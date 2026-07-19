package com.crsk.openclaw.data.network.ws

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caller surface used by HeartbeatChannel to invoke `chat.history` after a
 * `session.message` broadcast. Separated from WsRpcClient so the existing
 * InFlight-exclusion unit test can keep injecting a minimal fake.
 */
interface WsRpcCaller {
    suspend fun call(method: String, params: JSONObject, timeoutMs: Long): Frame.Response
}

sealed interface HeartbeatOkOutcome {
    data object Suppress : HeartbeatOkOutcome
    data class Keep(val strippedText: String) : HeartbeatOkOutcome
}

data class HeartbeatLastRun(
    val timestampMs: Long,
    val toolCount: Int,
    val ok: Boolean,
    val skipped: Boolean,
)

@Singleton
class HeartbeatChannel @Inject constructor(
    private val ws: WsEventSource,
    private val inFlightRuns: InFlightRunRegistry,
    private val rpc: WsRpcCaller,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private val _chunks = MutableSharedFlow<HeartbeatChunk>(extraBufferCapacity = 64)
    val chunks: SharedFlow<HeartbeatChunk> = _chunks.asSharedFlow()

    private val _lastRun = MutableStateFlow<HeartbeatLastRun?>(null)
    val lastRun: StateFlow<HeartbeatLastRun?> = _lastRun.asStateFlow()

    /** Per-run accumulator keyed by runId (legacy event:"agent" path). */
    private data class RunState(
        val messageId: String,
        var text: StringBuilder = StringBuilder(),
        var toolCount: Int = 0,
        var announced: Boolean = false,
    )

    private val runs = mutableMapOf<String, RunState>()
    private val runsLock = Any()

    /** Last assistant message id we surfaced per heartbeat-bearing sessionKey. Prevents dupes. */
    private val lastSurfacedMessageIdBySession = mutableMapOf<String, String>()
    private val surfacedLock = Any()

    /**
     * Last seen heartbeat event timestamp; openclaw may re-broadcast the same event
     * after a client reconnect, and we don't want a duplicate bubble.
     */
    @Volatile private var lastHeartbeatEventTs: Long = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            ws.events.collect { frame ->
                when (frame.event) {
                    "heartbeat" -> handleHeartbeatEvent(frame.payload)
                    "session.message" -> handleSessionMessage(frame.payload)
                    "agent", "chat", "chat.inject" -> handle(frame.payload)
                    else -> { /* ignore */ }
                }
            }
        }
    }

    /**
     * Handle openclaw's `event:"heartbeat"` broadcast. Sent to ALL connected
     * clients (no subscription required). Payload shape per heartbeat-runner:
     *
     *   {
     *     status: "sent" | "ok-token" | "ok-empty" | "skipped" | "failed",
     *     preview?: string,   // first 200 chars of the agent reply (for "sent")
     *     reason?: string,
     *     durationMs?: number,
     *     channel?: string,
     *     ts: number
     *   }
     */
    private suspend fun handleHeartbeatEvent(payload: JSONObject) {
        val ts = payload.optLong("ts", 0L)
        if (ts > 0L && ts == lastHeartbeatEventTs) {
            Log.d("HeartbeatChannel", "heartbeat event duplicate ts=$ts — ignoring")
            return
        }
        if (ts > 0L) lastHeartbeatEventTs = ts
        val status = payload.optString("status")
        val preview = payload.optString("preview", "").trim()
        val reason = payload.optString("reason", "")
        Log.i("HeartbeatChannel", "heartbeat event status=$status reason=$reason preview='${preview.take(80)}'")
        val now = System.currentTimeMillis()
        // "skipped" with reason="target-none" carries the FULL agent reply in `preview`
        // (openclaw ran the agent successfully; it just had nowhere to deliver because
        // we configured target:"none"). Treat it as if it were "sent" — that's the
        // whole reason we're using target:"none" (we deliver locally via WS, not via
        // an openclaw channel). True deferrals (busy / quiet-hours) have no preview.
        val effectiveStatus = if (status == "skipped" && reason == "target-none" && preview.isNotBlank()) "sent" else status
        Log.i("HeartbeatChannel", "effectiveStatus=$effectiveStatus (raw status=$status reason=$reason)")

        when (effectiveStatus) {
            "sent" -> {
                if (preview.isBlank()) {
                    _lastRun.value = HeartbeatLastRun(now, 0, ok = true, skipped = true)
                    return
                }
                // Content-level dedup: openclaw caches the last "ok-token"/"sent"
                // event and re-broadcasts it on reconnect. Our ts-dedup catches the
                // identical-ts replay but not the case where openclaw refreshes ts
                // while the agent has produced the SAME reply text two ticks in a
                // row (e.g. the model is being lazy and reusing its last output).
                val previewKey = "sent:$preview"
                val alreadySurfaced = synchronized(surfacedLock) {
                    val prev = lastSurfacedMessageIdBySession[previewKey]
                    if (prev != null) true
                    else { lastSurfacedMessageIdBySession[previewKey] = "1"; false }
                }
                if (alreadySurfaced) {
                    Log.d("HeartbeatChannel", "duplicate preview — already surfaced, skipping")
                    _lastRun.value = HeartbeatLastRun(now, 0, ok = true, skipped = false)
                    return
                }
                val msgId = UUID.randomUUID().toString()
                Log.w("HeartbeatChannel", "EMITTING heartbeat bubble msgId=$msgId preview='$preview'")
                _chunks.emit(HeartbeatChunk.NewMessage(msgId, now))
                _chunks.emit(HeartbeatChunk.TextDelta(msgId, preview))
                _chunks.emit(HeartbeatChunk.Done(msgId))
                _lastRun.value = HeartbeatLastRun(now, 0, ok = true, skipped = false)
            }
            "ok-token", "ok-empty" -> {
                // Agent decided nothing needed attention (or replied HEARTBEAT_OK).
                _lastRun.value = HeartbeatLastRun(now, 0, ok = true, skipped = true)
            }
            "skipped" -> {
                // Real defer (busy / quiet-hours / lanes-busy / cron-in-progress).
                _lastRun.value = HeartbeatLastRun(now, 0, ok = true, skipped = true)
            }
            "failed" -> {
                val msgId = UUID.randomUUID().toString()
                _chunks.emit(HeartbeatChunk.NewMessage(msgId, now))
                _chunks.emit(HeartbeatChunk.Error(msgId, "heartbeat failed: ${reason.ifBlank { "unknown" }}"))
                _chunks.emit(HeartbeatChunk.Done(msgId))
                _lastRun.value = HeartbeatLastRun(now, 0, ok = false, skipped = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        synchronized(runsLock) { runs.clear() }
        synchronized(surfacedLock) { lastSurfacedMessageIdBySession.clear() }
    }

    /**
     * Handle openclaw's `session.message` broadcast, which fires whenever any session
     * (chat or heartbeat) accumulates a new message. We only care about sessions whose
     * key indicates an openclaw "main:main" heartbeat session — chat sessions are
     * handled via the existing event:"agent" path through ChatSession.
     */
    private suspend fun handleSessionMessage(payload: JSONObject) {
        val sessionKey = payload.optString("sessionKey").ifBlank {
            payload.optJSONObject("session")?.optString("key")
                ?: payload.optString("key", "")
        }
        if (sessionKey.isBlank()) return
        if (!isHeartbeatSessionKey(sessionKey)) {
            Log.d("HeartbeatChannel", "session.message ignored (not heartbeat): $sessionKey")
            return
        }

        Log.i("HeartbeatChannel", "session.message fired for $sessionKey — fetching chat.history")

        val res = runCatching {
            rpc.call(
                method = "chat.history",
                params = JSONObject().apply {
                    put("sessionKey", sessionKey)
                    put("limit", 5)            // last few messages — small payload
                    put("maxChars", 8_000)
                },
                timeoutMs = 30_000L,
            )
        }.getOrElse {
            Log.w("HeartbeatChannel", "chat.history failed for $sessionKey: ${it.message}")
            return
        }

        if (!res.ok) {
            Log.w("HeartbeatChannel", "chat.history not-ok for $sessionKey: ${res.error?.code} ${res.error?.message}")
            return
        }

        val messages = res.payload?.optJSONArray("messages") ?: JSONArray()
        val lastAssistant = lastAssistantMessage(messages) ?: run {
            Log.d("HeartbeatChannel", "chat.history returned no assistant message for $sessionKey")
            return
        }

        val incomingId = lastAssistant.optString("id").ifBlank {
            // Fallback: hash by timestamp + first 32 chars of text
            "${lastAssistant.optLong("timestamp", 0)}:${extractText(lastAssistant).take(32).hashCode()}"
        }

        val alreadySurfaced = synchronized(surfacedLock) {
            val prev = lastSurfacedMessageIdBySession[sessionKey]
            if (prev == incomingId) true
            else { lastSurfacedMessageIdBySession[sessionKey] = incomingId; false }
        }
        if (alreadySurfaced) {
            Log.d("HeartbeatChannel", "session.message already surfaced id=$incomingId")
            return
        }

        val text = extractText(lastAssistant).trim()
        if (text.isBlank()) {
            Log.d("HeartbeatChannel", "session.message has blank text for $sessionKey — skipping")
            return
        }

        val outcome = classifyOk(text, ackMaxChars = 300)
        Log.i("HeartbeatChannel", "session.message outcome=$outcome text='${text.take(120)}'")

        val now = System.currentTimeMillis()
        when (outcome) {
            HeartbeatOkOutcome.Suppress -> {
                _lastRun.value = HeartbeatLastRun(timestampMs = now, toolCount = 0, ok = true, skipped = true)
            }
            is HeartbeatOkOutcome.Keep -> {
                val msgId = UUID.randomUUID().toString()
                _chunks.emit(HeartbeatChunk.NewMessage(msgId, now))
                _chunks.emit(HeartbeatChunk.TextDelta(msgId, outcome.strippedText))
                _chunks.emit(HeartbeatChunk.Done(msgId))
                _lastRun.value = HeartbeatLastRun(timestampMs = now, toolCount = 0, ok = true, skipped = false)
            }
        }
    }

    /** True iff this session belongs to an openclaw "main"-anchored heartbeat session. */
    private fun isHeartbeatSessionKey(key: String): Boolean {
        // Heartbeat session looks like "agent:main:main" (the persistent agent session).
        // Per-chat sessions look like "agent:main:android-<uuid>".
        return key == "agent:main:main" || key.endsWith(":main") && !key.contains("android-")
    }

    private fun lastAssistantMessage(messages: JSONArray): JSONObject? {
        for (i in (messages.length() - 1) downTo 0) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") == "assistant") return msg
        }
        return null
    }

    /**
     * Extract visible text from a message. Content can be a plain string OR an array of
     * blocks like [{type:"text", text:"..."}, {type:"thinking", thinking:"..."}].
     * We concatenate text blocks only.
     */
    private fun extractText(msg: JSONObject): String {
        val content = msg.opt("content") ?: return ""
        if (content is String) return content
        if (content is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    val t = block.optString("text", "")
                    if (t.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    }
                }
            }
            return sb.toString()
        }
        return ""
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Legacy event:"agent" path. Kept as a fallback in case some openclaw setups
    // still stream heartbeat output as agent frames (e.g. with a different target).
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun handle(payload: JSONObject) {
        val runId = payload.optString("runId").ifBlank { payload.optJSONObject("data")?.optString("runId") ?: "" }
        if (runId.isBlank()) return
        val inFlight = inFlightRuns.isInFlight(runId)
        Log.d("HeartbeatChannel", "handle stream=${payload.optString("stream")} runId=$runId inFlight=$inFlight")
        if (inFlight) return // Chat owns it.

        val data = payload.optJSONObject("data") ?: payload
        val stream = payload.optString("stream")
        val state = synchronized(runsLock) {
            runs.getOrPut(runId) { RunState(messageId = UUID.randomUUID().toString()) }
        }
        when (stream) {
            "assistant" -> {
                val delta = data.optString("delta", "")
                val text = data.optString("text", "")
                val chunkText = when {
                    delta.isNotEmpty() -> delta
                    text.isNotEmpty() && text != state.text.toString() ->
                        text.removePrefix(state.text.toString())
                    else -> ""
                }
                if (chunkText.isEmpty()) return
                if (!state.announced) {
                    state.announced = true
                    _chunks.emit(HeartbeatChunk.NewMessage(state.messageId, System.currentTimeMillis()))
                }
                state.text.append(chunkText)
                _chunks.emit(HeartbeatChunk.TextDelta(state.messageId, chunkText))
            }
            "tool" -> handleTool(state, data)
            "lifecycle" -> handleLifecycle(runId, state, data)
            "error" -> {
                val msg = data.optString("text", "agent error")
                _chunks.emit(HeartbeatChunk.Error(state.messageId, msg))
            }
        }
    }

    private suspend fun handleTool(state: RunState, data: JSONObject) {
        val phase = data.optString("phase")
        val name = data.optString("toolName", data.optString("name", "tool"))
        val callId = data.optString("toolCallId").ifBlank { data.optString("callId") }
            .ifBlank { data.optString("id") }
            .ifBlank { UUID.randomUUID().toString() }
        when (phase) {
            "start" -> {
                state.toolCount += 1
                val args = data.optJSONObject("args")?.toString()
                    ?: data.optJSONObject("input")?.toString()
                    ?: data.optJSONObject("arguments")?.toString() ?: "{}"
                if (!state.announced) {
                    state.announced = true
                    _chunks.emit(HeartbeatChunk.NewMessage(state.messageId, System.currentTimeMillis()))
                }
                _chunks.emit(HeartbeatChunk.ToolStart(state.messageId, callId, name, args))
            }
            "end" -> {
                val out = data.optString("outputText", "").ifBlank {
                    data.optJSONObject("output")?.toString() ?: ""
                }
                val ok = data.optBoolean("ok", !data.has("error"))
                _chunks.emit(HeartbeatChunk.ToolResult(state.messageId, callId, name, ok, out))
            }
            "error" -> {
                val errText = data.optString("text", "tool error")
                _chunks.emit(HeartbeatChunk.ToolResult(state.messageId, callId, name, false, errText))
            }
        }
    }

    private suspend fun handleLifecycle(runId: String, state: RunState, data: JSONObject) {
        val phase = data.optString("phase", "")
        when (phase) {
            "end", "agent_end" -> finalize(runId, state, ok = true)
            "error", "failed", "aborted" -> {
                val txt = data.optString("text", "").ifBlank { data.optString("error", "heartbeat run failed") }
                if (state.announced) _chunks.emit(HeartbeatChunk.Error(state.messageId, txt))
                finalize(runId, state, ok = false)
            }
        }
    }

    private suspend fun finalize(runId: String, state: RunState, ok: Boolean) {
        val outcome = classifyOk(state.text.toString(), ackMaxChars = 300)
        Log.i("HeartbeatChannel", "finalize runId=$runId ok=$ok outcome=$outcome announced=${state.announced} text='${state.text.take(120)}'")
        when (outcome) {
            HeartbeatOkOutcome.Suppress -> {
                if (state.announced) _chunks.emit(HeartbeatChunk.Suppress(state.messageId))
                _lastRun.value = HeartbeatLastRun(
                    timestampMs = System.currentTimeMillis(),
                    toolCount = state.toolCount,
                    ok = ok,
                    skipped = !state.announced,
                )
            }
            is HeartbeatOkOutcome.Keep -> {
                // No way to retro-edit the rendered text here; ChatViewModel reapplies the
                // same strip when handling Done. We emit Done with the trimmed text contract.
                _chunks.emit(HeartbeatChunk.Done(state.messageId))
                _lastRun.value = HeartbeatLastRun(
                    timestampMs = System.currentTimeMillis(),
                    toolCount = state.toolCount,
                    ok = ok,
                    skipped = false,
                )
            }
        }
        synchronized(runsLock) { runs.remove(runId) }
    }

    /** Notes a tick that was skipped server-side (e.g. empty HEARTBEAT.md). */
    fun noteSkippedTick() {
        _lastRun.value = HeartbeatLastRun(
            timestampMs = System.currentTimeMillis(),
            toolCount = 0,
            ok = true,
            skipped = true,
        )
    }

    companion object {
        private const val OK_TOKEN = "HEARTBEAT_OK"

        /**
         * Replicates openclaw's HEARTBEAT_OK rule:
         *   - If trimmed text starts OR ends with HEARTBEAT_OK AND
         *     the remaining stripped text length is <= ackMaxChars, the run is suppressed.
         *   - Otherwise the token is stripped from start/end and the rest kept.
         */
        fun classifyOk(rawText: String, ackMaxChars: Int): HeartbeatOkOutcome {
            val trimmed = rawText.trim()
            val startsWithOk = trimmed.startsWith(OK_TOKEN)
            val endsWithOk = trimmed.endsWith(OK_TOKEN)
            if (!startsWithOk && !endsWithOk) return HeartbeatOkOutcome.Keep(trimmed)
            val stripped = trimmed
                .let { if (startsWithOk) it.removePrefix(OK_TOKEN) else it }
                .let { if (endsWithOk) it.removeSuffix(OK_TOKEN) else it }
                .trim()
            return if (stripped.length <= ackMaxChars) HeartbeatOkOutcome.Suppress
            else HeartbeatOkOutcome.Keep(stripped)
        }
    }
}
