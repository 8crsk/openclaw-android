package com.crsk.openclaw.ui.chat

import org.json.JSONObject

/**
 * Pure formatting helpers for rendering a [com.crsk.openclaw.data.model.ToolCall]'s
 * arguments. Extracts a short, human-readable subtitle and a pretty-printed body so the
 * user can see exactly what the agent is about to do (tap target / typed text / curl).
 *
 * Used in two places: the in-app expandable tool-call chip (post-hoc review) AND the live
 * floating overlay label the user watches while the agent works inside other apps.
 */
object ToolDetailFormatter {

    /** Keys checked in order for a concise one-liner. First non-blank wins. */
    private val SUMMARY_KEYS = listOf("command", "cmd", "url", "target", "label", "selector")

    /** A short subtitle for the collapsed chip, or null if nothing useful is present. */
    fun oneLineSummary(name: String, argumentsJson: String): String? {
        val obj = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return null
        obj.optString("text").takeIf { it.isNotBlank() }?.let { return "\"$it\"" }
        for (key in SUMMARY_KEYS) {
            obj.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** Pretty-print JSON args with 2-space indent; pass through anything that isn't JSON. */
    fun prettyArgs(argumentsJson: String): String =
        runCatching { JSONObject(argumentsJson).toString(2) }.getOrDefault(argumentsJson)

    /**
     * Compact label for the LIVE floating overlay (which the user watches while the agent
     * works in other apps). "name · summary", summary clamped to 40 chars with an ellipsis,
     * or just the name when there is nothing useful to show.
     */
    fun overlayLabel(name: String, argumentsJson: String): String {
        val summary = oneLineSummary(name, argumentsJson) ?: return name
        val clamped = if (summary.length > 40) summary.take(40) + "…" else summary
        return "$name · $clamped"
    }
}
