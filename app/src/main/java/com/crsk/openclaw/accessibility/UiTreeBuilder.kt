package com.crsk.openclaw.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** One window's root node plus its screen bounds and z-order layer (higher = drawn on top). */
data class WindowRoot(val root: AccessibilityNodeInfo, val bounds: IntBox, val layer: Int)

object UiTreeBuilder {

    private const val MAX_DEPTH = 30
    private const val MIN_SIZE = 5
    private const val MIN_VISIBLE_FRACTION = 0.01f

    /**
     * Build the flattened element tree across all windows, flagging nodes occluded by
     * higher-z windows. Windows are processed top-of-z first; each window's bounds then
     * occlude everything drawn below it (window-granularity occlusion — catches dialogs,
     * sheets, drawers, and the IME for free). Occluded nodes are flagged (not removed from
     * the tree); the flattener / MarkAssigner skip them.
     */
    fun build(
        windows: List<WindowRoot>,
        screenWidth: Int,
        screenHeight: Int,
        maxDepth: Int = MAX_DEPTH,
    ): List<ElementNode> {
        val screenBounds = Rect(0, 0, screenWidth, screenHeight)
        val indexCounter = IndexCounter()
        val visited = HashSet<Int>()
        val ordered = windows.sortedByDescending { it.layer }
        val occluders = mutableListOf<IntBox>()
        val result = mutableListOf<ElementNode>()

        for (w in ordered) {
            val windowNodes = mutableListOf<ElementNode>()
            traverse(w.root, screenBounds, indexCounter, visited, 0, maxDepth, windowNodes)
            markAndFilterOccluded(windowNodes, occluders)
            result.addAll(windowNodes)
            occluders.add(w.bounds)
        }
        return result
    }

    /** Recursively set visibleFraction/occluded against current occluders. Children kept (val);
     *  consumers skip flagged nodes. */
    private fun markAndFilterOccluded(nodes: List<ElementNode>, occluders: List<IntBox>) {
        if (occluders.isEmpty()) return
        for (n in nodes) {
            n.visibleFraction = OcclusionCalculator.visibleFraction(n.bounds, occluders)
            n.occluded = n.visibleFraction < MIN_VISIBLE_FRACTION
            markAndFilterOccluded(n.children, occluders)
        }
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        screenBounds: Rect,
        indexCounter: IndexCounter,
        visited: HashSet<Int>,
        depth: Int,
        maxDepth: Int,
        output: MutableList<ElementNode>,
    ) {
        if (depth > maxDepth) return
        val id = System.identityHashCode(node)
        if (!visited.add(id)) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (!Rect.intersects(bounds, screenBounds)) {
            recycleTree(node)
            return
        }
        if (bounds.width() < MIN_SIZE || bounds.height() < MIN_SIZE) {
            recycleTree(node)
            return
        }

        val children = mutableListOf<ElementNode>()
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                traverse(child, screenBounds, indexCounter, visited, depth + 1, maxDepth, children)
            }
        }

        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val resId = node.viewIdResourceName?.substringAfterLast(":id/").orEmpty()
        val cls = node.className?.toString().orEmpty()

        val isInteractive = node.isClickable || node.isFocusable || node.isEditable()
            || node.isScrollable || node.isCheckable || node.isLongClickable
        val hasContent = text.isNotEmpty() || desc.isNotEmpty() || resId.isNotEmpty()
        val hasChildren = children.isNotEmpty()

        if (isInteractive || hasContent || hasChildren) {
            val element = ElementNode(
                index = indexCounter.next(),
                className = cls,
                text = text,
                contentDescription = desc,
                resourceId = resId,
                bounds = IntBox(bounds.left, bounds.top, bounds.right, bounds.bottom),
                isClickable = node.isClickable || node.isLongClickable,
                isEditable = node.isEditable(),
                isScrollable = node.isScrollable,
                isFocused = node.isFocused,
                isChecked = if (node.isCheckable) node.isChecked else null,
                isCheckable = node.isCheckable,
                children = children,
            )
            output.add(element)
        } else {
            output.addAll(children)
        }

        try { node.recycle() } catch (_: Exception) {}
    }

    private fun recycleTree(node: AccessibilityNodeInfo) {
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) recycleTree(child)
        }
        try { node.recycle() } catch (_: Exception) {}
    }

    private fun AccessibilityNodeInfo.isEditable(): Boolean = try {
        isEditable
    } catch (_: Exception) {
        className?.toString()?.contains("EditText", ignoreCase = true) == true
    }

    private class IndexCounter {
        private var value = 0
        fun next() = value++
    }
}
