package com.crsk.openclaw.accessibility

/**
 * Assigns a single contiguous mark id (0,1,2,…) to every visible interactable node in
 * pre-order, mutating ElementNode.markId, and returns the marks list (index == markId).
 * This is the ONE id space: badge label == legend `id` == the value `tap id=N` resolves
 * against. Occluded nodes are skipped so the model never targets a hidden element.
 */
object MarkAssigner {
    fun assign(tree: List<ElementNode>): List<ElementNode> {
        val marks = mutableListOf<ElementNode>()
        fun walk(nodes: List<ElementNode>) {
            for (n in nodes) {
                if (n.isInteractable && !n.occluded) {
                    n.markId = marks.size
                    marks.add(n)
                }
                walk(n.children)
            }
        }
        walk(tree)
        return marks
    }
}
