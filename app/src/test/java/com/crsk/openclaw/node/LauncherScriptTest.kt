package com.crsk.openclaw.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherScriptTest {
    @Test
    fun rendersShebangAndExportsLdLibraryPath() {
        val script = LauncherScript.render(
            libDir = "/data/app/com.crsk.openclaw/lib/arm64",
            homeDir = "/data/data/com.crsk.openclaw/files/home",
            shimEntry = "/data/data/com.crsk.openclaw/files/home/.openclaw/mcp/shizuku-phone/index.js",
        )
        assertTrue("starts with shebang", script.startsWith("#!/system/bin/sh\n"))
        assertTrue("exports LD_LIBRARY_PATH", script.contains("export LD_LIBRARY_PATH=\"/data/app/com.crsk.openclaw/lib/arm64\""))
        assertTrue("exports HOME", script.contains("export HOME=\"/data/data/com.crsk.openclaw/files/home\""))
        assertTrue(
            "execs libnode.so with shim entry",
            script.contains("exec \"/data/app/com.crsk.openclaw/lib/arm64/libnode.so\" \"/data/data/com.crsk.openclaw/files/home/.openclaw/mcp/shizuku-phone/index.js\"")
        )
    }

    @Test
    fun usesExecToPreservePid() {
        val script = LauncherScript.render(libDir = "/x", homeDir = "/y", shimEntry = "/z/index.js")
        val execLines = script.lines().filter { it.startsWith("exec ") }
        assertEquals("exactly one exec line", 1, execLines.size)
    }

    @Test
    fun endsWithNewline() {
        val script = LauncherScript.render(libDir = "/x", homeDir = "/y", shimEntry = "/z/index.js")
        assertTrue("ends with newline", script.endsWith("\n"))
    }
}
