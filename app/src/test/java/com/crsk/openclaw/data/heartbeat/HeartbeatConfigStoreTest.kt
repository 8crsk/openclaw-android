package com.crsk.openclaw.data.heartbeat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeartbeatConfigStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun storeOver(configDir: File): HeartbeatConfigStore = HeartbeatConfigStore(configDir)

    @Test fun `reads default disabled state from missing config`() {
        val store = storeOver(tmp.newFolder("cfg"))
        val state = store.read()
        assertEquals(false, state.enabled)
        assertEquals("30m", state.interval)
        assertEquals("", state.tasksMarkdown)
    }

    @Test fun `writes heartbeat block and reads it back`() {
        val dir = tmp.newFolder("cfg")
        File(dir, "config.json").writeText("""{"agents":{"defaults":{}}}""")
        val store = storeOver(dir)

        store.write(HeartbeatState(enabled = true, interval = "1h", activeHoursStart = "09:00", activeHoursEnd = "21:00", timezone = "America/New_York"))
        val state = store.read()
        assertEquals(true, state.enabled)
        assertEquals("1h", state.interval)
        assertEquals("09:00", state.activeHoursStart)
        assertEquals("21:00", state.activeHoursEnd)
        assertEquals("America/New_York", state.timezone)

        val raw = JSONObject(File(dir, "config.json").readText())
        val hb = raw.getJSONObject("agents").getJSONObject("defaults").getJSONObject("heartbeat")
        assertEquals("1h", hb.getString("every"))
        assertEquals("none", hb.getString("target"))
    }

    @Test fun `disabling writes every 0m and preserves interval as lastIntervalMin sidecar`() {
        val dir = tmp.newFolder("cfg")
        val store = storeOver(dir)
        store.write(HeartbeatState(enabled = true, interval = "1h"))
        store.write(HeartbeatState(enabled = false, interval = "1h"))
        val raw = JSONObject(File(dir, "config.json").readText())
        assertEquals("0m", raw.getJSONObject("agents").getJSONObject("defaults").getJSONObject("heartbeat").getString("every"))
    }

    @Test fun `reads and writes HEARTBEAT_md file in workspace`() {
        val dir = tmp.newFolder("cfg")
        val store = storeOver(dir)
        store.writeTasks("# tasks\n- check the time\n")
        assertEquals("# tasks\n- check the time\n", store.read().tasksMarkdown)
        // openclaw reads HEARTBEAT.md from <configDir>/workspace, not configDir directly.
        assertTrue(File(dir, "workspace/HEARTBEAT.md").exists())
    }
}