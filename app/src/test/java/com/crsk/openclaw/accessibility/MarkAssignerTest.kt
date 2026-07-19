package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkAssignerTest {
    private var idx = 0
    private fun node(
        clickable: Boolean = false,
        occluded: Boolean = false,
        children: List<ElementNode> = emptyList(),
    ) = ElementNode(
        index = idx++, className = "X", text = "", contentDescription = "", resourceId = "",
        bounds = IntBox(0, 0, 1, 1), isClickable = clickable, isEditable = false,
        isScrollable = false, isFocused = false, isChecked = null, isCheckable = false,
        children = children,
    ).apply { this.occluded = occluded }

    @Test fun assigns_contiguous_ids_preorder_to_interactables_only() {
        idx = 0
        val leafA = node(clickable = true)            // interactable
        val leafB = node(clickable = false)           // not
        val leafC = node(clickable = true)            // interactable
        val parent = node(clickable = true, children = listOf(leafA, leafB, leafC)) // interactable
        val marks = MarkAssigner.assign(listOf(parent))

        // Pre-order: parent, leafA, leafB(skip), leafC → marks = [parent, leafA, leafC]
        assertEquals(listOf(0, 1, 2), marks.map { it.markId })
        assertEquals(3, marks.size)
        assertEquals(0, parent.markId)
        assertEquals(1, leafA.markId)
        assertEquals(-1, leafB.markId)
        assertEquals(2, leafC.markId)
    }

    @Test fun skips_occluded_interactables() {
        idx = 0
        val visible = node(clickable = true)
        val hidden = node(clickable = true, occluded = true)
        val marks = MarkAssigner.assign(listOf(visible, hidden))
        assertEquals(1, marks.size)
        assertEquals(0, visible.markId)
        assertEquals(-1, hidden.markId)
    }
}
