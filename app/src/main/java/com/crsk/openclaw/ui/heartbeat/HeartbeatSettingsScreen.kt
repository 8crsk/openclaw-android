package com.crsk.openclaw.ui.heartbeat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsk.openclaw.ui.components.PrimaryButton

@Composable
fun HeartbeatSettingsScreen(viewModel: HeartbeatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastRun by viewModel.lastRun.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Heartbeat", style = MaterialTheme.typography.titleLarge)
        Text(
            "Run the agent on a schedule. It reads HEARTBEAT.md and acts when something needs attention.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = state.enabled, onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } })
            Spacer(Modifier.height(0.dp))
            Text("  Enable proactive checks")
        }

        Text("Interval")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("15m", "30m", "1h", "2h").forEach { opt ->
                OutlinedButton(
                    onClick = { viewModel.update { it.copy(interval = opt) } },
                    shape = RoundedCornerShape(20.dp),
                ) { Text(if (state.interval == opt) "● $opt" else opt) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.activeHoursStart,
                onValueChange = { v -> viewModel.update { it.copy(activeHoursStart = v) } },
                label = { Text("Active from (HH:mm)") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.activeHoursEnd,
                onValueChange = { v -> viewModel.update { it.copy(activeHoursEnd = v) } },
                label = { Text("Active until (HH:mm)") },
                modifier = Modifier.weight(1f),
            )
        }

        Text("Tasks (HEARTBEAT.md)")
        OutlinedTextField(
            value = state.tasksMarkdown,
            onValueChange = { v -> viewModel.update { it.copy(tasksMarkdown = v) } },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            placeholder = { Text("# Heartbeat tasks\n- example: summarise unread WhatsApp messages") },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // PrimaryButton internally does fillMaxWidth(); wrap in weight(1f) so it
            // shares the Row with "Run now" instead of consuming the full width.
            PrimaryButton(text = "Save", onClick = viewModel::save, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = viewModel::runNow,
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Run now") }
        }

        lastRun?.let { lr ->
            val ago = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(lr.timestampMs))
            val tail = when {
                lr.skipped -> "skipped (nothing to do or tasks empty)"
                lr.ok -> "${lr.toolCount} tool call(s)"
                else -> "failed"
            }
            Text("Last run: $ago · $tail", style = MaterialTheme.typography.bodySmall)
        }
    }
}
