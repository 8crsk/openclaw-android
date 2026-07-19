package com.crsk.openclaw.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.os.Build
import com.crsk.openclaw.accessibility.A11yStatus
import com.crsk.openclaw.accessibility.AccessibilityServiceHolder
import com.crsk.openclaw.bootstrap.BootstrapManager
import com.crsk.openclaw.bootstrap.BootstrapState
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.data.preferences.ConsentStore
import com.crsk.openclaw.service.GatewayService
import com.crsk.openclaw.termux.TermuxInstaller
import com.crsk.openclaw.util.DeviceChecks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupPhase {
    WELCOME,
    // PRIVACY_CONSENT collects DPDP §6 / GDPR Art. 7 informed consent (AI
    // processing + analytics opt-in + 18+/ToS) BEFORE any data leaves the
    // device. Skipped on re-entry if the user already consented to the current
    // ConsentStore.CURRENT_CONSENT_VERSION; re-shown when the policy bumps.
    PRIVACY_CONSENT,
    // Removed phases (still opt-in via Settings, just not blocking onboarding):
    //   GET_KEY, PASTE_KEY, PICK_MODEL — provider + key + model are configured
    //     from Settings; the wizard doesn't block on them.
    //   INSTALL_SHIZUKU, SHIZUKU_PAIR — Shizuku is optional. The
    //     AccessibilityService covers UI automation; Shizuku is only needed
    //     for shell-only ops (force-stop apps) and can be enabled later
    //     from Settings → Shell access.
    ENABLE_ACCESSIBILITY,
    NODE_SETUP,
    COMPLETE,
}

