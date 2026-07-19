package com.crsk.openclaw.ui.setup

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsk.openclaw.R
import com.crsk.openclaw.shizuku.ShizukuStatus
import com.crsk.openclaw.ui.components.AmbientGlowBackdrop
import com.crsk.openclaw.ui.components.GlassCard
import com.crsk.openclaw.ui.components.PrimaryButton
import com.crsk.openclaw.ui.theme.AccentBlue
import com.crsk.openclaw.ui.theme.Danger
import com.crsk.openclaw.ui.theme.Surface
import com.crsk.openclaw.ui.theme.TextMuted
import com.crsk.openclaw.ui.theme.TextSecondary


@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == SetupPhase.COMPLETE) onSetupComplete()
    }

    AmbientGlowBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            when (state.phase) {
                SetupPhase.WELCOME -> WelcomePhase(onStart = viewModel::proceedFromWelcome)
                SetupPhase.PRIVACY_CONSENT -> {
                    val consentDraft by viewModel.consentDraft.collectAsStateWithLifecycle()
                    PrivacyConsentPhase(
                        aiProcessing = consentDraft.aiProcessing,
                        analytics = consentDraft.analytics,
                        termsAccepted = consentDraft.termsAccepted,
                        onAiProcessingChange = viewModel::setAiProcessingConsent,
                        onAnalyticsChange = viewModel::setAnalyticsConsent,
                        onTermsChange = viewModel::setTermsAccepted,
                        onContinue = viewModel::grantConsentAndContinue,
                        onOpenPrivacy = {
                            runCatching {
                                CustomTabsIntent.Builder().build()
                                    .launchUrl(context, "https://4ais.in/privacy".toUri())
                            }
                        },
                        onOpenTerms = {
                            runCatching {
                                CustomTabsIntent.Builder().build()
                                    .launchUrl(context, "https://4ais.in/terms".toUri())
                            }
                        },
                    )
                }
                // Provider keys, model choice, and optional Shizuku are all
                // configured later from Settings — the wizard stays minimal:
                // consent → accessibility → node bootstrap.
                SetupPhase.ENABLE_ACCESSIBILITY -> EnableAccessibilityPhase(
                    accessibilityEnabled = state.accessibilityEnabled,
                    needsRestrictedSettings = state.needsRestrictedSettings,
                    onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
                    onOpenAppInfo = viewModel::openAppInfoSettings,
                    onRefresh = viewModel::refreshAccessibilityStatus,
                )
                SetupPhase.NODE_SETUP -> NodeSetupPhase(
                    progress = state.setupProgress,
                    status = state.setupStatus,
                    error = state.setupError,
                    onRetry = viewModel::retrySetup,
                    needsBatteryOptimization = state.needsBatteryOptimization,
                    onDisableBatteryOptimization = viewModel::requestBatteryOptimizationExemption,
                )
                SetupPhase.COMPLETE -> {}
            }
        }
    }
}

