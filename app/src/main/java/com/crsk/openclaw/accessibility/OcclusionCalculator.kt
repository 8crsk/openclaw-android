package com.crsk.openclaw.accessibility

/**
 * Pure geometry: how much of a box remains visible after a set of occluder boxes are
 * drawn over it. Uses recursive rectangle subtraction so overlapping occluders are
 * counted once (no double-subtraction). Framework-free → JVM-unit-testable.
 */
object OcclusionCalculator {

    /** @return visible fraction in [0,1]; 0 for a zero-area box. */
    fun visibleFraction(box: IntBox, occluders: List<IntBox>): Float {
        if (box.area <= 0) return 0f
        val relevant = occluders.filter { it.intersects(box) }
        if (relevant.isEmpty()) return 1f
        val visibleArea = subtractAll(box, relevant).sumOf { it.area }
        return (visibleArea.toFloat() / box.area.toFloat()).coerceIn(0f, 1f)
    }

    /** Subtract every occluder from [box], returning disjoint visible pieces. */
    private fun subtractAll(box: IntBox, occluders: List<IntBox>): List<IntBox> {
        var pieces = listOf(box)
        for (occ in occluders) {
            pieces = pieces.flatMap { subtract(it, occ) }
            if (pieces.isEmpty()) break
        }
        return pieces
    }

    /** Subtract one occluder from one box → up to 4 non-overlapping remainder boxes. */
    private fun subtract(box: IntBox, occ: IntBox): List<IntBox> {
        if (!box.intersects(occ)) return listOf(box)
        val ix1 = maxOf(box.left, occ.left)
        val iy1 = maxOf(box.top, occ.top)
        val ix2 = minOf(box.right, occ.right)
        val iy2 = minOf(box.bottom, occ.bottom)
        val out = ArrayList<IntBox>(4)
        if (box.top < iy1) out.add(IntBox(box.left, box.top, box.right, iy1))         // above
        if (iy2 < box.bottom) out.add(IntBox(box.left, iy2, box.right, box.bottom))   // below
        if (box.left < ix1) out.add(IntBox(box.left, iy1, ix1, iy2))                  // left
        if (ix2 < box.right) out.add(IntBox(ix2, iy1, box.right, iy2))                // right
        return out.filter { it.area > 0 }
    }
}
