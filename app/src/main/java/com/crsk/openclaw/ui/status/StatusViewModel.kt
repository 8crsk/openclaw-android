package com.crsk.openclaw.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsk.openclaw.accessibility.A11yStatus
import com.crsk.openclaw.accessibility.AccessibilityServiceHolder
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.data.composio.ComposioRepository
import com.crsk.openclaw.data.composio.Connection
import com.crsk.openclaw.data.composio.ConnectionStatus
import com.crsk.openclaw.data.composio.Toolkit
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.service.GatewayService
import com.crsk.openclaw.shizuku.ShizukuManager
import com.crsk.openclaw.shizuku.ShizukuStatus
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which on-device capabilities are wired up. Each is shown as a logo bubble. */
data class ConnectionsUi(
    val gateway: Boolean = false,
    val model: Boolean = false,
    val uiAutomation: Boolean = false,
    val shell: Boolean = false,
    val plugins: Boolean = false,
)

/** A toolkit row on the Status page — combines the catalog entry with whether this device
 *  is currently connected to it. */
data class ToolkitChip(
    val toolkit: Toolkit,
    val isConnected: Boolean,
    val isPending: Boolean,
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    gatewayProcess: GatewayProcess,
    accessibilityHolder: AccessibilityServiceHolder,
    shizukuManager: ShizukuManager,
    preferences: AppPreferences,
    private val composio: ComposioRepository,
) : ViewModel() {

    val connections: StateFlow<ConnectionsUi> = combine(
        gatewayProcess.status,
        gatewayProcess.mcpReady,
        accessibilityHolder.status,
        shizukuManager.status,
        preferences.selectedProviderHasKey,
    ) { gateway, mcpReady, a11y, shizuku, hasKey ->
        ConnectionsUi(
            gateway = gateway is GatewayStatus.Running,
            model = hasKey,
            uiAutomation = a11y == A11yStatus.CONNECTED,
            shell = shizuku is ShizukuStatus.Ready,
            plugins = mcpReady,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUi())

    /** Curated short list of popular integrations to show inline on Status. Composio
     *  exposes hundreds, but we don't want to render 300 chips here. The full list is
     *  one tap away via the "Browse all" tile. */
    private val featuredSlugs = listOf(
        "gmail", "googlecalendar", "googledrive", "notion", "slack",
        "github", "linear", "discord", "twitter", "linkedin", "spotify", "youtube",
    )

    val featuredToolkits: StateFlow<List<ToolkitChip>> = combine(
        composio.catalog,
        composio.connections,
    ) { catalog, connections ->
        // Slugs come from Composio in mixed case (catalog: lowercase, connections: uppercase
        // pre-fix). Lowercase everything at comparison time so the match is bulletproof
        // regardless of what casing arrives.
        val active = connections.filter { it.status == ConnectionStatus.ACTIVE }
            .map { it.toolkitSlug.lowercase() }.toSet()
        val pending = connections.filter { it.status == ConnectionStatus.INITIATED }
            .map { it.toolkitSlug.lowercase() }.toSet()
        val bySlug = catalog.associateBy { it.slug.lowercase() }
        featuredSlugs.mapNotNull { slug ->
            val tk = bySlug[slug] ?: return@mapNotNull null
            val key = tk.slug.lowercase()
            ToolkitChip(
                toolkit = tk,
                isConnected = active.contains(key),
                isPending = pending.contains(key),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Emits a redirect URL the screen should open in Chrome Custom Tab. */
    private val _pendingRedirect = MutableStateFlow<String?>(null)
    val pendingRedirect: StateFlow<String?> = _pendingRedirect.asStateFlow()
    fun consumeRedirect() { _pendingRedirect.value = null }

    /** User-facing error from a connect attempt. Screen shows as Toast and clears it. */
    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()
    fun consumeConnectError() { _connectError.value = null }

    /** Kick off an OAuth connection for one of the featured chips. Surface failures
     *  through `connectError` so the user sees feedback instead of a silent no-op. */
    fun connect(toolkitSlug: String) {
        viewModelScope.launch {
            val init = runCatching { composio.startConnect(toolkitSlug) }
                .onFailure { _connectError.value = "Couldn't reach Composio: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
            if (init != null) {
                _pendingRedirect.value = init.redirectUrl
            } else if (_connectError.value == null) {
                _connectError.value = "Couldn't start $toolkitSlug. Composio rejected the request — check your Composio key in Settings."
            }
        }
    }

    /** Called by the screen ON_RESUME after the OAuth tab. Pulls fresh statuses + restarts
     *  the gateway if a new connection went ACTIVE. */
    fun onResumeFromOAuth() {
        viewModelScope.launch {
            runCatching { composio.syncConnections() }
            val url = runCatching { composio.refreshMcpUrl() }.getOrNull()
            if (!url.isNullOrBlank()) {
                runCatching {
                    GatewayService.stop(appContext)
                    GatewayService.start(appContext)
                }
            }
        }
    }

    /** Pull a fresh toolkit catalog on demand. */
    fun refreshCatalog() = composio.refreshCatalog()

    init {
        composio.refreshCatalog()
        viewModelScope.launch { runCatching { composio.syncConnections() } }
    }
}
