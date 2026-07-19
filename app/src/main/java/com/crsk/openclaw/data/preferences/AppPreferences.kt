package com.crsk.openclaw.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crsk.openclaw.data.providers.ProviderCatalog
import kotlinx.coroutines.flow.combine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "openclaw_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedKeyStore: EncryptedKeyStore,
) {
    private val setupCompleteKey = booleanPreferencesKey("setup_complete")
    private val apiProviderKey = stringPreferencesKey("api_provider")
    private val selectedModelKey = stringPreferencesKey("selected_model")
    private val bootstrapCompleteKey = booleanPreferencesKey("bootstrap_complete")
    private val gatewayPortKey = intPreferencesKey("gateway_port")
    private val autoStartOnBootKey = booleanPreferencesKey("auto_start_on_boot")
    private val autoApproveAgentActionsKey = booleanPreferencesKey("auto_approve_agent_actions")
    private val overlayEnabledKey = booleanPreferencesKey("overlay_enabled")
    private val agentVisionEnabledKey = booleanPreferencesKey("agent_vision_enabled")
    private val overlayBubbleXKey = intPreferencesKey("overlay_bubble_x")
    private val overlayBubbleYKey = intPreferencesKey("overlay_bubble_y")
    private val reflectionEnabledKey = booleanPreferencesKey("reflection_enabled")
    private val lifetimeInputTokensKey = longPreferencesKey("lifetime_input_tokens")
    private val lifetimeOutputTokensKey = longPreferencesKey("lifetime_output_tokens")

    // Single shared collector scope for hot preferences. Eager collection means
    // `.first()` on these StateFlows is a memory read, not a DataStore disk hit —
    // critical for tight loops (e.g. health probes, per-turn chat startup) where
    // re-reading the same key every iteration was 1 file I/O each.
    private val prefsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isSetupComplete: Flow<Boolean> = context.dataStore.data.map { it[setupCompleteKey] ?: false }

    // Hot prefs — exposed as eagerly-collected StateFlow so .first() and .value are O(1).
    /** Selected provider id from ProviderCatalog ("nim", "oai", "ant", "gem"). */
    val apiProvider: StateFlow<String> = context.dataStore.data
        .map { it[apiProviderKey] ?: ProviderCatalog.default.id }
        .stateIn(prefsScope, SharingStarted.Eagerly, ProviderCatalog.default.id)

    /** Bumped on every provider-key write so key-derived flows re-evaluate. */
    private val keysVersion = MutableStateFlow(0)

    /** True when the currently selected provider has a saved API key. */
    val selectedProviderHasKey: StateFlow<Boolean> =
        combine(apiProvider, keysVersion) { providerId, _ ->
            encryptedKeyStore.getProviderKey(providerId).isNotBlank()
        }.stateIn(prefsScope, SharingStarted.Eagerly, false)

    fun getProviderKey(providerId: String): String = encryptedKeyStore.getProviderKey(providerId)

    fun setProviderKey(providerId: String, key: String) {
        encryptedKeyStore.setProviderKey(providerId, key)
        keysVersion.value += 1
    }

    /** Bare model id (as sent to the provider API) for the selected provider. */
    val selectedModel: StateFlow<String> = context.dataStore.data
        .map { it[selectedModelKey] ?: ProviderCatalog.default.defaultModel.id }
        .stateIn(prefsScope, SharingStarted.Eagerly, ProviderCatalog.default.defaultModel.id)
    val gatewayPort: StateFlow<Int> = context.dataStore.data
        .map { it[gatewayPortKey] ?: 3000 }
        .stateIn(prefsScope, SharingStarted.Eagerly, 3000)
    val reflectionEnabled: StateFlow<Boolean> = context.dataStore.data
        .map { it[reflectionEnabledKey] ?: false }
        .stateIn(prefsScope, SharingStarted.Eagerly, false)

    // Hot pref — read O(1) per agent action in UiRoutes to gate the vision overlay.
    val agentVisionEnabled: StateFlow<Boolean> = context.dataStore.data
        .map { it[agentVisionEnabledKey] ?: false }
        .stateIn(prefsScope, SharingStarted.Eagerly, false)

    // Cold / UI-only prefs stay as Flow — they're collected only by Compose surfaces
    // with WhileSubscribed semantics, so eager collection would just waste memory.
    val isBootstrapComplete: Flow<Boolean> = context.dataStore.data.map { it[bootstrapCompleteKey] ?: false }
    val autoStartOnBoot: Flow<Boolean> = context.dataStore.data.map { it[autoStartOnBootKey] ?: false }
    // Default OFF. Earlier versions defaulted ON, which meant every fresh install
    // shipped with the agent able to tap, type and exec without surfacing a single
    // confirmation — indefensible legally (no informed per-action consent) and
    // operationally (no audit trail of user intent). Users can opt back in from
    // Settings, but the load-bearing safe default is to ask.
    val autoApproveAgentActions: Flow<Boolean> = context.dataStore.data.map { it[autoApproveAgentActionsKey] ?: false }
    val overlayEnabled: Flow<Boolean> = context.dataStore.data.map { it[overlayEnabledKey] ?: false }
    val overlayBubbleX: Flow<Int> = context.dataStore.data.map { it[overlayBubbleXKey] ?: -1 }
    val overlayBubbleY: Flow<Int> = context.dataStore.data.map { it[overlayBubbleYKey] ?: -1 }
    val lifetimeInputTokens: Flow<Long> = context.dataStore.data.map { it[lifetimeInputTokensKey] ?: 0L }
    val lifetimeOutputTokens: Flow<Long> = context.dataStore.data.map { it[lifetimeOutputTokensKey] ?: 0L }

    suspend fun setSetupComplete(complete: Boolean) {
        context.dataStore.edit { it[setupCompleteKey] = complete }
    }

    suspend fun setApiProvider(provider: String) {
        context.dataStore.edit { it[apiProviderKey] = provider }
    }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { it[selectedModelKey] = model }
    }

    suspend fun setBootstrapComplete(complete: Boolean) {
        context.dataStore.edit { it[bootstrapCompleteKey] = complete }
    }

    suspend fun setGatewayPort(port: Int) {
        context.dataStore.edit { it[gatewayPortKey] = port }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[autoStartOnBootKey] = enabled }
    }

    suspend fun setAutoApproveAgentActions(enabled: Boolean) {
        context.dataStore.edit { it[autoApproveAgentActionsKey] = enabled }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[overlayEnabledKey] = enabled }
    }

    suspend fun setAgentVisionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[agentVisionEnabledKey] = enabled }
    }

    suspend fun setOverlayBubblePosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[overlayBubbleXKey] = x
            it[overlayBubbleYKey] = y
        }
    }

    suspend fun setReflectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[reflectionEnabledKey] = enabled }
    }

    suspend fun addTokenUsage(inputTokens: Long, outputTokens: Long) {
        context.dataStore.edit {
            it[lifetimeInputTokensKey] = (it[lifetimeInputTokensKey] ?: 0L) + inputTokens
            it[lifetimeOutputTokensKey] = (it[lifetimeOutputTokensKey] ?: 0L) + outputTokens
        }
    }

    suspend fun resetLifetimeTokens() {
        context.dataStore.edit {
            it[lifetimeInputTokensKey] = 0L
            it[lifetimeOutputTokensKey] = 0L
        }
    }
}
