package com.crsk.openclaw.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdFormatTest {
    @Test fun `normalizeAndroidId pads short ids to 16 hex chars`() {
        val out = DeviceId.normalize("abc123")
        assertEquals(16, out.length)
        assertTrue(out.matches(Regex("^[a-f0-9]{16}$")))
    }

    @Test fun `normalizeAndroidId truncates long ids to 16 hex chars`() {
        val out = DeviceId.normalize("0123456789abcdef0123456789abcdef")
        assertEquals(16, out.length)
        assertTrue(out.matches(Regex("^[a-f0-9]{16}$")))
    }

    @Test fun `normalizeAndroidId lowercases and strips non-hex`() {
        val out = DeviceId.normalize("ABCD-1234-XXXX-5678")
        assertEquals(16, out.length)
        assertTrue(out.matches(Regex("^[a-f0-9]{16}$")))
    }

    @Test fun `normalizeAndroidId is deterministic for same input`() {
        assertEquals(DeviceId.normalize("abc"), DeviceId.normalize("abc"))
    }

    @Test fun `normalizeAndroidId is different for different inputs`() {
        assertTrue(DeviceId.normalize("abc") != DeviceId.normalize("xyz"))
    }
}
