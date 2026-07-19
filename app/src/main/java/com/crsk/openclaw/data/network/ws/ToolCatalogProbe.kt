package com.crsk.openclaw.data.network.ws

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot RPC probe that asks the gateway for its current tool catalog and
 * logs the result. Used to confirm whether MCP-server tools have surfaced to
 * the agent. The probe is idempotent across an app session — call sites can
 * fire it freely on every streamChat; it only hits the wire once.
 */
@Singleton
class ToolCatalogProbe @Inject constructor(
    private val ws: WsRpcClient,
) {
    private val fired = AtomicBoolean(false)

    suspend fun runOnce() {
        if (!fired.compareAndSet(false, true)) return
        runCatching {
            val res = ws.call("tools.catalog", JSONObject(), timeoutMs = 10_000)
            if (!res.ok) {
                Log.w(TAG, "tools.catalog rpc not ok: ${res.error?.code} ${res.error?.message}")
                return
            }
            val payload = res.payload ?: JSONObject()
            logCatalog(payload)
        }.onFailure { Log.w(TAG, "tools.catalog probe failed: ${it.message}") }
    }

    private fun logCatalog(payload: JSONObject) {
        val tools = payload.optJSONArray("tools")
        val servers = payload.optJSONObject("servers")
        val toolCount = tools?.length() ?: 0

        val serverSummary = if (servers != null) {
            val keys = servers.keys().asSequence().toList()
            keys.joinToString(", ") { name ->
                val toolN = servers.optJSONObject(name)?.optInt("toolCount") ?: 0
                "$name:$toolN tools"
            }
        } else "(none)"

        val toolNames = (0 until toolCount).map {
            tools!!.optJSONObject(it).optString("toolName").ifBlank {
                tools.optJSONObject(it).optString("name")
            }
        }
        Log.i(TAG, "ToolCatalog: servers=[$serverSummary] tools=$toolNames")
    }

    companion object { private const val TAG = "ToolCatalogProbe" }
}
