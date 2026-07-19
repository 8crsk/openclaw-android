package com.crsk.openclaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.crsk.openclaw.R
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.shizuku.ShizukuBridge
import com.crsk.openclaw.shizuku.ShizukuManager
import com.crsk.openclaw.shizuku.ShizukuStatus
import com.crsk.openclaw.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GatewayService : Service() {

    @Inject lateinit var gatewayProcess: GatewayProcess
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var shizukuManager: ShizukuManager
    @Inject lateinit var shizukuBridge: ShizukuBridge
    @Inject lateinit var healthMonitor: GatewayHealthMonitor
    @Inject lateinit var wsRpcClient: com.crsk.openclaw.data.network.ws.WsRpcClient
    @Inject lateinit var heartbeatChannel: com.crsk.openclaw.data.network.ws.HeartbeatChannel
    @Inject lateinit var heartbeatNotifier: HeartbeatNotifier
    @Inject lateinit var overlayBridge: com.crsk.openclaw.overlay.AgentOverlayBridge

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var overlayManager: com.crsk.openclaw.overlay.OverlayManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Survival mitigations are applied once per service lifetime, after Shizuku is ready. */
    @Volatile private var survivalApplied = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        overlayManager = com.crsk.openclaw.overlay.OverlayManager(
            context = applicationContext,
            bridge = overlayBridge,
            preferences = preferences,
        ).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("4AIs Gateway")
            .setContentText("Starting...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLock()

        shizukuManager.init()
        runCatching { shizukuBridge.startBridge() }
            .onSuccess { Log.i(TAG, "ShizukuBridge started on 127.0.0.1:3001") }
            .onFailure { Log.e(TAG, "ShizukuBridge failed to start", it) }

        // Once Shizuku is privileged, neutralise Android's background killers so the
        // gateway process survives backgrounding on every OEM. Applied once per launch.
        serviceScope.launch {
            shizukuManager.status.collect { st ->
                if (st is ShizukuStatus.Ready) applySurvivalMitigations()
            }
        }

        serviceScope.launch {
            // Guard against duplicate starts. Android START_STICKY will redeliver
            // onStartCommand whenever the system recreates the service after a
            // memory-pressure kill, and our own internal re-launches (onTaskRemoved,
            // health-recovery loops) can fire onStartCommand a second time within
            // seconds. Without this guard each call would stop+restart the gateway
            // unnecessarily, causing the "two rapid gateway restarts" log pattern
            // and dropping any in-flight WS reconnect.
            if (gatewayProcess.isRunning()) {
                Log.i(TAG, "onStartCommand: gateway already running, skipping start")
            } else {
                val port = preferences.gatewayPort.first()
                gatewayProcess.start(port)
            }

            // Start health monitoring only after gateway has started
            if (gatewayProcess.isRunning()) {
                healthMonitor.start()
            }
        }

        serviceScope.launch {
            healthMonitor.health.collect { state ->
                updateNotificationFromHealth(state)
            }
        }

        serviceScope.launch {
            // Open the WS proactively after the GATEWAY (only) becomes healthy AND
            // restart it on every transition. We watch `state.gateway`, not
            // `state.overall`, because `overall` is DEGRADED whenever Shizuku is
            // disconnected — which is the user's normal state when not actively
            // controlling the phone, so HEALTHY would never fire. The WS only needs
            // the gateway to be up; Shizuku is unrelated.
            var wasGatewayHealthy = false
            healthMonitor.health.collect { state ->
                val isGatewayHealthy = state.gateway == ServiceHealth.HEALTHY
                if (isGatewayHealthy && !wasGatewayHealthy) {
                    Log.i(TAG, "gateway healthy → reconnecting WS + starting HeartbeatChannel")
                    runCatching { wsRpcClient.close() }
                    val port = preferences.gatewayPort.first()
                    runCatching { wsRpcClient.ensureConnected(port) }
                        .onFailure { Log.w(TAG, "ws reconnect after gateway start failed", it) }
                    heartbeatChannel.start()
                }
                wasGatewayHealthy = isGatewayHealthy
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { overlayManager?.stop() }
        overlayManager = null
        runCatching { heartbeatChannel.stop() }
        runCatching { wsRpcClient.close() }
        healthMonitor.stop()
        runCatching { shizukuBridge.stopBridge() }
        runCatching { shizukuManager.shutdown() }
        gatewayProcess.stop()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * The user swiped the app from recents. Keep the gateway alive: re-launch the
     * foreground service. Combined with START_STICKY this makes the gateway resilient
     * to task removal on every OEM.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved — re-launching foreground service to stay alive")
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, GatewayService::class.java),
            )
        }.onFailure { Log.w(TAG, "failed to re-launch service from onTaskRemoved", it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Disable Android's phantom-process killing and background restrictions via Shizuku
     * (uid 2000 / shell). Device-agnostic — works on every OEM. Settings reset on reboot,
     * so this re-runs each launch. Best-effort: failures are logged, never fatal.
     */
    private fun applySurvivalMitigations() {
        if (survivalApplied) return
        survivalApplied = true
        val pkg = packageName
        val cmds = listOf(
            listOf("settings", "put", "global", "settings_enable_monitor_phantom_procs", "false"),
            listOf("device_config", "set_sync_disabled_for_tests", "persistent"),
            listOf("device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"),
            listOf("dumpsys", "deviceidle", "whitelist", "+$pkg"),
            listOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
            listOf("am", "set-standby-bucket", pkg, "active"),
        )
        serviceScope.launch {
            for (cmd in cmds) {
                runCatching { shizukuManager.exec(cmd, null, 5_000) }
                    .onSuccess { (exit, out, err) ->
                        val tail = err.ifBlank { out }.trim()
                        Log.i(TAG, "survival: ${cmd.joinToString(" ")} -> exit=$exit $tail")
                    }
                    .onFailure { Log.w(TAG, "survival cmd failed: ${cmd.joinToString(" ")}", it) }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gateway Service", NotificationManager.IMPORTANCE_LOW)
            )

            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Gateway Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Critical alerts when the gateway or phone control is lost"
                }
            )
        }
        heartbeatNotifier.ensureChannel()
    }

    private fun updateNotificationFromHealth(state: HealthState) {
        val nm = getSystemService(NotificationManager::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, text, ongoing) = when (state.overall) {
            ServiceHealth.HEALTHY -> Triple(
                "4AIs — All systems go",
                "Gateway online | Phone control active | UI automation active",
                true,
            )
            ServiceHealth.DEGRADED -> Triple(
                "4AIs — Partially connected",
                state.detail,
                true,
            )
            ServiceHealth.RECOVERING -> Triple(
                "4AIs — Restarting gateway",
                "Auto-recovering... (attempt ${state.gatewayRestarts + 1})",
                true,
            )
            ServiceHealth.DOWN -> Triple(
                "4AIs — Gateway down",
                state.detail,
                true,
            )
        }

        val serviceNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(ongoing)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID, serviceNotification)

        // Fire a high-priority alert when Shizuku dies (once, not every 30s)
        if (state.shizuku == ServiceHealth.DOWN && state.gateway == ServiceHealth.HEALTHY) {
            val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setContentTitle("Phone control lost")
                .setContentText("Shizuku disconnected. Tap to re-pair wireless debugging.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(ALERT_NOTIFICATION_ID, alertNotification)
        } else if (state.shizuku == ServiceHealth.HEALTHY) {
            nm.cancel(ALERT_NOTIFICATION_ID)
        }
    }

    private fun acquireWakeLock() {
        // Guard against duplicate acquire when onStartCommand re-fires (START_STICKY
        // redelivery, onTaskRemoved re-launch). Without this, a new WakeLock was
        // allocated each call and the previous reference leaked while still held —
        // the field assignment dropped the old handle but the system kept the lock.
        wakeLock?.let { if (it.isHeld) return }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "4ais:gateway").apply {
            setReferenceCounted(false)
            // No timeout — the gateway must stay alive as long as the foreground
            // service runs. Released deterministically in onDestroy().
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "gateway_channel"
        private const val CHANNEL_ALERT = "gateway_alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}