data class SetupUiState(
    val phase: SetupPhase = SetupPhase.WELCOME,
    val accessibilityEnabled: Boolean = false,
    val needsRestrictedSettings: Boolean = false,
    val setupProgress: Float = 0f,
    val setupStatus: String = "",
    val setupError: String? = null,
    val noInternet: Boolean = false,
    val noWifi: Boolean = false,
    val needsUnknownSources: Boolean = false,
    val needsBatteryOptimization: Boolean = false,
    val notificationPermissionNeeded: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: AppPreferences,
    private val bootstrapManager: BootstrapManager,
    private val termuxInstaller: TermuxInstaller,
    private val deviceChecks: DeviceChecks,
    private val accessibilityHolder: AccessibilityServiceHolder,
    private val consentStore: ConsentStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        refreshInstallStatus()
    }

    // ── Privacy consent ────────────────────────────────────────────────────────
    /** Tracks the user's checkbox state in the PRIVACY_CONSENT phase. We mirror
     *  it here (not in the screen's `remember`) so a config change doesn't reset
     *  partial input mid-consent. */
    private val _consentDraft = MutableStateFlow(ConsentDraft())
    val consentDraft: StateFlow<ConsentDraft> = _consentDraft.asStateFlow()

    fun setAiProcessingConsent(value: Boolean) {
        _consentDraft.value = _consentDraft.value.copy(aiProcessing = value)
    }
    fun setAnalyticsConsent(value: Boolean) {
        _consentDraft.value = _consentDraft.value.copy(analytics = value)
    }
    fun setTermsAccepted(value: Boolean) {
        _consentDraft.value = _consentDraft.value.copy(termsAccepted = value)
    }

    /** Persist consent + move on. Required boxes (aiProcessing + termsAccepted)
     *  are enforced in the UI; analytics is optional. */
    fun grantConsentAndContinue() {
        val draft = _consentDraft.value
        if (!draft.aiProcessing || !draft.termsAccepted) return
        viewModelScope.launch {
            consentStore.grant(
                aiProcessingConsent = draft.aiProcessing,
                analyticsConsent = draft.analytics,
                termsAccepted = draft.termsAccepted,
                // Country can be detected later via a separate signal (locale,
                // IP geo). Empty for now — the consent record still proves *when*
                // consent was granted, which is the audit-critical part.
                grantedFromCountry = "",
            )
            advanceToAccessibility()
        }
    }

    fun refreshInstallStatus() {
        _state.value = _state.value.copy(
            needsUnknownSources = !termuxInstaller.canInstallPackages(),
            needsBatteryOptimization = deviceChecks.isBatteryOptimized(),
            noWifi = !deviceChecks.isOnWifi(),
            noInternet = !deviceChecks.isOnline(),
        )
    }

    /** Welcome → consent (if needed) → accessibility. We always check consent
     *  before any data could leave the device. If the user already consented
     *  to the current policy version, the consent phase is skipped. */
    fun proceedFromWelcome() {
        viewModelScope.launch {
            val needsConsent = consentStore.needsReconsent.first()
            if (needsConsent) {
                _state.value = _state.value.copy(phase = SetupPhase.PRIVACY_CONSENT)
            } else {
                advanceToAccessibility()
            }
        }
    }

    fun openUnknownSourcesSettings() {
        runCatching { termuxInstaller.openUnknownSourcesSettings() }
    }

    /* ---------- Accessibility service setup ---------- */

    private var a11yPollerStarted = false

    private fun advanceToAccessibility() {
        val alreadyEnabled = accessibilityHolder.isConnected()
        _state.value = _state.value.copy(
            phase = SetupPhase.ENABLE_ACCESSIBILITY,
            accessibilityEnabled = alreadyEnabled,
            needsRestrictedSettings = Build.VERSION.SDK_INT >= 33,
        )
        if (alreadyEnabled) {
            advanceToNodeSetup()
            return
        }
        startA11yPoller()
    }

    private fun startA11yPoller() {
        if (a11yPollerStarted) return
        a11yPollerStarted = true
        viewModelScope.launch {
            accessibilityHolder.status.collect { status ->
                _state.value = _state.value.copy(accessibilityEnabled = status == A11yStatus.CONNECTED)
                if (status == A11yStatus.CONNECTED && _state.value.phase == SetupPhase.ENABLE_ACCESSIBILITY) {
                    delay(400)
                    advanceToNodeSetup()
                }
            }
        }
    }

    fun refreshAccessibilityStatus() {
        _state.value = _state.value.copy(accessibilityEnabled = accessibilityHolder.isConnected())
    }

    fun openAccessibilitySettings() {
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openAppInfoSettings() {
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openWifiSettings() {
        runCatching { deviceChecks.openWifiSettings() }
    }

    // Shizuku launcher helpers (openShizukuApp, openShizukuStorePage,
    // openWirelessDebugging, openDeveloperOptions) used to live here for the
    // setup wizard's Shizuku pairing phase. With Shizuku removed from the
    // wizard they're unreferenced; the same helpers exist in SettingsViewModel
    // for users who opt into Shizuku later for shell access.

    /* ---------- Node setup (gateway) ---------- */

    private fun advanceToNodeSetup() {
        _state.value = _state.value.copy(phase = SetupPhase.NODE_SETUP, setupError = null)
        startSetup()
    }

    fun startSetup() {
        if (!deviceChecks.isOnline()) {
            _state.value = _state.value.copy(
                setupError = "No internet connection. Connect to WiFi or mobile data, then tap Retry.",
                noInternet = true,
            )
            return
        }
        _state.value = _state.value.copy(setupError = null, noInternet = false)

        viewModelScope.launch {
            val port = preferences.gatewayPort.first()

            launch { bootstrapManager.bootstrap(port) }

            bootstrapManager.state.collect { bs ->
                when (bs) {
                    is BootstrapState.NotStarted -> {}
                    is BootstrapState.Downloading -> _state.value = _state.value.copy(
                        setupProgress = bs.progress,
                        setupStatus = "Preparing setup files…",
                    )
                    is BootstrapState.Extracting -> _state.value = _state.value.copy(
                        setupProgress = bs.progress,
                        setupStatus = "Extracting npm…",
                    )
                    is BootstrapState.FixingPaths -> {}
                    is BootstrapState.Running -> _state.value = _state.value.copy(
                        setupProgress = bs.run {
                            when {
                                step.contains("OpenClaw", true)  -> 0.40f
                                step.contains("config", true)    -> 0.80f
                                step.contains("MCP", true)       -> 0.87f
                                step.contains("gateway", true)   -> 0.92f
                                else -> _state.value.setupProgress
                            }
                        },
                        setupStatus = "${bs.step}\n${bs.line.take(100)}",
                    )
                    is BootstrapState.Complete -> {
                        preferences.setBootstrapComplete(true)
                        finishSetup()
                        return@collect
                    }
                    is BootstrapState.Failed -> {
                        _state.value = _state.value.copy(setupError = bs.error)
                        return@collect
                    }
                }
            }
        }
    }

    private fun finishSetup() {
        viewModelScope.launch {
            GatewayService.start(appContext)
            if (deviceChecks.isBatteryOptimized()) {
                runCatching { deviceChecks.openBatteryOptimizationSettings() }
            }
            preferences.setSetupComplete(true)
            _state.value = _state.value.copy(setupProgress = 1f, setupStatus = "Gateway online.")
            delay(800)
            _state.value = _state.value.copy(phase = SetupPhase.COMPLETE)
        }
    }

    fun retrySetup() {
        _state.value = _state.value.copy(setupError = null)
        startSetup()
    }

    /* ---------- Device prerequisites ---------- */

    fun requestBatteryOptimizationExemption() {
        runCatching { deviceChecks.openBatteryOptimizationSettings() }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(notificationPermissionNeeded = !granted)
    }
}

/** Local-only checkbox state for the PRIVACY_CONSENT phase. */
data class ConsentDraft(
    val aiProcessing: Boolean = false,
    val analytics: Boolean = false,
    val termsAccepted: Boolean = false,
)
