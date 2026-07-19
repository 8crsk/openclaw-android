package com.crsk.openclaw.ui.status

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.crsk.openclaw.ui.status.ToolkitChip as ToolkitChipModel
import com.crsk.openclaw.ui.components.AmbientGlowBackdrop
import com.crsk.openclaw.ui.components.GlassCard
import com.crsk.openclaw.ui.theme.AccentBlue
import com.crsk.openclaw.ui.theme.BorderHairline
import com.crsk.openclaw.ui.theme.BubbleSentTop
import com.crsk.openclaw.ui.theme.Success
import com.crsk.openclaw.ui.theme.Surface
import com.crsk.openclaw.ui.theme.SurfaceHigh
import com.crsk.openclaw.ui.theme.TextMuted
import com.crsk.openclaw.ui.theme.TextSecondary
import com.crsk.openclaw.ui.theme.Warning

private data class Capability(
    val id: CapabilityId,
    val label: String,
    val icon: ImageVector,
    val connected: Boolean,
)

@Composable
fun StatusScreen(
    onOpenIntegrations: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val featuredToolkits by viewModel.featuredToolkits.collectAsStateWithLifecycle()
    val pendingRedirect by viewModel.pendingRedirect.collectAsStateWithLifecycle()
    val connectError by viewModel.connectError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(connectError) {
        val msg = connectError ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        viewModel.consumeConnectError()
    }

    // OAuth handoff: when the VM sets a redirect URL, open Chrome and clear.
    LaunchedEffect(pendingRedirect) {
        val url = pendingRedirect ?: return@LaunchedEffect
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        viewModel.consumeRedirect()
    }

    // Every time the Status screen comes back to foreground (incl. returning from OAuth)
    // refresh connection statuses + restart gateway if anything went ACTIVE.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumeFromOAuth()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val capabilities = listOf(
        Capability(CapabilityId.Gateway, "Gateway", Icons.Filled.Bolt, connections.gateway),
        Capability(CapabilityId.Model, "AI model", Icons.Filled.AutoAwesome, connections.model),
        Capability(CapabilityId.UiAutomation, "UI control", Icons.Filled.TouchApp, connections.uiAutomation),
        Capability(CapabilityId.Shell, "Shell", Icons.Filled.Terminal, connections.shell),
        Capability(CapabilityId.Plugins, "Plugins", Icons.Filled.Extension, connections.plugins),
    )

    val onEnable: (CapabilityId) -> Unit = { id ->
        when (id) {
            CapabilityId.Gateway -> com.crsk.openclaw.service.GatewayService.start(context)
            CapabilityId.UiAutomation -> runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            CapabilityId.Model, CapabilityId.Shell -> onOpenSettings()
            CapabilityId.Plugins -> onOpenIntegrations()
        }
    }

    AmbientGlowBackdrop {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ConnectionsCard(
                    capabilities = capabilities,
                    featuredToolkits = featuredToolkits,
                    onConnectToolkit = viewModel::connect,
                    onBrowseAll = onOpenIntegrations,
                    onEnable = onEnable,
                )
                UsageCard()
            }
        }
    }
}

@Composable
private fun ConnectionsCard(
    capabilities: List<Capability>,
    featuredToolkits: List<ToolkitChipModel>,
    onConnectToolkit: (String) -> Unit,
    onBrowseAll: () -> Unit,
    onEnable: (CapabilityId) -> Unit,
) {
    val connectedCount = capabilities.count { it.connected }
    val activeToolkits = featuredToolkits.count { it.isConnected }
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 20.dp) {
        Column {
            // ── System capabilities header ───────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "System",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$connectedCount/${capabilities.size} active",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(16.dp))
            capabilities.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { cap ->
                        Box(modifier = Modifier.weight(1f)) {
                            ConnectionBubble(cap, onEnable)
                        }
                    }
                    repeat(3 - rowItems.size) { Box(modifier = Modifier.weight(1f)) {} }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Apps subsection (Composio) ────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (featuredToolkits.isNotEmpty()) {
                    Text(
                        text = "$activeToolkits connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (featuredToolkits.isEmpty()) {
                Text(
                    text = "Loading integrations…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    featuredToolkits.forEach { chip ->
                        ToolkitTile(
                            chip = chip,
                            onClick = { if (!chip.isConnected) onConnectToolkit(chip.toolkit.slug) },
                        )
                    }
                    BrowseAllTile(onClick = onBrowseAll)
                }
            }
        }
    }
}

@Composable
private fun ToolkitTile(chip: ToolkitChipModel, onClick: () -> Unit) {
    val ring = when {
        chip.isConnected -> Success
        chip.isPending -> Warning
        else -> Color.Transparent
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val logo = chip.toolkit.logoUrl
                if (!logo.isNullOrBlank()) {
                    AsyncImage(
                        model = logo,
                        contentDescription = chip.toolkit.name,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Text(
                        text = chip.toolkit.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentBlue,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (chip.isConnected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Success)
                        .align(Alignment.BottomEnd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = chip.toolkit.name,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun BrowseAllTile(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                color = AccentBlue,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Browse all",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConnectionBubble(cap: Capability, onEnable: (CapabilityId) -> Unit) {
    val cta = CapabilityActions.ctaLabel(cap.id, cap.connected)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .then(if (cta != null) Modifier.clickable { onEnable(cap.id) } else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .then(
                        if (cap.connected) {
                            Modifier.background(
                                brush = Brush.linearGradient(listOf(BubbleSentTop, Color(0xFF5E5CE6))),
                            )
                        } else {
                            Modifier
                                .background(Surface)
                                .border(1.dp, BorderHairline, CircleShape)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = cap.icon,
                    contentDescription = cap.label,
                    tint = if (cap.connected) Color.White else TextMuted,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (cap.connected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(Success),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = cap.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (cap.connected) MaterialTheme.colorScheme.onSurface else TextMuted,
        )
        Text(
            text = if (cap.connected) "Connected" else (cta ?: "Off"),
            style = MaterialTheme.typography.labelSmall,
            color = if (cap.connected) Success else AccentBlue,
        )
    }
}

@Composable
private fun UsageCard() {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 20.dp) {
        Column {
            Text(
                text = "Usage",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Success),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Unlimited",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Every chat runs on your own API key — no middleman, no message limit.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

