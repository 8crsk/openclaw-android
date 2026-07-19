package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementNodeTest {
    private fun node(
        text: String = "", desc: String = "", res: String = "",
        cls: String = "android.widget.TextView",
        clickable: Boolean = false, editable: Boolean = false, scrollable: Boolean = false,
    ) = ElementNode(
        index = 0, className = cls, text = text, contentDescription = desc, resourceId = res,
        bounds = IntBox(0, 0, 10, 10), isClickable = clickable, isEditable = editable,
        isScrollable = scrollable, isFocused = false, isChecked = null, isCheckable = false,
        children = emptyList(),
    )

    @Test fun interactable_predicate() {
        assertTrue(node(clickable = true).isInteractable)
        assertTrue(node(editable = true).isInteractable)
        assertTrue(node(scrollable = true).isInteractable)
        assertFalse(node(text = "label").isInteractable)
    }

    @Test fun markId_defaults_to_minus_one() {
        assertEquals(-1, node().markId)
    }

    @Test fun compact_json_uses_markId_as_id_when_assigned() {
        val n = node(text = "Send", clickable = true).apply { markId = 7 }
        val json = n.toCompactJson()
        assertEquals(7, json.getInt("id"))
        assertEquals("Send", json.getString("txt"))
    }
}
