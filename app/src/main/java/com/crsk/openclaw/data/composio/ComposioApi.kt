package com.crsk.openclaw.data.composio

import com.crsk.openclaw.data.preferences.EncryptedKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct HTTP wrapper around the Composio v3 API, authenticated with the user's
 * own Composio key (Settings → Integrations, stored in EncryptedSharedPreferences).
 * The device id doubles as Composio's `user_id` so connections stay scoped to
 * this install. Every call is a graceful no-op (empty result / null) while no
 * key is saved.
 */
@Singleton
class ComposioApi @Inject constructor(
    private val keyStore: EncryptedKeyStore,
) {

    private val apiKey: String get() = keyStore.getComposioKey()

    fun hasKey(): Boolean = apiKey.isNotBlank()

    suspend fun listToolkits(deviceId: String, limit: Int = 200): List<Toolkit> = withContext(Dispatchers.IO) {
        val raw = getJson("/toolkits?limit=$limit") ?: return@withContext emptyList()
        // Composio shape: { items: [{slug, name, description, logo, categories, meta:{auth_type}}, ...], nextCursor }
        val items = raw.optJSONArray("items") ?: raw.optJSONArray("toolkits") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            Toolkit(
                slug = o.optString("slug").ifBlank { o.optString("name").lowercase() },
                name = o.optString("name").ifBlank { o.optString("slug") },
                description = o.optString("description", ""),
                logoUrl = o.optString("logo").ifBlank { o.optJSONObject("meta")?.optString("logo") },
                categories = o.optJSONArray("categories")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                authType = o.optJSONObject("meta")?.optString("auth_type")
                    ?: o.optString("auth_type", "OAUTH2"),
            )
        }
    }

    suspend fun startConnect(deviceId: String, toolkitSlug: String): ConnectInit? = withContext(Dispatchers.IO) {
        // Composio's API requires an auth_config_id, not a raw toolkit slug. For
        // Composio-managed OAuth (the default for personal use) we create-or-reuse
        // a "use_composio_managed_auth" auth_config for the toolkit, then call
        // /connected_accounts/link with that id. Two round-trips per connect, but
        // it happens once per toolkit. Slugs in auth_configs are uppercase.
        val toolkitUpper = toolkitSlug.uppercase()
        val acBody = JSONObject()
            .put("toolkit", JSONObject().put("slug", toolkitUpper))
            .put(
                "auth_config",
                JSONObject()
                    .put("type", "use_composio_managed_auth")
                    .put("name", "4ais-$toolkitUpper-managed"),
            )
        val acRes = postJson("/auth_configs", acBody) ?: return@withContext null
        val authConfigId = acRes.optString("id")
            .ifBlank { acRes.optJSONObject("auth_config")?.optString("id") ?: "" }
            .ifBlank { acRes.optString("nanoid") }
        if (authConfigId.isBlank()) return@withContext null

        val linkBody = JSONObject()
            .put("auth_config_id", authConfigId)
            .put("user_id", deviceId)
        val res = postJson("/connected_accounts/link", linkBody) ?: return@withContext null
        val redirect = res.optString("redirect_url").ifBlank {
            res.optString("redirectUrl").ifBlank { res.optString("authorization_url") }
        }
        val connId = res.optString("connected_account_id").ifBlank {
            res.optString("id").ifBlank {
                res.optString("connection_id").ifBlank { res.optString("connectionId") }
            }
        }
        if (redirect.isBlank() || connId.isBlank()) return@withContext null
        ConnectInit(connectionId = connId, redirectUrl = redirect)
    }

    suspend fun listConnections(deviceId: String): List<Connection> = withContext(Dispatchers.IO) {
        val user = URLEncoder.encode(deviceId, "UTF-8")
        val raw = getJson("/connected_accounts?user_id=$user") ?: return@withContext emptyList()
        val items = raw.optJSONArray("items") ?: raw.optJSONArray("connected_accounts") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            // Composio's actual shape: toolkit is a NESTED OBJECT with .slug (uppercase).
            // Lowercase it here so it matches the catalog's slug used by Status/Connections UIs.
            val toolkitSlug = (o.optJSONObject("toolkit")?.optString("slug")
                ?: o.optString("toolkit_slug").ifBlank { o.optString("toolkit") })
                .lowercase()
            Connection(
                toolkitSlug = toolkitSlug,
                connectionId = o.optString("id")
                    .ifBlank { o.optString("connected_account_id") }
                    .ifBlank { o.optString("nanoid") },
                status = ConnectionStatus.fromString(o.optString("status")),
                connectedAt = o.optLong("created_at", System.currentTimeMillis()),
            )
        }
    }

    suspend fun revoke(deviceId: String, connectionId: String): Boolean = withContext(Dispatchers.IO) {
        val conn = open("/connected_accounts/${URLEncoder.encode(connectionId, "UTF-8")}", "DELETE")
            ?: return@withContext false
        try {
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** Generate one MCP server URL covering the given toolkits. Agent connects to this URL. */
    suspend fun generateMcpUrl(deviceId: String, toolkits: List<String>): String? = withContext(Dispatchers.IO) {
        if (toolkits.isEmpty()) return@withContext null
        val body = JSONObject()
            .put("name", "4ais-$deviceId")
            .put("toolkits", JSONArray(toolkits))
            .put("user_id", deviceId)
        val res = postJson("/mcp/servers/generate", body) ?: return@withContext null
        res.optString("url").ifBlank { res.optString("mcp_url") }.takeIf { it.isNotBlank() }
    }

    // --- HTTP helpers ---

    private fun open(path: String, method: String): HttpURLConnection? {
        val key = apiKey
        if (key.isBlank()) return null
        val conn = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("x-api-key", key)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 8_000
            readTimeout = 15_000
        }
        return conn
    }

    private fun getJson(path: String): JSONObject? {
        val conn = open(path, "GET") ?: return null
        return try {
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        val conn = open(path, "POST")?.apply { doOutput = true } ?: return null
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://backend.composio.dev/api/v3"
    }
}
