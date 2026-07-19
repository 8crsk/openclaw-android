package com.crsk.openclaw.accessibility

import org.json.JSONArray
import org.json.JSONObject

data class ElementNode(
    val index: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: IntBox,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isFocused: Boolean,
    val isChecked: Boolean?,
    val isCheckable: Boolean,
    val children: List<ElementNode>,
) {
    /** Stable per-snapshot mark id (>=0 for interactables, assigned by MarkAssigner). -1 otherwise. */
    var markId: Int = -1

    /** Fraction of the node still visible after higher-z windows occlude it (1f = fully visible). */
    var visibleFraction: Float = 1f

    /** True when occluded below the visibility threshold; such nodes are dropped from the tree. */
    var occluded: Boolean = false

    val centerX: Int get() = bounds.centerX
    val centerY: Int get() = bounds.centerY

    /** The single predicate that defines what gets a mark / appears in the legend. */
    val isInteractable: Boolean get() = isClickable || isEditable || isScrollable

    val displayText: String
        get() = text.ifEmpty { contentDescription.ifEmpty { resourceId.substringAfterLast("/", "") } }

    fun matchesText(query: String, ignoreCase: Boolean = true): Boolean {
        return (text.isNotEmpty() && text.contains(query, ignoreCase)) ||
            (contentDescription.isNotEmpty() && contentDescription.contains(query, ignoreCase)) ||
            (resourceId.isNotEmpty() && resourceId.substringAfterLast("/", "").contains(query, ignoreCase))
    }

    fun matchesClass(cls: String): Boolean = className.endsWith(cls, ignoreCase = true)

    fun toCompactJson(): JSONObject = JSONObject().apply {
        // `id` is the single handle the model targets: == markId (badge number). Falls back to
        // tree `index` only for non-interactable nodes that still appear in the full tree dump.
        put("id", if (markId >= 0) markId else index)
        put("cls", className.substringAfterLast('.'))
        if (text.isNotEmpty()) put("txt", text)
        if (contentDescription.isNotEmpty()) put("desc", contentDescription)
        if (resourceId.isNotEmpty()) put("res", resourceId.substringAfterLast("/", ""))
        put("bounds", JSONArray().apply {
            put(bounds.left); put(bounds.top); put(bounds.right); put(bounds.bottom)
        })
        if (isClickable) put("click", true)
        if (isEditable) put("edit", true)
        if (isScrollable) put("scroll", true)
        if (isFocused) put("focus", true)
        if (isChecked != null) put("checked", isChecked)
        if (children.isNotEmpty()) {
            put("children", JSONArray().apply {
                children.forEach { put(if (it.markId >= 0) it.markId else it.index) }
            })
        }
    }
}
