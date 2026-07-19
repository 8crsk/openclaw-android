package com.crsk.openclaw.service

import com.crsk.openclaw.accessibility.A11yStatus
import com.crsk.openclaw.accessibility.AccessibilityServiceHolder
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.shizuku.ShizukuManager
import com.crsk.openclaw.shizuku.ShizukuStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ServiceHealth {
    HEALTHY,
    DEGRADED,
    DOWN,
    RECOVERING,
}

data class HealthState(
    val gateway: ServiceHealth = ServiceHealth.DOWN,
    val shizuku: ServiceHealth = ServiceHealth.DOWN,
    val accessibility: ServiceHealth = ServiceHealth.DOWN,
    val overall: ServiceHealth = ServiceHealth.DOWN,
    val gatewayConsecutiveFailures: Int = 0,
    val gatewayRestarts: Int = 0,
    val lastCheck: Long = 0,
    val detail: String = "Not started",
)

@Singleton
class GatewayHealthMonitor @Inject constructor(
    private val gatewayProcess: GatewayProcess,
    private val shizukuManager: ShizukuManager,
    private val accessibilityHolder: AccessibilityServiceHolder,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health.asStateFlow()

    // Single keep-alive client shared across probes. Replaces per-probe
    // HttpURLConnection (one TCP setup every 30 s) with a pooled connection.
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch { monitorLoop() }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        _health.value = HealthState()
    }

    private suspend fun monitorLoop() {
        delay(5_000)
        // Read the port once; gatewayPort changes are rare (Settings) and the
        // service restarts on change. Reading it inside the loop was 1 DataStore
        // round-trip per probe.
        var port = preferences.gatewayPort.first()
        scope.launch {
            preferences.gatewayPort.collect { port = it }
        }
        while (scope.isActive) {
            val gatewayOk = probeGateway(port)
            val shizukuOk = probeShizuku()
            val prev = _health.value

            val gwHealth: ServiceHealth
            val gwFailures: Int
            val gwRestarts: Int

            if (gatewayOk) {
                gwHealth = ServiceHealth.HEALTHY
                gwFailures = 0
                gwRestarts = prev.gatewayRestarts
            } else {
                gwFailures = prev.gatewayConsecutiveFailures + 1
                gwRestarts = prev.gatewayRestarts

                if (gwFailures >= GATEWAY_FAILURE_THRESHOLD && prev.gateway != ServiceHealth.RECOVERING && prev.gatewayRestarts < MAX_RESTARTS) {
                    gwHealth = ServiceHealth.RECOVERING
                    scope.launch { attemptRestart(port) }
                } else if (prev.gateway == ServiceHealth.RECOVERING) {
                    gwHealth = ServiceHealth.RECOVERING
                } else {
                    gwHealth = ServiceHealth.DOWN
                }
            }

            val szHealth = if (shizukuOk) ServiceHealth.HEALTHY else ServiceHealth.DOWN
            val a11yOk = probeAccessibility()
            val a11yHealth = if (a11yOk) ServiceHealth.HEALTHY else ServiceHealth.DOWN

            // Shizuku is optional — its absence doesn't degrade overall health.
            // Only gateway down or a11y disabled counts as degraded.
            val overall = when {
                gwHealth == ServiceHealth.HEALTHY && a11yOk -> ServiceHealth.HEALTHY
                gwHealth == ServiceHealth.HEALTHY && !a11yOk -> ServiceHealth.DEGRADED
                gwHealth == ServiceHealth.RECOVERING -> ServiceHealth.RECOVERING
                else -> ServiceHealth.DOWN
            }

            val detail = buildString {
                append("Gateway: ")
                append(when (gwHealth) {
                    ServiceHealth.HEALTHY -> "online"
                    ServiceHealth.RECOVERING -> "restarting (attempt ${prev.gatewayRestarts + 1})..."
                    ServiceHealth.DOWN -> "down ($gwFailures consecutive failures)"
                    ServiceHealth.DEGRADED -> "degraded"
                })
                append(" | Shell: ")
                append(if (shizukuOk) "active" else "off (optional)")
                append(" | UI: ")
                append(if (a11yOk) "active" else "off")
            }

            _health.value = HealthState(
                gateway = gwHealth,
                shizuku = szHealth,
                accessibility = a11yHealth,
                overall = overall,
                gatewayConsecutiveFailures = gwFailures,
                gatewayRestarts = gwRestarts,
                lastCheck = System.currentTimeMillis(),
                detail = detail,
            )

            delay(PROBE_INTERVAL_MS)
        }
    }

    private suspend fun attemptRestart(port: Int) {
        val prev = _health.value
        try {
            gatewayProcess.start(port)

            delay(5_000)
            if (probeGateway(port)) {
                _health.value = _health.value.copy(
                    gateway = ServiceHealth.HEALTHY,
                    gatewayConsecutiveFailures = 0,
                    gatewayRestarts = prev.gatewayRestarts + 1,
                )
            }
        } catch (_: Exception) {
            _health.value = _health.value.copy(
                gateway = ServiceHealth.DOWN,
                gatewayRestarts = prev.gatewayRestarts + 1,
            )
        }
    }

    private fun probeGateway(port: Int): Boolean = try {
        // Must hit /health and verify JSON: the gateway's SPA fallback returns 200 +
        // text/html for any unmatched URL, so a bare 200 is a false positive.
        // Uses the shared OkHttpClient so the loopback TCP connection is reused
        // across probes (1 socket, not N).
        val req = Request.Builder().url("http://127.0.0.1:$port/health").get().build()
        probeClient.newCall(req).execute().use { res ->
            res.isSuccessful && (res.header("Content-Type")?.contains("application/json", ignoreCase = true) == true)
        }
    } catch (_: Exception) {
        false
    }

    private fun probeShizuku(): Boolean = try {
        shizukuManager.status.value is ShizukuStatus.Ready
    } catch (_: Exception) {
        false
    }

    private fun probeAccessibility(): Boolean = try {
        accessibilityHolder.status.value == A11yStatus.CONNECTED
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val PROBE_INTERVAL_MS = 30_000L
        private const val MAX_RESTARTS = 5
        // Raised 3→5 so a single loopback hiccup doesn't trigger a restart loop.
        private const val GATEWAY_FAILURE_THRESHOLD = 5
    }
}
