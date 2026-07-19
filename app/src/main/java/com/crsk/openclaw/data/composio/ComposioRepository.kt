package com.crsk.openclaw.data.composio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crsk.openclaw.util.DeviceId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.composioStore: DataStore<Preferences> by preferencesDataStore(name = "composio_prefs")
private val CONNECTIONS_KEY = stringPreferencesKey("connections_json")
private val CACHED_MCP_URL_KEY = stringPreferencesKey("cached_mcp_url")

/**
 * Manages Composio connections: catalog cache + per-device connection list. Persists
 * connections to DataStore. NodeProcess reads `connections` on gateway start to inject
 * MCP server entries; SettingsScreen observes the same Flow to render the UI.
 */
@Singleton
class ComposioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ComposioApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceId: String get() = DeviceId.get(context)

    /** Cached toolkit catalog. Refreshed on demand. */
    private val _catalog = MutableStateFlow<List<Toolkit>>(emptyList())
    val catalog: StateFlow<List<Toolkit>> = _catalog.asStateFlow()

    private val _isLoadingCatalog = MutableStateFlow(false)
    val isLoadingCatalog: StateFlow<Boolean> = _isLoadingCatalog.asStateFlow()

    /** Persisted connections keyed by toolkit slug. */
    val connections: Flow<List<Connection>> = context.composioStore.data.map { prefs ->
        val raw = prefs[CONNECTIONS_KEY] ?: return@map emptyList()
        deserialize(raw)
    }

    /** Trigger a catalog refresh (network). Safe to call repeatedly; no-ops if already loading. */
    fun refreshCatalog() {
        if (_isLoadingCatalog.value) return
        scope.launch {
            _isLoadingCatalog.value = true
            try {
                val list = api.listToolkits(deviceId)
                if (list.isNotEmpty()) _catalog.value = list
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }

    /** Start an OAuth connection for a toolkit. Returns the redirect URL the caller should
     *  open in a Chrome Custom Tab. Stores an INITIATED connection record locally so the
     *  Connections UI shows it immediately. */
    suspend fun startConnect(toolkitSlug: String): ConnectInit? {
        val init = api.startConnect(deviceId, toolkitSlug) ?: return null
        upsert(Connection(
            toolkitSlug = toolkitSlug,
            connectionId = init.connectionId,
            status = ConnectionStatus.INITIATED,
            connectedAt = System.currentTimeMillis(),
        ))
        return init
    }

    /** Pull fresh connection statuses from Composio and reconcile local cache. Call after
     *  returning from the OAuth tab. */
    suspend fun syncConnections() {
        val server = api.listConnections(deviceId)
        if (server.isEmpty()) return
        context.composioStore.edit { prefs ->
            val current = deserialize(prefs[CONNECTIONS_KEY] ?: "[]").associateBy { it.toolkitSlug }.toMutableMap()
            for (c in server) current[c.toolkitSlug] = c
            prefs[CONNECTIONS_KEY] = serialize(current.values.toList())
        }
    }

    /** Revoke + remove a connection. */
    suspend fun disconnect(toolkitSlug: String) {
        val current = currentList()
        val target = current.firstOrNull { it.toolkitSlug == toolkitSlug } ?: return
        withContext(Dispatchers.IO) { api.revoke(deviceId, target.connectionId) }
        remove(toolkitSlug)
    }

    /** All connections that are ACTIVE — what we'd hand to openclaw as MCP servers. */
    suspend fun activeToolkitSlugs(): List<String> =
        currentList().filter { it.status == ConnectionStatus.ACTIVE }.map { it.toolkitSlug }

    /** Generate one MCP URL covering all active connections AND cache it. NodeProcess
     *  reads the cached value synchronously on every gateway start. */
    suspend fun refreshMcpUrl(): String? {
        val active = activeToolkitSlugs()
        val url = if (active.isEmpty()) null else api.generateMcpUrl(deviceId, active)
        context.composioStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(CACHED_MCP_URL_KEY) else prefs[CACHED_MCP_URL_KEY] = url
        }
        return url
    }

    /** Synchronous accessor for NodeProcess. Returns null if no active connections. */
    fun cachedMcpUrlBlocking(): String? = kotlinx.coroutines.runBlocking {
        context.composioStore.data.map { it[CACHED_MCP_URL_KEY] }.first()
    }

    // --- internals ---

    private suspend fun currentList(): List<Connection> {
        val raw = context.composioStore.data.map { it[CONNECTIONS_KEY] ?: "[]" }.first()
        return deserialize(raw)
    }

    private suspend fun upsert(c: Connection) {
        context.composioStore.edit { prefs ->
            val current = deserialize(prefs[CONNECTIONS_KEY] ?: "[]").toMutableList()
            current.removeAll { it.toolkitSlug == c.toolkitSlug }
            current.add(c)
            prefs[CONNECTIONS_KEY] = serialize(current)
        }
    }

    private suspend fun remove(toolkitSlug: String) {
        context.composioStore.edit { prefs ->
            val current = deserialize(prefs[CONNECTIONS_KEY] ?: "[]").toMutableList()
            current.removeAll { it.toolkitSlug == toolkitSlug }
            prefs[CONNECTIONS_KEY] = serialize(current)
        }
    }

    private fun serialize(list: List<Connection>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject().apply {
                put("slug", c.toolkitSlug)
                put("id", c.connectionId)
                put("status", c.status.name)
                put("at", c.connectedAt)
            })
        }
        return arr.toString()
    }

    private fun deserialize(raw: String): List<Connection> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Connection(
                toolkitSlug = o.optString("slug"),
                connectionId = o.optString("id"),
                status = ConnectionStatus.fromString(o.optString("status")),
                connectedAt = o.optLong("at", 0),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
