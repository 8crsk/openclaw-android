package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntBoxTest {
    @Test fun centers_and_dimensions() {
        val b = IntBox(10, 20, 30, 60)
        assertEquals(20, b.centerX)
        assertEquals(40, b.centerY)
        assertEquals(20, b.width)
        assertEquals(40, b.height)
        assertEquals(800, b.area)
    }

    @Test fun zero_area_box() {
        val b = IntBox(5, 5, 5, 5)
        assertEquals(0, b.area)
    }

    @Test fun intersects_screen() {
        val screen = IntBox(0, 0, 100, 100)
        assertTrue(IntBox(50, 50, 150, 150).intersects(screen))
        assertFalse(IntBox(100, 100, 200, 200).intersects(screen)) // touching edge only
        assertFalse(IntBox(-50, -50, 0, 0).intersects(screen))
    }
}
