package com.crsk.openclaw.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsk.openclaw.accessibility.A11yStatus
import com.crsk.openclaw.accessibility.AccessibilityServiceHolder
import com.crsk.openclaw.accessibility.AppDocsRepository
import com.crsk.openclaw.data.AuditEvent
import com.crsk.openclaw.bootstrap.BootstrapManager
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.data.network.KeyValidationResult
import com.crsk.openclaw.data.network.ProviderKeyValidator
import com.crsk.openclaw.data.providers.AiProvider
import com.crsk.openclaw.data.providers.ProviderCatalog
import com.crsk.openclaw.data.AgentAuditLog
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.data.preferences.ConsentStore
import com.crsk.openclaw.data.preferences.EncryptedKeyStore
import com.crsk.openclaw.service.GatewayHealthMonitor
import com.crsk.openclaw.service.GatewayService
import com.crsk.openclaw.service.HealthState
import com.crsk.openclaw.shizuku.ShizukuManager
import com.crsk.openclaw.shizuku.ShizukuStatus
import com.crsk.openclaw.termux.TermuxInstaller
import com.crsk.openclaw.util.DeviceChecks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KeyEditState(
    val draft: String = "",
    val isValidating: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: AppPreferences,
    private val keyValidator: ProviderKeyValidator,
    private val gatewayProcess: GatewayProcess,
    private val bootstrapManager: BootstrapManager,
    private val shizukuManager: ShizukuManager,
    private val healthMonitor: GatewayHealthMonitor,
    private val deviceChecks: DeviceChecks,
    private val accessibilityHolder: AccessibilityServiceHolder,
    private val termuxInstaller: TermuxInstaller,
    private val appDocs: AppDocsRepository,
    private val consentStore: ConsentStore,
    private val encryptedKeyStore: EncryptedKeyStore,
    private val auditLog: AgentAuditLog,
) : ViewModel() {

    // ── Privacy section ─────────────────────────────────────────────────────────
    private val _privacyMessage = MutableStateFlow<String?>(null)
    val privacyMessage: StateFlow<String?> = _privacyMessage.asStateFlow()

    fun openPrivacyPolicy(context: Context) {
        openUrl(context, "https://4ais.in/privacy")
    }

    fun openTerms(context: Context) {
        openUrl(context, "https://4ais.in/terms")
    }

    fun openContact(context: Context) {
        openUrl(context, "https://4ais.in/contact")
    }

    private fun openUrl(context: Context, url: String) {
        runCatching {
            androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                .launchUrl(context, android.net.Uri.parse(url))
        }
    }

    /** Withdraw analytics + AI-processing consent. The user can re-grant via the
     *  setup wizard re-prompt; until then `consentStore.analyticsConsentGranted`
     *  emits false and downstream usage telemetry is suppressed. */
    fun withdrawConsent() {
        viewModelScope.launch {
            consentStore.withdrawAll()
            _privacyMessage.value = "Consent withdrawn. Restart the app to re-grant."
        }
    }

    /** Clear the local agent-activity audit log (Settings → Agent activity). */
    fun clearAgentActivity() {
        viewModelScope.launch {
            auditLog.clear()
            _privacyMessage.value = "Agent activity log cleared."
        }
    }

    /** Hard reset. Wipes EncryptedSharedPreferences (API key, refresh tokens),
     *  the entire DataStore, all per-app notes, the audit log, the OpenClaw
     *  home directory, and consent. Re-enters the setup wizard on next launch.
     *  DPDP §13 / GDPR Art. 17 erasure for local data. */
    fun resetAppData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { encryptedKeyStore.clearAll() }
            runCatching { appDocs.clearAll() }
            runCatching { auditLog.clear() }
            runCatching { consentStore.withdrawAll() }
            runCatching { preferences.resetLifetimeTokens() }
            runCatching {
                java.io.File(appContext.filesDir, "home/.openclaw").deleteRecursively()
            }
            runCatching {
                preferences.setSetupComplete(false)
                preferences.setBootstrapComplete(false)
            }
            withContext(Dispatchers.Main) {
                _privacyMessage.value = "App data reset. Restart the app to re-enter setup."
            }
        }
    }

    fun dismissPrivacyMessage() {
        _privacyMessage.value = null
    }

    // Agent activity viewer state ────────────────────────────────────────────
    private val _auditEvents = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<AuditEvent>> = _auditEvents.asStateFlow()

    fun loadAuditEvents() {
        viewModelScope.launch {
            _auditEvents.value = withContext(Dispatchers.IO) { auditLog.recent(200) }
        }
    }
    // ────────────────────────────────────────────────────────────────────────────

    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.status
    val a11yStatus: StateFlow<A11yStatus> = accessibilityHolder.status

    private val _shizukuInstalled = MutableStateFlow(termuxInstaller.isInstalled(TermuxInstaller.Packages.SHIZUKU))
    val shizukuInstalled: StateFlow<Boolean> = _shizukuInstalled.asStateFlow()

    private val _shizukuTestResult = MutableStateFlow<String?>(null)
    val shizukuTestResult: StateFlow<String?> = _shizukuTestResult.asStateFlow()

    /** True when Android battery optimization is still throttling this app. */
    private val _batteryOptimized = MutableStateFlow(deviceChecks.isBatteryOptimized())
    val batteryOptimized: StateFlow<Boolean> = _batteryOptimized.asStateFlow()

    init {
        shizukuManager.init()
    }

    /** Re-read battery-optimization state (e.g. when the user returns from settings). */
    fun refreshBatteryStatus() {
        _batteryOptimized.value = deviceChecks.isBatteryOptimized()
    }

    /** Opens the system dialog to exempt this app from battery optimization. */
    fun fixBatteryOptimization() {
        deviceChecks.openBatteryOptimizationSettings()
    }

    fun reconnectShizuku() {
        shizukuManager.shutdown()
        shizukuManager.init()
    }

    /** Explicit "Grant Shizuku permission" action — shows the Shizuku permission dialog. */
    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun openShizukuApp(context: Context) {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
            context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun installShizuku() {
        if (!termuxInstaller.canInstallPackages()) {
            termuxInstaller.openUnknownSourcesSettings()
            return
        }
        // Shizuku's APK is not redistributed with the open-source build. If the
        // bundled asset is absent (the normal case), send the user to the
        // official download page instead.
        runCatching { termuxInstaller.launchInstall(TermuxInstaller.Apks.SHIZUKU) }
            .onFailure { openUrl(appContext, "https://shizuku.rikka.app/download/") }
    }

    fun refreshShizukuInstalled() {
        _shizukuInstalled.value = termuxInstaller.isInstalled(TermuxInstaller.Packages.SHIZUKU)
    }

    fun openWirelessDebugging() {
        runCatching {
            appContext.startActivity(
                Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun runShizukuTest() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { shizukuManager.exec(listOf("id"), null, 5_000) }
            }
            _shizukuTestResult.value = result.fold(
                onSuccess = { (exit, out, err) ->
                    val tail = if (err.isBlank()) out.trim() else "$out\nERR: $err".trim()
                    "exit=$exit  $tail".take(400)
                },
                onFailure = { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            )
        }
    }

    /** The provider whose key/model the user is editing — mirrors preferences. */
    val selectedProvider: StateFlow<AiProvider> = preferences.apiProvider
        .let { src ->
            MutableStateFlow(ProviderCatalog.byId(src.value) ?: ProviderCatalog.default).also { out ->
                viewModelScope.launch {
                    src.collect { out.value = ProviderCatalog.byId(it) ?: ProviderCatalog.default }
                }
            }
        }

    /** The saved key for the selected provider (used to prefill the field). */
    private val _savedKey = MutableStateFlow(preferences.getProviderKey(selectedProvider.value.id))
    val savedKey: StateFlow<String> = _savedKey.asStateFlow()

    /** Switch provider: persist the choice, snap the model to that provider's
     *  default when the stored one doesn't belong to it, and reload the key field. */
    fun setProvider(providerId: String) {
        val provider = ProviderCatalog.byId(providerId) ?: return
        viewModelScope.launch {
            preferences.setApiProvider(provider.id)
            if (provider.models.none { it.id == preferences.selectedModel.value }) {
                preferences.setSelectedModel(provider.defaultModel.id)
            }
            _savedKey.value = preferences.getProviderKey(provider.id)
            _keyEdit.value = KeyEditState(draft = _savedKey.value)
        }
    }

    val selectedModel = preferences.selectedModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProviderCatalog.default.defaultModel.id)

    // ── Composio integration key (optional, powers Settings → Connections) ──
    private val _composioKeySaved = MutableStateFlow(encryptedKeyStore.getComposioKey().isNotBlank())
    val composioKeySaved: StateFlow<Boolean> = _composioKeySaved.asStateFlow()

    fun saveComposioKey(key: String) {
        encryptedKeyStore.setComposioKey(key.trim())
        _composioKeySaved.value = key.isNotBlank()
        _privacyMessage.value = if (key.isBlank()) "Composio key removed." else "Composio key saved."
    }

    val gatewayStatus: StateFlow<GatewayStatus> = gatewayProcess.status
    val mcpReady: StateFlow<Boolean> = gatewayProcess.mcpReady
    val healthState: StateFlow<HealthState> = healthMonitor.health

    val autoStartOnBoot = preferences.autoStartOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoApproveAgentActions = preferences.autoApproveAgentActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val overlayEnabled: StateFlow<Boolean> = preferences.overlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setOverlayEnabled(enabled) }
    }

    val agentVisionEnabled: StateFlow<Boolean> = preferences.agentVisionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAgentVisionEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setAgentVisionEnabled(enabled) }
    }

    val reflectionEnabled: StateFlow<Boolean> = preferences.reflectionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setReflectionEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setReflectionEnabled(enabled) }
    }

    /** Per-app notes the agent has saved (package → note count). Refreshes on demand. */
    private val _appDocsSummary = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val appDocsSummary: StateFlow<List<Pair<String, Int>>> = _appDocsSummary.asStateFlow()

    fun refreshAppDocs() {
        viewModelScope.launch {
            _appDocsSummary.value = withContext(Dispatchers.IO) { appDocs.summarize() }
        }
    }

    fun clearAllAppDocs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appDocs.clearAll() }
            _appDocsSummary.value = emptyList()
        }
    }

    private val _keyEdit = MutableStateFlow(KeyEditState())
    val keyEdit: StateFlow<KeyEditState> = _keyEdit.asStateFlow()

    fun keyDraftChanged(text: String) {
        _keyEdit.value = _keyEdit.value.copy(draft = text.trim(), message = null, isError = false)
    }

    fun saveKey() {
        val draft = _keyEdit.value.draft
        val provider = selectedProvider.value
        if (draft.isBlank()) {
            _keyEdit.value = _keyEdit.value.copy(message = "Paste a key first.", isError = true)
            return
        }
        _keyEdit.value = _keyEdit.value.copy(isValidating = true, message = null, isError = false)
        viewModelScope.launch {
            _keyEdit.value = when (val result = keyValidator.validate(provider, draft)) {
                KeyValidationResult.Valid -> {
                    preferences.setProviderKey(provider.id, draft)
                    _savedKey.value = draft
                    // The gateway reads keys at start — restart so openclaw picks
                    // up the new provider without waiting for the next app launch.
                    viewModelScope.launch(Dispatchers.IO) { runCatching { gatewayProcess.restart() } }
                    KeyEditState(draft = draft, message = "Key updated. Restarting gateway…", isError = false)
                }
                KeyValidationResult.InvalidKey ->
                    _keyEdit.value.copy(
                        isValidating = false,
                        message = "That key didn't work.",
                        isError = true,
                    )
                is KeyValidationResult.NetworkError ->
                    _keyEdit.value.copy(
                        isValidating = false,
                        message = "Couldn't reach ${provider.displayName}. Check your connection.",
                        isError = true,
                    )
                is KeyValidationResult.UnexpectedStatus ->
                    _keyEdit.value.copy(
                        isValidating = false,
                        message = "${provider.displayName} returned HTTP ${result.code}.",
                        isError = true,
                    )
            }
        }
    }

    fun setSelectedModel(modelId: String) {
        viewModelScope.launch { preferences.setSelectedModel(modelId) }
    }

    fun startGateway() {
        if (!bootstrapManager.isBootstrapped()) {
            viewModelScope.launch {
                preferences.setSetupComplete(false)
                preferences.setBootstrapComplete(false)
            }
            return
        }
        GatewayService.start(appContext)
    }

    fun stopGateway() {
        GatewayService.stop(appContext)
    }

    fun toggleAutoStart() {
        viewModelScope.launch {
            val current = autoStartOnBoot.value
            preferences.setAutoStartOnBoot(!current)
        }
    }

    fun toggleAutoApprove() {
        viewModelScope.launch {
            preferences.setAutoApproveAgentActions(!autoApproveAgentActions.value)
        }
    }
}
