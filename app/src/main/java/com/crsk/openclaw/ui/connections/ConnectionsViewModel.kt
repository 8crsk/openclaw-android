package com.crsk.openclaw.ui.connections

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsk.openclaw.data.composio.Connection
import com.crsk.openclaw.data.composio.ConnectionStatus
import com.crsk.openclaw.data.composio.ComposioRepository
import com.crsk.openclaw.data.composio.Toolkit
import com.crsk.openclaw.service.GatewayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionsUiState(
    val toolkits: List<ToolkitRow> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val pendingRedirectUrl: String? = null,
    val error: String? = null,
)

data class ToolkitRow(
    val toolkit: Toolkit,
    val connection: Connection?,
) {
    val isConnected get() = connection?.status == ConnectionStatus.ACTIVE
    val isPending get() = connection?.status == ConnectionStatus.INITIATED
}

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val composio: ComposioRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _pendingRedirect = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ConnectionsUiState> = combine(
        composio.catalog,
        composio.connections,
        _query,
        composio.isLoadingCatalog,
        combine(_pendingRedirect, _error) { a, b -> a to b },
    ) { catalog, connections, query, isLoading, (pendingRedirect, error) ->
        val connectionsBySlug = connections.associateBy { it.toolkitSlug }
        val filtered = if (query.isBlank()) catalog else catalog.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.slug.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
        ConnectionsUiState(
            toolkits = filtered.map { ToolkitRow(it, connectionsBySlug[it.slug]) },
            query = query,
            isLoading = isLoading,
            pendingRedirectUrl = pendingRedirect,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionsUiState())

    init {
        // Lazy first-time fetch. Subsequent screen entries reuse cached catalog.
        composio.refreshCatalog()
        viewModelScope.launch { runCatching { composio.syncConnections() } }
    }

    fun onQueryChange(q: String) { _query.value = q }
    fun refresh() {
        composio.refreshCatalog()
        viewModelScope.launch { runCatching { composio.syncConnections() } }
    }

    fun connect(toolkitSlug: String) {
        viewModelScope.launch {
            _error.value = null
            val init = runCatching { composio.startConnect(toolkitSlug) }.getOrNull()
            if (init == null) {
                _error.value = "Couldn't start connection for $toolkitSlug. Check your network."
                return@launch
            }
            _pendingRedirect.value = init.redirectUrl
        }
    }

    fun consumeRedirect() { _pendingRedirect.value = null }
    fun clearError() { _error.value = null }

    /** Call after the OAuth tab returns. Polls Composio for fresh status; if any new
     *  ACTIVE connection appeared, regenerate MCP URL and restart the gateway so the
     *  agent picks up the new tools. */
    fun onReturnFromOAuth() {
        viewModelScope.launch {
            runCatching { composio.syncConnections() }
            val url = runCatching { composio.refreshMcpUrl() }.getOrNull()
            if (!url.isNullOrBlank()) {
                // Gateway needs a restart to re-read config.json with the new mcp.servers entry.
                runCatching {
                    GatewayService.stop(appContext)
                    GatewayService.start(appContext)
                }
            }
        }
    }

    fun disconnect(toolkitSlug: String) {
        viewModelScope.launch {
            runCatching { composio.disconnect(toolkitSlug) }
            runCatching { composio.refreshMcpUrl() }
            // Restart gateway so the removed tool drops from the agent's catalog.
            runCatching {
                GatewayService.stop(appContext)
                GatewayService.start(appContext)
            }
        }
    }
}
