package com.crsk.openclaw.overlay

import com.crsk.openclaw.data.network.ws.ApprovalRequest

data class OverlayUiState(
    val visible: Boolean = false,
    val currentAction: String? = null,
    val recentActions: List<String> = emptyList(),  // newest first, max 5
    val pendingApproval: ApprovalRequest? = null,
    val isExpanded: Boolean = false,
    val currentPackage: String? = null,
)
