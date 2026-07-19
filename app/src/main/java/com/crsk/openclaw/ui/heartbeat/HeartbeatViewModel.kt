package com.crsk.openclaw.ui.heartbeat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.data.heartbeat.HeartbeatConfigStore
import com.crsk.openclaw.data.heartbeat.HeartbeatState
import com.crsk.openclaw.data.network.ws.HeartbeatChannel
import com.crsk.openclaw.data.network.ws.HeartbeatLastRun
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HeartbeatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewayProcess: GatewayProcess,
    private val heartbeatChannel: HeartbeatChannel,
) : ViewModel() {

    private val configDir = File(context.filesDir, "home/.openclaw")
    private val store = HeartbeatConfigStore(configDir)

    private val _state = MutableStateFlow(store.read())
    val state: StateFlow<HeartbeatState> = _state.asStateFlow()
    val lastRun: StateFlow<HeartbeatLastRun?> = heartbeatChannel.lastRun

    fun update(transform: (HeartbeatState) -> HeartbeatState) {
        _state.value = transform(_state.value)
    }

    fun save() {
        val snapshot = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            store.write(snapshot)
            store.writeTasks(snapshot.tasksMarkdown)
            withContext(Dispatchers.Main) { gatewayProcess.restart() }
        }
    }

    fun runNow() {
        val originalInterval = _state.value.interval
        viewModelScope.launch(Dispatchers.IO) {
            // Persist original interval BEFORE clobbering, so a crash mid-trigger
            // can't strand the user at "1m".
            store.write(_state.value.copy(interval = originalInterval, enabled = true))
            store.write(_state.value.copy(interval = "1m", enabled = true))
            withContext(Dispatchers.Main) { gatewayProcess.restart() }
            // Wait up to 90s for one heartbeat to complete.
            val deadline = System.currentTimeMillis() + 90_000L
            var seenAt: Long? = null
            while (System.currentTimeMillis() < deadline) {
                val ts = heartbeatChannel.lastRun.value?.timestampMs
                if (ts != null && (seenAt == null || ts > seenAt)) { seenAt = ts; break }
                kotlinx.coroutines.delay(1_000L)
            }
            store.write(_state.value.copy(interval = originalInterval, enabled = true))
            withContext(Dispatchers.Main) { gatewayProcess.restart() }
        }
    }
}