@Composable
private fun ColumnScope.PrivacyConsentPhase(
    aiProcessing: Boolean,
    analytics: Boolean,
    termsAccepted: Boolean,
    onAiProcessingChange: (Boolean) -> Unit,
    onAnalyticsChange: (Boolean) -> Unit,
    onTermsChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Privacy & permissions",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "4AIs runs on your phone. Before we start, please confirm what you're OK sending out.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
    )
    Spacer(Modifier.height(20.dp))
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 16.dp) {
        Column {
            ConsentCheckboxRow(
                checked = aiProcessing,
                onCheckedChange = onAiProcessingChange,
                title = "Send my prompts and screen content to the AI provider",
                subtitle = "Required. The agent can't answer without this. " +
                    "Everything goes directly to the provider you pick (NVIDIA, OpenAI, " +
                    "Anthropic, or Google) using your own API key — there is no middleman server.",
            )
            Spacer(Modifier.height(12.dp))
            ConsentCheckboxRow(
                checked = termsAccepted,
                onCheckedChange = onTermsChange,
                title = "I'm 18+ and accept the Terms and Privacy Policy",
                subtitle = null,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(start = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Privacy Policy",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBlue,
                    modifier = Modifier.clickable(onClick = onOpenPrivacy),
                )
                Text(
                    text = "Terms",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBlue,
                    modifier = Modifier.clickable(onClick = onOpenTerms),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = "You can withdraw consent any time from Settings → Privacy & data.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )
    Spacer(Modifier.weight(1f))
    val canContinue = aiProcessing && termsAccepted
    PrimaryButton(
        text = if (canContinue) "Continue" else "Tick the required boxes to continue",
        icon = if (canContinue) Icons.AutoMirrored.Filled.ArrowForward else null,
        onClick = { if (canContinue) onContinue() },
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ConsentCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = AccentBlue,
                uncheckedColor = TextSecondary,
                checkmarkColor = Color.Black,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomePhase(onStart: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(AccentBlue.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(32.dp),
        )
    }
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.setup_welcome_headline),
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.setup_welcome_subhead),
        style = MaterialTheme.typography.headlineSmall,
        color = TextSecondary,
    )
    Spacer(Modifier.height(40.dp))
    PrimaryButton(
        text = stringResource(R.string.setup_welcome_cta),
        icon = Icons.AutoMirrored.Filled.ArrowForward,
        onClick = onStart,
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun NumberedStep(n: Int, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = n.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = AccentBlue,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ColumnScope.NodeSetupPhase(
    progress: Float,
    status: String,
    error: String?,
    onRetry: () -> Unit,
    needsBatteryOptimization: Boolean = false,
    onDisableBatteryOptimization: () -> Unit = {},
) {
    if (needsBatteryOptimization) {
        Spacer(Modifier.height(8.dp))
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 18.dp,
            contentPadding = 16.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFA726),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Disable battery optimization",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Required so Android doesn't kill the gateway while your screen is off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "Allow Unrestricted",
                icon = Icons.Filled.Settings,
                onClick = onDisableBatteryOptimization,
            )
        }
    }

    Spacer(Modifier.weight(1f))
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(AccentBlue.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CloudDownload,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(32.dp),
        )
    }
    Spacer(Modifier.height(28.dp))
    Text(
        text = "Setting up 4AIs",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Running once on this phone — about 2–5 minutes.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
    )
    Spacer(Modifier.height(28.dp))

    if (error != null) {
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 20.dp) {
            Column {
                Text(text = error, style = MaterialTheme.typography.bodyMedium, color = Danger)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(text = "Retry", icon = Icons.Filled.Refresh, onClick = onRetry)
            }
        }
    } else {
        // The status is "step\nrawlogline" during the Running phase, or a single clean
        // line otherwise. Narrate the step prominently; let the raw log whisper underneath.
        val primary = status.substringBefore('\n').trim().ifBlank { "Preparing…" }
        val detail = status.substringAfter('\n', "").trim()
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            label = "setupProgress",
        )
        Text(
            text = primary,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Surface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AccentBlue),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = AccentBlue,
            )
        }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.EnableAccessibilityPhase(
    accessibilityEnabled: Boolean,
    needsRestrictedSettings: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRefresh: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Spacer(Modifier.height(16.dp))
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(AccentBlue.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (accessibilityEnabled) Icons.Filled.CheckCircle else Icons.Filled.Security,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(32.dp),
        )
    }
    Spacer(Modifier.height(24.dp))
    Text(
        text = "Enable UI automation",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "4AIs needs the Accessibility Service to read your screen and tap on your behalf. This is how the agent opens apps, types messages, and navigates for you.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
    )
    Spacer(Modifier.height(20.dp))

    if (needsRestrictedSettings && !accessibilityEnabled) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 18.dp,
            contentPadding = 16.dp,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFA726),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Allow restricted settings first",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                NumberedStep(1, "Tap Open App Info below.")
                NumberedStep(2, "Tap the ⋮ menu (top right).")
                NumberedStep(3, "Tap \"Allow restricted settings\".")
                NumberedStep(4, "Come back here.")
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "Open App Info",
                    icon = Icons.Filled.Settings,
                    onClick = onOpenAppInfo,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 20.dp) {
        Column {
            if (accessibilityEnabled) {
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentBlue,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "UI automation is ready. Continuing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            } else {
                NumberedStep(1, "Tap Open Accessibility Settings below.")
                NumberedStep(2, "Find \"4AIs UI Automation\" in the list.")
                NumberedStep(3, "Toggle it ON and confirm.")
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "Open Accessibility Settings",
                    icon = Icons.Filled.Settings,
                    onClick = onOpenAccessibilitySettings,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = AccentBlue,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Waiting for you to enable the service…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
}
