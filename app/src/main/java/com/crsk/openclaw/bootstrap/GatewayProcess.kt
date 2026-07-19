package com.crsk.openclaw.bootstrap

import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.node.NodeProcess
import com.crsk.openclaw.node.NodeSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GatewayStatus {
    data object Stopped : GatewayStatus
    data object Starting : GatewayStatus
    data class Running(val port: Int) : GatewayStatus
    data class Failed(val error: String) : GatewayStatus
}

@Singleton
class GatewayProcess @Inject constructor(
    private val nodeSetup: NodeSetup,
    private val nodeProcess: NodeProcess,
    private val preferences: AppPreferences,
) {
    private val _status = MutableStateFlow<GatewayStatus>(GatewayStatus.Stopped)
    val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    private val _mcpReady = MutableStateFlow(false)
    val mcpReady: StateFlow<Boolean> = _mcpReady.asStateFlow()

    suspend fun start(port: Int = 3000) =
        withContext(Dispatchers.IO) {
            if (_status.value is GatewayStatus.Running) return@withContext
            _status.value = GatewayStatus.Starting

            val result = nodeSetup.ensureRunning(port)
            _status.value = result.fold(
                onSuccess = {
                    _mcpReady.value = true
                    GatewayStatus.Running(port)
                },
                onFailure = { GatewayStatus.Failed(it.message ?: "Failed to start gateway") },
            )
        }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun stop() {
        ioScope.launch { runCatching { nodeSetup.stop() } }
        _status.value = GatewayStatus.Stopped
        _mcpReady.value = false
    }

    suspend fun restart() {
        val port = preferences.gatewayPort.first()
        stop()
        start(port)
    }

    fun isRunning(): Boolean = _status.value is GatewayStatus.Running

    suspend fun probe(port: Int): Boolean = withContext(Dispatchers.IO) {
        repeat(3) {
            if (nodeProcess.checkHealth(port)) {
                _status.value = GatewayStatus.Running(port)
                return@withContext true
            }
            delay(500)
        }
        false
    }
}
