package com.crsk.openclaw.accessibility

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class TraceEntry(
    val seq: Long,
    val ts: Long,
    val verb: String,
    val args: String,
    val ok: Boolean,
    val detail: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq); put("ts", ts); put("verb", verb); put("args", args); put("ok", ok); put("detail", detail)
    }
}

/** Bounded in-memory log of recent agent actions for /ui/trace debugging. Thread-safe; not persisted. */
class ActionTrace(private val cap: Int = 100) {
    private val buf = ArrayDeque<TraceEntry>()
    private val seq = AtomicLong(0)

    @Synchronized fun record(verb: String, args: String, ok: Boolean, detail: String) {
        buf.addLast(TraceEntry(seq.incrementAndGet(), System.currentTimeMillis(), verb, args, ok, detail))
        while (buf.size > cap) buf.removeFirst()
    }

    /** Newest-first, at most [limit]. */
    @Synchronized fun snapshot(limit: Int): List<TraceEntry> =
        buf.reversed().take(limit.coerceIn(1, cap))

    @Synchronized fun toJson(limit: Int): JSONObject = JSONObject().apply {
        put("entries", JSONArray().apply { snapshot(limit).forEach { put(it.toJson()) } })
    }
}
