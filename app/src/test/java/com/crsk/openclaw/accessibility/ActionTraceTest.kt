package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionTraceTest {
    @Test fun caps_and_returns_newest_first() {
        val t = ActionTrace(cap = 3)
        repeat(5) { i -> t.record("tap", "{id:$i}", ok = true, detail = "d$i") }
        val recent = t.snapshot(10)
        assertEquals(3, recent.size)                       // capped
        assertEquals("{id:4}", recent.first().args)        // newest first
        assertEquals("{id:2}", recent.last().args)
    }

    @Test fun limit_truncates() {
        val t = ActionTrace(cap = 10)
        repeat(5) { i -> t.record("v$i", "", ok = true, detail = "") }
        assertEquals(2, t.snapshot(2).size)
    }
}
