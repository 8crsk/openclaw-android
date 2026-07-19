package com.crsk.openclaw.accessibility

/** Result of comparing two legend key sets. Lists preserve the "after" / "before" order. */
data class LegendDelta(val appeared: List<String>, val disappeared: List<String>)

/**
 * Lightweight set-diff over legend keys ("role|text"). No node-identity tracking: just tells
 * the model what (role,text) entries appeared/disappeared after an action — a cheap, reliable
 * "your action changed X" signal (agent-device diff-snapshot pattern).
 */
object LegendDiff {
    fun diff(before: List<String>?, after: List<String>): LegendDelta {
        val beforeSet = before?.toSet() ?: emptySet()
        val afterSet = after.toSet()
        val appeared = after.filter { it !in beforeSet }
        val disappeared = (before ?: emptyList()).filter { it !in afterSet }
        return LegendDelta(appeared, disappeared)
    }
}
