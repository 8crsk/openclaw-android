package com.crsk.openclaw.ui.settings

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsk.openclaw.data.providers.ProviderCatalog
import com.crsk.openclaw.data.providers.ProviderModel
import com.crsk.openclaw.accessibility.A11yStatus
import com.crsk.openclaw.ui.components.AmbientGlowBackdrop
import com.crsk.openclaw.ui.components.GlassCard
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.shizuku.ShizukuStatus
import com.crsk.openclaw.ui.components.PrimaryButton
import com.crsk.openclaw.ui.theme.BorderHairline
import com.crsk.openclaw.ui.theme.Cyan
import com.crsk.openclaw.ui.theme.Danger
import com.crsk.openclaw.ui.theme.Success
import com.crsk.openclaw.ui.theme.SurfaceGlass
import com.crsk.openclaw.ui.theme.TextMuted
import com.crsk.openclaw.ui.theme.TextSecondary


@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onOpenHeartbeat: () -> Unit = {},
) {
    val savedKey by viewModel.savedKey.collectAsStateWithLifecycle()
    val selectedProvider by viewModel.selectedProvider.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val keyEdit by viewModel.keyEdit.collectAsStateWithLifecycle()
    val gatewayStatus by viewModel.gatewayStatus.collectAsStateWithLifecycle()
    val autoStart by viewModel.autoStartOnBoot.collectAsStateWithLifecycle()
    val autoApprove by viewModel.autoApproveAgentActions.collectAsStateWithLifecycle()
    val overlayEnabled by viewModel.overlayEnabled.collectAsStateWithLifecycle()
    val agentVisionEnabled by viewModel.agentVisionEnabled.collectAsStateWithLifecycle()
    val reflectionEnabled by viewModel.reflectionEnabled.collectAsStateWithLifecycle()
    val appDocsSummary by viewModel.appDocsSummary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshAppDocs() }
    val shizukuStatus by viewModel.shizukuStatus.collectAsStateWithLifecycle()
    val shizukuTestResult by viewModel.shizukuTestResult.collectAsStateWithLifecycle()
    val shizukuInstalled by viewModel.shizukuInstalled.collectAsStateWithLifecycle()
    val a11yStatus by viewModel.a11yStatus.collectAsStateWithLifecycle()
    val mcpReady by viewModel.mcpReady.collectAsStateWithLifecycle()
    val batteryOptimized by viewModel.batteryOptimized.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(savedKey) {
        if (keyEdit.draft.isBlank() && savedKey.isNotBlank()) {
            viewModel.keyDraftChanged(savedKey)
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshBatteryStatus() }

    AmbientGlowBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = 14.dp,
                onClick = onOpenHeartbeat,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = Cyan)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Heartbeat", style = MaterialTheme.typography.titleSmall)
                        Text("Proactive scheduled agent runs", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionHeader("Gateway")
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val statusColor = when (gatewayStatus) {
                            is GatewayStatus.Running -> Cyan
                            is GatewayStatus.Starting -> Color.Yellow
                            is GatewayStatus.Failed -> Danger
                            is GatewayStatus.Stopped -> TextMuted
                        }
                        val statusText = when (gatewayStatus) {
                            is GatewayStatus.Running -> "Running on port ${(gatewayStatus as GatewayStatus.Running).port}"
                            is GatewayStatus.Starting -> "Starting…"
                            is GatewayStatus.Failed -> "Failed: ${(gatewayStatus as GatewayStatus.Failed).error}"
                            is GatewayStatus.Stopped -> "Stopped"
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(statusColor, CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val isRunning = gatewayStatus is GatewayStatus.Running || gatewayStatus is GatewayStatus.Starting
                    PrimaryButton(
                        text = if (isRunning) "Stop gateway" else "Start gateway",
                        icon = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        onClick = { if (isRunning) viewModel.stopGateway() else viewModel.startGateway() },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Start on boot",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = autoStart,
                            onCheckedChange = { viewModel.toggleAutoStart() },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-approve agent actions",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Tools run automatically. Toggle off to confirm each action.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = autoApprove,
                            onCheckedChange = { viewModel.toggleAutoApprove() },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    var hasOverlayPerm by remember {
                        mutableStateOf(com.crsk.openclaw.overlay.OverlayPermissionHelper.hasPermission(context))
                    }
                    LaunchedEffect(Unit) {
                        hasOverlayPerm = com.crsk.openclaw.overlay.OverlayPermissionHelper.hasPermission(context)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show floating overlay when agent is working",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "A small bubble shows what the agent is doing, even in other apps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setOverlayEnabled(enabled)
                                if (enabled && !hasOverlayPerm) {
                                    val intent = com.crsk.openclaw.overlay.OverlayPermissionHelper.buildGrantIntent(context.packageName)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    runCatching { context.startActivity(intent) }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan),
                        )
                    }
                    if (overlayEnabled && !hasOverlayPerm) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Permission not granted yet — tap to open Settings.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val intent = com.crsk.openclaw.overlay.OverlayPermissionHelper.buildGrantIntent(context.packageName)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    runCatching { context.startActivity(intent) }
                                }
                                .padding(horizontal = 0.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show agent vision",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Draw element highlights on screen while the agent acts. No extra permission needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = agentVisionEnabled,
                            onCheckedChange = { viewModel.setAgentVisionEnabled(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Verify each action",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Tell the agent to glance at the screen after each tap and replan if it didn't work.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = reflectionEnabled,
                            onCheckedChange = { viewModel.setReflectionEnabled(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App knowledge",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val subtitle = if (appDocsSummary.isEmpty()) {
                                "Agent hasn't saved any app navigation hints yet."
                            } else {
                                val total = appDocsSummary.sumOf { it.second }
                                "$total hints across ${appDocsSummary.size} app(s): " +
                                    appDocsSummary.take(3).joinToString(", ") {
                                        it.first.substringAfterLast('.') + " (${it.second})"
                                    } + if (appDocsSummary.size > 3) "…" else ""
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        if (appDocsSummary.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearAllAppDocs() }) {
                                Text("Clear", color = Cyan)
                            }
                        }
                    }
                }
            }

            if (gatewayStatus is GatewayStatus.Running && !mcpReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Phone automation tools not installed. Restart gateway with internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA726),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (batteryOptimized) {
                Spacer(Modifier.height(12.dp))
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    Column {
                        Text(
                            text = "Battery optimization is on",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFFA726),
                        )
                        Text(
                            text = "Android may kill the gateway in the background. Allow " +
                                "unrestricted battery use to keep it alive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(
                            text = "Allow background activity",
                            onClick = { viewModel.fixBatteryOptimization() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionHeader("UI automation")
            UiAutomationCard(
                status = a11yStatus,
                onOpenSettings = viewModel::openAccessibilitySettings,
            )

            Spacer(Modifier.height(24.dp))

            SectionHeader("Shell access (optional)")
            ShizukuOptionalCard(
                status = shizukuStatus,
                installed = shizukuInstalled,
                testResult = shizukuTestResult,
                onInstall = viewModel::installShizuku,
                onGrantPermission = viewModel::requestShizukuPermission,
                onReconnect = viewModel::reconnectShizuku,
                onRunTest = viewModel::runShizukuTest,
                onOpenShizuku = { viewModel.openShizukuApp(context) },
                onOpenWirelessDebugging = viewModel::openWirelessDebugging,
                onRefreshInstalled = viewModel::refreshShizukuInstalled,
            )

            Spacer(Modifier.height(24.dp))

            SectionHeader("AI provider")
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 8.dp) {
                Column {
                    ProviderCatalog.all.forEachIndexed { index, provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setProvider(provider.id) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = provider.id == selectedProvider.id,
                                onClick = { viewModel.setProvider(provider.id) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Cyan,
                                    unselectedColor = TextMuted,
                                ),
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (provider.freeTier) {
                                Text(
                                    text = "Free tier",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Success,
                                )
                            }
                        }
                        if (index < ProviderCatalog.all.lastIndex) {
                            HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("API key")
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column {
                    OutlinedTextField(
                        value = keyEdit.draft,
                        onValueChange = viewModel::keyDraftChanged,
                        label = { Text(selectedProvider.keyLabel, color = TextSecondary) },
                        placeholder = { Text(selectedProvider.keyHint, color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle visibility",
                                    tint = TextSecondary,
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                            cursorColor = Cyan,
                            focusedContainerColor = SurfaceGlass,
                            unfocusedContainerColor = SurfaceGlass,
                        ),
                    )
                    keyEdit.message?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (keyEdit.isError) Danger else Success,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = if (keyEdit.isValidating) "Checking…" else "Save key",
                        onClick = viewModel::saveKey,
                        enabled = !keyEdit.isValidating && keyEdit.draft.isNotBlank() && keyEdit.draft != savedKey,
                    )
                    if (keyEdit.isValidating) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = Cyan,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Validating key…",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .clickable { openConsole(context, selectedProvider.consoleUrl) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenInBrowser,
                            contentDescription = null,
                            tint = Cyan,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Get a ${selectedProvider.displayName} key",
                            color = Cyan,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Model")
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 8.dp) {
                Column {
                    selectedProvider.models.forEachIndexed { index, model ->
                        ModelRow(
                            model = model,
                            selected = model.id == selectedModel,
                            onClick = { viewModel.setSelectedModel(model.id) },
                        )
                        if (index < selectedProvider.models.lastIndex) {
                            HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Integrations (optional)")
            val composioKeySaved by viewModel.composioKeySaved.collectAsStateWithLifecycle()
            var composioDraft by remember { mutableStateOf("") }
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column {
                    Text(
                        text = "Composio API key",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (composioKeySaved)
                            "Key saved. Connect Gmail, Notion, GitHub and more from the Status tab."
                        else
                            "Optional: paste a key from composio.dev to let the agent use Gmail, Notion, GitHub and 300+ other apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = composioDraft,
                        onValueChange = { composioDraft = it.trim() },
                        label = { Text("Composio key", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                            cursorColor = Cyan,
                            focusedContainerColor = SurfaceGlass,
                            unfocusedContainerColor = SurfaceGlass,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "Save Composio key",
                        onClick = { viewModel.saveComposioKey(composioDraft) },
                        enabled = composioDraft.isNotBlank(),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Privacy & data")
            val privacyMessage by viewModel.privacyMessage.collectAsStateWithLifecycle()
            var showResetConfirm by remember { mutableStateOf(false) }
            var showActivityLog by remember { mutableStateOf(false) }
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 8.dp) {
                Column {
                    PrivacyRow(
                        title = "Privacy Policy",
                        subtitle = "How we handle your data (DPDP, GDPR, CCPA)",
                        onClick = { viewModel.openPrivacyPolicy(context) },
                    )
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    PrivacyRow(
                        title = "Terms of Service",
                        subtitle = "Your obligations and ours",
                        onClick = { viewModel.openTerms(context) },
                    )
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    PrivacyRow(
                        title = "Contact & grievance",
                        subtitle = "legal@4ais.in · grievance@4ais.in",
                        onClick = { viewModel.openContact(context) },
                    )
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    PrivacyRow(
                        title = "Agent activity",
                        subtitle = "See what the agent has done on your phone",
                        actionLabel = "View",
                        onClick = {
                            viewModel.loadAuditEvents()
                            showActivityLog = true
                        },
                    )
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    PrivacyRow(
                        title = "Clear agent activity log",
                        subtitle = "Wipes the local on-device record of agent actions",
                        actionLabel = "Clear",
                        onClick = { viewModel.clearAgentActivity() },
                    )
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    PrivacyRow(
                        title = "Withdraw consent",
                        subtitle = "Stops optional usage telemetry; we'll re-ask next launch",
                        actionLabel = "Withdraw",
                        onClick = { viewModel.withdrawConsent() },
                    )
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    PrivacyRow(
                        title = "Reset all app data",
                        subtitle = "Deletes API key, settings, agent notes, audit log. Re-enters setup.",
                        actionLabel = "Reset…",
                        destructive = true,
                        onClick = { showResetConfirm = true },
                    )
                }
            }
            if (showActivityLog) {
                val events by viewModel.auditEvents.collectAsStateWithLifecycle()
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showActivityLog = false },
                    title = { Text("Agent activity") },
                    text = {
                        if (events.isEmpty()) {
                            Text(
                                "No actions recorded yet. Once the agent taps, types, or runs a command, it'll show here.",
                                color = TextMuted,
                            )
                        } else {
                            // Newest first.
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(events.reversed()) { ev ->
                                    AgentActivityRow(ev)
                                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showActivityLog = false }) { Text("Close") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.clearAgentActivity()
                            viewModel.loadAuditEvents()
                        }) { Text("Clear", color = Danger) }
                    },
                )
            }
            privacyMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.dismissPrivacyMessage() }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Success,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Dismiss", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showResetConfirm) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("Reset all app data?") },
                    text = {
                        Text(
                            "This permanently deletes your API key, all settings, the agent's app-knowledge " +
                                "notes, the on-device audit log, and any saved consent. The app will return to " +
                                "the setup wizard on next launch. This cannot be undone.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showResetConfirm = false
                            viewModel.resetAppData()
                        }) { Text("Reset", color = Danger) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("About")
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 8.dp) {
                Column {
                    AboutRow("Version", "${com.crsk.openclaw.BuildConfig.VERSION_NAME} (build ${com.crsk.openclaw.BuildConfig.VERSION_CODE})")
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    AboutRow("Engine", "OpenClaw Gateway (local)")
                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                    AboutRow(
                        "LLM Backend",
                        "Bring your own key · ${selectedProvider.displayName}",
                    )
                }
            }
        }
    }
}

private fun openConsole(context: Context, url: String) {
    CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
}

@Composable
private fun AgentActivityRow(ev: com.crsk.openclaw.data.AuditEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ev.summary.ifBlank { ev.verb },
                style = MaterialTheme.typography.bodyMedium,
                color = if (ev.ok) MaterialTheme.colorScheme.onSurface else Danger,
            )
            Text(
                text = buildString {
                    if (ev.targetPackage.isNotBlank()) {
                        append(ev.targetPackage.substringAfterLast('.'))
                        append(" · ")
                    }
                    append(formatRelativeTime(ev.timestampMs))
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        Text(
            text = if (ev.ok) "OK" else "FAIL",
            style = MaterialTheme.typography.labelSmall,
            color = if (ev.ok) Success else Danger,
        )
    }
}

private fun formatRelativeTime(epochMs: Long): String {
    val deltaSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        deltaSec < 60 -> "${deltaSec}s ago"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}

@Composable
private fun PrivacyRow(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) Danger else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) Danger else Cyan,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.OpenInBrowser,
                contentDescription = null,
                tint = TextSecondary,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun ModelRow(model: ProviderModel, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Cyan,
                unselectedColor = TextMuted,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = model.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun ShizukuOptionalCard(
    status: ShizukuStatus,
    installed: Boolean,
    testResult: String?,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
    onReconnect: () -> Unit,
    onRunTest: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenWirelessDebugging: () -> Unit,
    onRefreshInstalled: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshInstalled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
        Column {
            Text(
                text = "Shizuku gives the agent ADB-level shell access — needed for force-stopping " +
                    "apps, typing into WebViews, and running shell commands. Most UI automation works " +
                    "without it via the Accessibility Service above.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))

            if (!installed) {
                // Not installed — show install button
                Text(
                    text = "Not installed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(text = "Install Shizuku", onClick = onInstall)
            } else {
                // Installed — show status and controls
                val (statusColor, statusText) = when (status) {
                    is ShizukuStatus.Ready -> Cyan to "Ready (${status.version})"
                    is ShizukuStatus.Connecting -> Color.Yellow to "Connecting…"
                    is ShizukuStatus.PermissionRequired -> Color.Yellow to "Permission required"
                    is ShizukuStatus.NotRunning -> Danger to "Not running"
                    is ShizukuStatus.NotInstalled -> Danger to "Not detected"
                    is ShizukuStatus.Failed -> Danger to "Failed: ${status.reason}"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))

                when (status) {
                    is ShizukuStatus.NotRunning, is ShizukuStatus.NotInstalled -> {
                        Text(
                            text = "Shizuku needs Wireless Debugging enabled (requires WiFi).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton(text = "Open Wireless Debugging", onClick = onOpenWirelessDebugging)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .clickable(onClick = onOpenShizuku)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Shizuku app", color = Cyan, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is ShizukuStatus.PermissionRequired -> {
                        PrimaryButton(text = "Grant Shizuku permission", onClick = onGrantPermission)
                    }
                    is ShizukuStatus.Ready -> {
                        PrimaryButton(
                            text = "Run test command (id)",
                            onClick = onRunTest,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .clickable(onClick = onOpenShizuku)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Shizuku app", color = Cyan, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is ShizukuStatus.Connecting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Cyan)
                            Spacer(Modifier.width(10.dp))
                            Text("Connecting…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                    is ShizukuStatus.Failed -> {
                        PrimaryButton(text = "Reconnect", onClick = onReconnect)
                    }
                }

                if (!testResult.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = testResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceGlass, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UiAutomationCard(
    status: A11yStatus,
    onOpenSettings: () -> Unit,
) {
    val (statusColor, statusText) = when (status) {
        A11yStatus.CONNECTED -> Cyan to "Connected"
        A11yStatus.DISCONNECTED -> Danger to "Not enabled"
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (status == A11yStatus.CONNECTED)
                    "Accessibility service active. The agent can read UI elements and tap by text."
                else
                    "Enable the accessibility service so the agent can interact with apps by reading screen content and tapping elements by name.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (status == A11yStatus.CONNECTED) "Accessibility settings" else "Enable service",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun AboutRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}
