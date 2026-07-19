package com.crsk.openclaw.accessibility

import kotlinx.coroutines.delay

class GestureController(
    private val holder: AccessibilityServiceHolder,
) {
    private fun service() = holder.getServiceOrNull()
        ?: throw IllegalStateException("Accessibility service not connected")

    suspend fun tap(x: Int, y: Int): Boolean = service().dispatchTap(x, y)

    suspend fun longTap(x: Int, y: Int): Boolean = service().dispatchLongTap(x, y)

    suspend fun tapByText(text: String): TapResult {
        val snap = service().getSnapshot() ?: return TapResult(false, "no UI snapshot available")
        val matches = findAllByText(snap.nodes, text)
        if (matches.isEmpty()) return TapResult(false, "no element matching \"$text\"")
        val target = matches.first()
        val ok = service().dispatchTap(target.centerX, target.centerY)
        return TapResult(ok, if (ok) "tapped \"${target.displayText}\"" else "gesture cancelled")
    }

    suspend fun tapByMark(markId: Int): TapResult {
        val snap = service().getSnapshot() ?: return TapResult(false, "no UI snapshot available")
        val target = snap.marks.getOrNull(markId)
            ?: return TapResult(false, "stale mark id $markId — re-observe")
        val ok = service().dispatchTap(target.centerX, target.centerY)
        return TapResult(ok, if (ok) "tapped mark $markId (${target.displayText})" else "gesture cancelled")
    }

    suspend fun doubleTapByMark(markId: Int): TapResult {
        val snap = service().getSnapshot() ?: return TapResult(false, "no UI snapshot available")
        val target = snap.marks.getOrNull(markId)
            ?: return TapResult(false, "stale mark id $markId — re-observe")
        val ok = service().dispatchDoubleTap(target.centerX, target.centerY)
        return TapResult(ok, if (ok) "double-tapped mark $markId" else "gesture cancelled")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
        service().dispatchSwipe(x1, y1, x2, y2, durationMs)

    suspend fun swipeDirection(direction: String, screenWidth: Int, screenHeight: Int): Boolean {
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        val dist = (minOf(screenWidth, screenHeight) * 0.4).toInt()
        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy + dist, cx, cy - dist)
            "down" -> swipe(cx, cy - dist, cx, cy + dist)
            "left" -> swipe(cx + dist, cy, cx - dist, cy)
            "right" -> swipe(cx - dist, cy, cx + dist, cy)
            else -> false
        }
    }

    suspend fun scrollToFind(
        direction: String,
        text: String,
        maxScrolls: Int = 10,
        screenWidth: Int,
        screenHeight: Int,
    ): ScrollResult {
        for (attempt in 0 until maxScrolls) {
            val snap = service().getSnapshot()
            if (snap != null) {
                val matches = findAllByText(snap.nodes, text)
                if (matches.isNotEmpty()) {
                    return ScrollResult(found = true, scrolls = attempt, element = matches.first())
                }
            }
            swipeDirection(direction, screenWidth, screenHeight)
            delay(SCROLL_SETTLE_MS)
        }
        return ScrollResult(found = false, scrolls = maxScrolls, element = null)
    }

    suspend fun waitForElement(
        text: String,
        gone: Boolean = false,
        timeoutMs: Long = 5000,
    ): WaitResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = service().getSnapshot()
            val found = snap != null && findAllByText(snap.nodes, text).isNotEmpty()
            if (gone && !found) return WaitResult(true, "element gone")
            if (!gone && found) return WaitResult(true, "element appeared")
            delay(POLL_INTERVAL_MS)
        }
        return WaitResult(false, "timeout after ${timeoutMs}ms")
    }

    private fun findAllByText(nodes: List<ElementNode>, text: String): List<ElementNode> {
        val results = mutableListOf<ElementNode>()
        fun recurse(list: List<ElementNode>) {
            for (node in list) {
                if (node.matchesText(text)) results.add(node)
                recurse(node.children)
            }
        }
        recurse(nodes)
        return results
    }

    companion object {
        private const val SCROLL_SETTLE_MS = 500L
        private const val POLL_INTERVAL_MS = 250L
    }
}

data class TapResult(val ok: Boolean, val detail: String)
data class ScrollResult(val found: Boolean, val scrolls: Int, val element: ElementNode?)
data class WaitResult(val ok: Boolean, val detail: String)
