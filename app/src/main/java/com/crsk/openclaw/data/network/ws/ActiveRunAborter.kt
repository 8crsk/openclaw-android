package com.crsk.openclaw.data.network.ws

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers the chat turn's in-flight openclaw `agent` run and tells the gateway to stop it.
 *
 * Cancelling the client-side stream (Stop button, runaway guard) only stops us *listening*;
 * the run keeps executing on the gateway and keeps driving the phone. [abort] sends a
 * best-effort abort RPC so the gateway actually stops it.
 *
 * openclaw 2026.5.12 protocol (verified against the published package's dist/):
 * - an `agent` run's `runId` IS the request's `idempotencyKey`;
 * - `sessions.abort {key, runId}` resolves the session from the run's abort controller, so it
 *   works even though openclaw canonicalizes the sessionKey we sent;
 * - `chat.abort {sessionKey, runId}` requires the canonical key to match, so it is only a
 *   fallback for gateways where `sessions.abort` fails.
 */
class ActiveRunAborter(
    private val rpc: WsRpcCaller,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = ABORT_TIMEOUT_MS,
) {
    data class ActiveRun(val sessionKey: String, val runId: String)

    private val active = AtomicReference<ActiveRun?>(null)

    /** The run that [abort] would target right now, if any. */
    fun current(): ActiveRun? = active.get()

    /** Call right before firing the `agent` RPC for a turn. Replaces any previous run. */
    fun track(sessionKey: String, runId: String) {
        if (sessionKey.isBlank() || runId.isBlank()) return
        active.set(ActiveRun(sessionKey, runId))
    }

    /** Call when the turn ends on its own. Only clears if [sessionKey] is still the tracked run,
     *  so a late close of an old turn can't forget a newer one. */
    fun finished(sessionKey: String) {
        active.updateAndGet { if (it?.sessionKey == sessionKey) null else it }
    }

    /**
     * Fire-and-forget: forgets the tracked run and asks the gateway to abort it on [scope].
     * Never blocks or suspends the caller (safe from the main thread). Returns the job doing
     * the RPC, or null when there is no tracked run.
     */
    fun abort(): Job? {
        val run = active.getAndSet(null) ?: return null
        return scope.launch { abortRun(run) }
    }

    /** True if the gateway acknowledged the abort; false on failure/timeout. Never throws. */
    internal suspend fun abortRun(run: ActiveRun): Boolean {
        val ok = withTimeoutOrNull(timeoutMs) {
            val viaSessions = attempt(
                "sessions.abort",
                JSONObject().put("key", run.sessionKey).put("runId", run.runId),
            )
            viaSessions || attempt(
                "chat.abort",
                JSONObject().put("sessionKey", run.sessionKey).put("runId", run.runId),
            )
        } ?: false
        if (ok) {
            Log.i(TAG, "aborted agent run ${run.runId}")
        } else {
            Log.w(TAG, "abort of agent run ${run.runId} not acknowledged (best-effort)")
        }
        return ok
    }

    private suspend fun attempt(method: String, params: JSONObject): Boolean = try {
        rpc.call(method, params, timeoutMs).ok
    } catch (t: Throwable) {
        // A per-call timeout is just a failed attempt; but if WE were cancelled (overall
        // timeout / scope shutdown), stop instead of trying the fallback.
        if (t is CancellationException) currentCoroutineContext().ensureActive()
        Log.d(TAG, "$method failed: ${t.message}")
        false
    }

    companion object {
        private const val TAG = "ActiveRunAborter"
        const val ABORT_TIMEOUT_MS = 3_000L
    }
}
