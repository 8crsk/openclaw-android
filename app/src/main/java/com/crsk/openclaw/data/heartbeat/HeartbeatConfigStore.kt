package com.crsk.openclaw.data.heartbeat

import org.json.JSONObject
import java.io.File

data class HeartbeatState(
    val enabled: Boolean,
    val interval: String = "30m",
    // Default to a full 24h active window so users don't silently hit "quiet-hours"
    // skips when they enable heartbeat outside a narrow daytime window.
    val activeHoursStart: String = "00:00",
    val activeHoursEnd: String = "23:59",
    val timezone: String = java.util.TimeZone.getDefault().id,
    val tasksMarkdown: String = "",
)

/**
 * Reads and writes the `agents.defaults.heartbeat` block in openclaw's config.json
 * and the workspace `HEARTBEAT.md` file. Pure I/O; no Android dependencies.
 */
class HeartbeatConfigStore(private val configDir: File) {
    private val configFile get() = File(configDir, "config.json")
    // openclaw 2026.5.12 reads heartbeat workspace files (HEARTBEAT.md, MEMORY.md)
    // from the agent's workspace dir, not the openclaw config dir. The workspace
    // path is <configDir>/workspace.
    private val workspaceDir get() = File(configDir, "workspace").also { it.mkdirs() }
    private val tasksFile get() = File(workspaceDir, "HEARTBEAT.md")

    fun read(): HeartbeatState {
        val hb = readBlock()
        // hb == null means the heartbeat block has never been written → disabled by default
        val every = hb?.optString("every", "0m") ?: "0m"
        val ah = hb?.optJSONObject("activeHours")
        return HeartbeatState(
            enabled = every != "0m",
            interval = if (every == "0m") "30m" else every,
            activeHoursStart = ah?.optString("start", "00:00") ?: "00:00",
            activeHoursEnd = ah?.optString("end", "23:59") ?: "23:59",
            timezone = ah?.optString("timezone", java.util.TimeZone.getDefault().id)
                ?: java.util.TimeZone.getDefault().id,
            tasksMarkdown = if (tasksFile.exists()) tasksFile.readText() else "",
        )
    }

    fun write(state: HeartbeatState) {
        val cfg = if (configFile.exists()) JSONObject(configFile.readText()) else JSONObject()
        val agents = cfg.optJSONObject("agents") ?: JSONObject().also { cfg.put("agents", it) }
        val defaults = agents.optJSONObject("defaults") ?: JSONObject().also { agents.put("defaults", it) }
        val hb = defaults.optJSONObject("heartbeat") ?: JSONObject().also { defaults.put("heartbeat", it) }

        hb.put("every", if (state.enabled) state.interval else "0m")
        hb.put("target", "none")
        hb.put("skipWhenBusy", true)
        // Fresh session per tick. Without this, the agent sees its previous reply
        // in history and parrots it ("23:13 alive" forever). Isolated session
        // forces re-computing the answer from the prompt every time.
        hb.put("isolatedSession", true)
        hb.put("ackMaxChars", 300)
        hb.put("lightContext", false)
        hb.put("activeHours", JSONObject().apply {
            put("start", state.activeHoursStart)
            put("end", state.activeHoursEnd)
            put("timezone", state.timezone)
        })
        configDir.mkdirs()
        configFile.writeText(cfg.toString(2))
    }

    fun writeTasks(markdown: String) {
        configDir.mkdirs()
        tasksFile.writeText(markdown)
    }

    private fun readBlock(): JSONObject? {
        if (!configFile.exists()) return null
        return runCatching {
            JSONObject(configFile.readText())
                .optJSONObject("agents")
                ?.optJSONObject("defaults")
                ?.optJSONObject("heartbeat")
        }.getOrNull()
    }
}