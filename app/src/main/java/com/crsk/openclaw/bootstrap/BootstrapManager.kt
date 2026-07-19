package com.crsk.openclaw.bootstrap

import com.crsk.openclaw.node.NodeSetup
import com.crsk.openclaw.node.NodeSetupState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BootstrapState {
    data object NotStarted : BootstrapState
    data class Downloading(val progress: Float) : BootstrapState
    data class Extracting(val progress: Float) : BootstrapState
    data object FixingPaths : BootstrapState
    data class Running(val step: String, val line: String = "") : BootstrapState
    data object Complete : BootstrapState
    data class Failed(val error: String) : BootstrapState
}

@Singleton
class BootstrapManager @Inject constructor(
    private val nodeSetup: NodeSetup,
) {
    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.NotStarted)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    fun isBootstrapped(): Boolean = nodeSetup.isProvisioned()

    suspend fun bootstrap(port: Int) = coroutineScope {
        val collector = launch {
            nodeSetup.state.collectLatest { ns ->
                _state.value = when (ns) {
                    is NodeSetupState.NotStarted -> BootstrapState.NotStarted
                    is NodeSetupState.Step -> when (ns.phase) {
                        "assets"    -> BootstrapState.Downloading(ns.progress)
                        "setup"     -> BootstrapState.Extracting(ns.progress)
                        "npm"       -> BootstrapState.Running("Extracting npm…", ns.detail)
                        "openclaw"  -> BootstrapState.Running("Installing OpenClaw…", ns.detail)
                        "config"    -> BootstrapState.Running("Writing gateway config…", ns.detail)
                        "mcp"       -> BootstrapState.Running("Setting up MCP shim…", ns.detail)
                        "starting"  -> BootstrapState.Running("Starting gateway…", ns.detail)
                        else        -> BootstrapState.Running(ns.phase, ns.detail)
                    }
                    is NodeSetupState.Failed  -> BootstrapState.Failed(ns.error)
                    is NodeSetupState.Complete -> BootstrapState.Complete
                }
            }
        }

        val result = nodeSetup.ensureRunning(port)
        collector.cancel()

        _state.value = result.fold(
            onSuccess = { BootstrapState.Complete },
            onFailure = { BootstrapState.Failed(it.message ?: "Setup failed") },
        )
    }
}
