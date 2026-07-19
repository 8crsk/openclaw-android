package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServedLegendTest {
    // cx is the desired CENTER x; build a 10px-wide box centered on it (centerX == cx).
    private fun mark(id: Int, cx: Int) = ElementNode(
        index = id, className = "X", text = "t$id", contentDescription = "", resourceId = "",
        bounds = IntBox(cx - 5, 0, cx + 5, 10), isClickable = true, isEditable = false,
        isScrollable = false, isFocused = false, isChecked = null, isCheckable = false,
        children = emptyList(),
    ).apply { markId = id }

    @Test fun resolves_against_last_served_coordinates() {
        val s = ServedLegend()
        s.update(listOf(mark(0, 0), mark(1, 100), mark(2, 200)))
        // The live tree may have renumbered, but resolution stays pinned to what was served.
        assertEquals(100, s.resolve(1)!!.centerX)
        assertEquals("t2", s.resolve(2)!!.text)
    }

    @Test fun out_of_range_is_null() {
        val s = ServedLegend()
        s.update(listOf(mark(0, 0)))
        assertNull(s.resolve(5))
    }

    @Test fun empty_before_first_serve_is_null() {
        assertNull(ServedLegend().resolve(0))
    }
}
