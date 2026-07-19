package com.crsk.openclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDetailFormatterTest {

    @Test
    fun `command field becomes the one-line summary`() {
        val s = ToolDetailFormatter.oneLineSummary("exec", """{"command":"curl 127.0.0.1:3001/ui/tap"}""")
        assertEquals("curl 127.0.0.1:3001/ui/tap", s)
    }

    @Test
    fun `typed text is quoted`() {
        val s = ToolDetailFormatter.oneLineSummary("ui_type", """{"text":"hello world"}""")
        assertEquals("\"hello world\"", s)
    }

    @Test
    fun `target label is used when no command or text`() {
        val s = ToolDetailFormatter.oneLineSummary("ui_tap", """{"target":"Submit"}""")
        assertEquals("Submit", s)
    }

    @Test
    fun `blank or unparseable args yield null`() {
        assertEquals(null, ToolDetailFormatter.oneLineSummary("exec", ""))
        assertEquals(null, ToolDetailFormatter.oneLineSummary("exec", "not json"))
    }

    @Test
    fun `pretty prints valid json and passes through non-json`() {
        val pretty = ToolDetailFormatter.prettyArgs("""{"a":1}""")
        assertEquals(true, pretty.contains("\"a\""))
        assertEquals("plain text", ToolDetailFormatter.prettyArgs("plain text"))
    }

    @Test
    fun `overlay label combines tool name with summary`() {
        assertEquals("ui_tap · Submit", ToolDetailFormatter.overlayLabel("ui_tap", """{"target":"Submit"}"""))
    }

    @Test
    fun `overlay label falls back to name when no summary`() {
        assertEquals("scroll", ToolDetailFormatter.overlayLabel("scroll", ""))
    }

    @Test
    fun `overlay label truncates a long summary`() {
        val long = "x".repeat(80)
        val label = ToolDetailFormatter.overlayLabel("exec", """{"command":"$long"}""")
        // name + separator + 40-char clamp + ellipsis, never the full 80 chars
        assertEquals(true, label.length < 60)
        assertEquals(true, label.startsWith("exec · "))
        assertEquals(true, label.endsWith("…"))
    }
}
