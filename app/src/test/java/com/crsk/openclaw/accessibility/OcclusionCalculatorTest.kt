package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class OcclusionCalculatorTest {
    private val box = IntBox(0, 0, 100, 100) // area 10000

    @Test fun no_occluders_fully_visible() {
        assertEquals(1f, OcclusionCalculator.visibleFraction(box, emptyList()), 0.0001f)
    }

    @Test fun fully_covered_is_zero() {
        assertEquals(0f, OcclusionCalculator.visibleFraction(box, listOf(IntBox(-10, -10, 110, 110))), 0.0001f)
    }

    @Test fun half_covered_is_half() {
        // Occluder covers the bottom half (y 50..100).
        assertEquals(0.5f, OcclusionCalculator.visibleFraction(box, listOf(IntBox(0, 50, 100, 100))), 0.0001f)
    }

    @Test fun two_overlapping_occluders_counted_once() {
        // Two occluders both cover the top half and overlap each other; union is the top half.
        val occ = listOf(IntBox(0, 0, 100, 50), IntBox(0, 0, 60, 50))
        assertEquals(0.5f, OcclusionCalculator.visibleFraction(box, occ), 0.0001f)
    }

    @Test fun zero_area_box_is_occluded() {
        assertEquals(0f, OcclusionCalculator.visibleFraction(IntBox(5, 5, 5, 5), emptyList()), 0.0001f)
    }
}
