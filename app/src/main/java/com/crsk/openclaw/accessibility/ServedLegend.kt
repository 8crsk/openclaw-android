package com.crsk.openclaw.accessibility

/**
 * The marks exactly as last shown to the model (list index == legend `id`). Tap / type /
 * double_tap by id MUST resolve against this frozen snapshot — NEVER a live getSnapshot(),
 * whose marks renumber on every tree rebuild between the model's observe and its next action
 * (stale-grace expiry, window-state changes, scrolls). Pinning the coordinates the model was
 * actually shown is the fix for "tapped id N but a different element was hit".
 *
 * Contract matches agent-device/DroidRun @eN refs: ids are snapshot-bound; the agent gets a
 * fresh legend in every /agent/act response, so it always acts on the most recent one.
 */
class ServedLegend {
    @Volatile private var marks: List<ElementNode> = emptyList()

    fun update(marks: List<ElementNode>) { this.marks = marks }

    /** The element the model saw as `id`, or null if no legend served yet / id out of range. */
    fun resolve(id: Int): ElementNode? = marks.getOrNull(id)
}
