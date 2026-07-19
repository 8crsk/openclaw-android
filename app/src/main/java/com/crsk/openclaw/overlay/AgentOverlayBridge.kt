package com.crsk.openclaw.overlay

import com.crsk.openclaw.data.network.ws.ApprovalKind
import com.crsk.openclaw.data.network.ws.ApprovalRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared state bus between [com.crsk.openclaw.ui.chat.ChatViewModel] (writer) and
 * [OverlayManager] (reader). Holds the current [OverlayUiState] and the stop signal.
 */
@Singleton
class AgentOverlayBridge @Inject constructor() {

    private val _overlayState = MutableStateFlow(OverlayUiState())
    val overlayState: StateFlow<OverlayUiState> = _overlayState.asStateFlow()

    private val _stopRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopRequested: SharedFlow<Unit> = _stopRequested.asSharedFlow()

    fun onAgentStarted() {
        _overlayState.update {
            OverlayUiState(visible = true)
        }
    }

    fun onToolStarted(name: String) {
        _overlayState.update { state ->
            val newRecent = (listOf(name) + state.recentActions).take(MAX_RECENT)
            state.copy(currentAction = name, recentActions = newRecent)
        }
    }

    fun onToolFinished() {
        _overlayState.update { it.copy(currentAction = null) }
    }

    fun onApprovalRequired(request: ApprovalRequest) {
        _overlayState.update { it.copy(pendingApproval = request, isExpanded = true) }
    }

    fun onApprovalResolved() {
        _overlayState.update { it.copy(pendingApproval = null) }
    }

    fun onAgentDone() {
        _overlayState.value = OverlayUiState(visible = false)
    }

    fun onAgentStopped() {
        _overlayState.value = OverlayUiState(visible = false)
    }

    fun setExpanded(expanded: Boolean) {
        _overlayState.update { it.copy(isExpanded = expanded) }
    }

    fun setCurrentPackage(packageName: String?) {
        _overlayState.update { it.copy(currentPackage = packageName) }
    }

    fun requestStop() {
        _stopRequested.tryEmit(Unit)
    }

    private val _approvalDecision = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val approvalDecision: SharedFlow<Boolean> = _approvalDecision.asSharedFlow()

    fun submitApprovalDecision(allow: Boolean) {
        _approvalDecision.tryEmit(allow)
    }

    /**
     * Surface a "manual control needed" prompt on the overlay bubble and suspend until the
     * user taps allow/deny (reuses [submitApprovalDecision] → [approvalDecision]). Self-contained:
     * does NOT touch ApprovalChannel / openclaw resolution. Returns false on timeout so the
     * agent's take_over never hangs if the bubble overlay is disabled.
     *
     * Safe vs the openclaw exec-approval path: ChatViewModel.resolveApproval early-returns when
     * its own pendingApproval is null (it is, for a take_over), so the shared decision no-ops there.
     */
    suspend fun requestManualHandoff(reason: String, timeoutMs: Long = 120_000L): Boolean {
        val req = ApprovalRequest(
            id = "takeover-${java.util.UUID.randomUUID()}",
            kind = ApprovalKind.Exec,
            summary = "Manual control needed",
            detail = reason,
        )
        _overlayState.update { it.copy(visible = true, isExpanded = true, pendingApproval = req) }
        val decision = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            approvalDecision.first()
        }
        _overlayState.update { it.copy(pendingApproval = null) }
        return decision ?: false
    }

    companion object {
        private const val MAX_RECENT = 5
    }
}
