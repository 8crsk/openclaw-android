package com.crsk.openclaw.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollingLogFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun writesLinesToFile() {
        val f = File(tmp.root, "log.err")
        val log = RollingLogFile(f, maxBytes = 1024)
        log.append("hello\n")
        log.append("world\n")
        log.close()
        assertEquals("hello\nworld\n", f.readText())
    }

    @Test
    fun trimsWhenOverCap() {
        val f = File(tmp.root, "log.err")
        val log = RollingLogFile(f, maxBytes = 20)
        log.append("abcdefghij\n") // 11 bytes
        log.append("klmnopqrst\n") // 11 bytes — now 22 bytes, over cap
        log.append("uvwxyz\n")     // 7 bytes — triggers trim before write, then writes
        log.close()
        val text = f.readText()
        assertTrue("size <= cap", f.length() <= 20)
        assertTrue("keeps newest", text.contains("uvwxyz"))
    }

    @Test
    fun survivesPreExistingFile() {
        val f = File(tmp.root, "log.err")
        f.writeText("old content\n")
        val log = RollingLogFile(f, maxBytes = 1024)
        log.append("new\n")
        log.close()
        assertTrue(f.readText().contains("new"))
    }
}
