package com.crsk.openclaw.accessibility

/**
 * Pure, framework-free axis-aligned box in screen pixels. Replaces android.graphics.Rect
 * inside ElementNode so the UI data model and occlusion math are JVM-unit-testable
 * (android.graphics.* returns default 0s under unitTests.isReturnDefaultValues).
 */
data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** True if this box shares positive-area overlap with [other] (edge-touch is not intersection). */
    fun intersects(other: IntBox): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
