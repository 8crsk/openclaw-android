package com.crsk.openclaw.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import com.crsk.openclaw.data.model.ToolCall
import com.crsk.openclaw.data.model.ToolCallStatus
import com.crsk.openclaw.ui.theme.AccentBlue
import com.crsk.openclaw.ui.theme.Danger
import com.crsk.openclaw.ui.theme.Success
import com.crsk.openclaw.ui.theme.Surface
import com.crsk.openclaw.ui.theme.TextMuted
import com.crsk.openclaw.ui.theme.TextSecondary

/**
 * A tool-call chip the user can tap to expand and see exactly what the agent did:
 * the argument summary, the full pretty-printed args, and the result preview. Collapsed
 * by default to preserve the calm iMessage feel; details are one tap away. This is the
 * post-hoc review surface — the live action is shown on the floating overlay.
 */
@Composable
fun ToolCallChip(tc: ToolCall) {
    val (tint, label) = when (tc.status) {
        ToolCallStatus.Pending -> TextMuted to "queued"
        ToolCallStatus.Running -> AccentBlue to "running"
        ToolCallStatus.Done -> Success to "done"
        ToolCallStatus.Failed -> Danger to "failed"
    }
    val summary = remember(tc.callId, tc.argumentsJson) {
        ToolDetailFormatter.oneLineSummary(tc.name, tc.argumentsJson)
    }
    val hasDetail = tc.argumentsJson.isNotBlank() || !tc.resultPreview.isNullOrBlank()
    var expanded by remember(tc.callId) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f))
            .pointerInput(tc.callId) {
                detectTapGestures(onTap = { if (hasDetail) expanded = !expanded })
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (tc.status) {
                ToolCallStatus.Pending -> Icon(Lucide.Hourglass, null, tint = tint, modifier = Modifier.size(14.dp))
                ToolCallStatus.Running -> CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp), color = tint)
                ToolCallStatus.Done -> Icon(Lucide.CircleCheck, null, tint = tint, modifier = Modifier.size(14.dp))
                ToolCallStatus.Failed -> Icon(Lucide.CircleAlert, null, tint = tint, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            Icon(Lucide.Wrench, null, tint = tint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(tc.name.ifEmpty { "tool" }, style = MaterialTheme.typography.labelSmall, color = tint)
            if (summary != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            if (hasDetail) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp).rotate(chevronRotation),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (tc.argumentsJson.isNotBlank()) {
                    DetailBlock("Input", ToolDetailFormatter.prettyArgs(tc.argumentsJson))
                }
                if (!tc.resultPreview.isNullOrBlank()) {
                    if (tc.argumentsJson.isNotBlank()) Spacer(Modifier.size(8.dp))
                    DetailBlock("Result", tc.resultPreview)
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    Spacer(Modifier.size(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .heightIn(max = 160.dp)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}
