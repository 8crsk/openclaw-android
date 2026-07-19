package com.crsk.openclaw.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crsk.openclaw.data.network.ws.ApprovalRequest
import com.crsk.openclaw.data.network.ws.RiskLevel
import com.crsk.openclaw.ui.theme.Danger
import com.crsk.openclaw.ui.theme.Success
import com.crsk.openclaw.ui.theme.Warning

@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Approve agent action?") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (request.riskLevel != RiskLevel.Low) {
                    RiskBadge(request.riskLevel, request.riskCategory)
                    Spacer(Modifier.height(12.dp))
                }
                Text(request.summary, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Text(request.detail, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } },
    )
}

@Composable
private fun RiskBadge(level: RiskLevel, category: String?) {
    val (tint, label) = when (level) {
        RiskLevel.High -> Danger to "High risk"
        RiskLevel.Elevated -> Warning to "Elevated"
        RiskLevel.Low -> Success to "Low"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = if (category != null) "⚠ $label · $category" else "⚠ $label",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
