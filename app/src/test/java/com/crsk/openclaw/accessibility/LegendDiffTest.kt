package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class LegendDiffTest {
    @Test fun appeared_and_disappeared() {
        val before = listOf("button|Send", "text|Hello")
        val after = listOf("text|Hello", "text|Sent ✓")
        val d = LegendDiff.diff(before, after)
        assertEquals(listOf("text|Sent ✓"), d.appeared)
        assertEquals(listOf("button|Send"), d.disappeared)
    }

    @Test fun identical_yields_empty() {
        val same = listOf("a|1", "b|2")
        val d = LegendDiff.diff(same, same)
        assertEquals(emptyList<String>(), d.appeared)
        assertEquals(emptyList<String>(), d.disappeared)
    }

    @Test fun null_baseline_treats_all_as_appeared() {
        val d = LegendDiff.diff(null, listOf("a|1"))
        assertEquals(listOf("a|1"), d.appeared)
        assertEquals(emptyList<String>(), d.disappeared)
    }
}
