package com.crsk.openclaw.data.network.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRunAborterTest {

    /** Records every RPC; [respond] decides the outcome per method. */
    private class FakeRpc(
        val respond: suspend (method: String) -> Frame.Response = { ok() },
    ) : WsRpcCaller {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): Frame.Response {
            calls += method to params
            return respond(method)
        }
    }

    // The TestScope itself (not backgroundScope): advanceUntilIdle() skips background tasks.
    private fun aborter(rpc: WsRpcCaller, scope: TestScope) =
        ActiveRunAborter(rpc, scope)

    @Test
    fun `abort sends sessions_abort for the tracked run`() = runTest {
        val rpc = FakeRpc()
        val a = aborter(rpc, this)
        a.track("android-s1", "run-1")

        val job = a.abort()
        advanceUntilIdle()

        assertTrue(job!!.isCompleted)
        assertEquals(1, rpc.calls.size)
        val (method, params) = rpc.calls.single()
        assertEquals("sessions.abort", method)
        assertEquals("android-s1", params.getString("key"))
        assertEquals("run-1", params.getString("runId"))
        // schema is additionalProperties:false — nothing else may be sent
        assertEquals(setOf("key", "runId"), params.keys().asSequence().toSet())
    }

    @Test
    fun `abort without a tracked run sends nothing`() = runTest {
        val rpc = FakeRpc()
        val a = aborter(rpc, this)

        assertNull(a.abort())
        advanceUntilIdle()
        assertTrue(rpc.calls.isEmpty())
    }

    @Test
    fun `abort is one-shot - a second Stop does not resend`() = runTest {
        val rpc = FakeRpc()
        val a = aborter(rpc, this)
        a.track("android-s1", "run-1")

        a.abort()
        assertNull(a.abort())
        advanceUntilIdle()
        assertEquals(1, rpc.calls.size)
    }

    @Test
    fun `finished run is not aborted`() = runTest {
        val rpc = FakeRpc()
        val a = aborter(rpc, this)
        a.track("android-s1", "run-1")
        a.finished("android-s1")

        assertNull(a.abort())
        advanceUntilIdle()
        assertTrue(rpc.calls.isEmpty())
    }

    @Test
    fun `late finish of an old turn does not forget the newer run`() = runTest {
        val rpc = FakeRpc()
        val a = aborter(rpc, this)
        a.track("android-old", "run-old")
        a.track("android-new", "run-new")
        a.finished("android-old")

        a.abort()
        advanceUntilIdle()
        assertEquals("run-new", rpc.calls.single().second.getString("runId"))
    }

    @Test
    fun `falls back to chat_abort when sessions_abort is rejected`() = runTest {
        val rpc = FakeRpc { method ->
            if (method == "sessions.abort") err() else ok()
        }
        val a = aborter(rpc, this)

        val acked = a.abortRun(ActiveRunAborter.ActiveRun("android-s1", "run-1"))

        assertTrue(acked)
        assertEquals(listOf("sessions.abort", "chat.abort"), rpc.calls.map { it.first })
        val chatParams = rpc.calls[1].second
        assertEquals("android-s1", chatParams.getString("sessionKey"))
        assertEquals("run-1", chatParams.getString("runId"))
    }

    @Test
    fun `falls back to chat_abort when sessions_abort throws`() = runTest {
        val rpc = FakeRpc { method ->
            if (method == "sessions.abort") throw GatewayHandshakeException("WebSocket not connected")
            ok()
        }
        val a = aborter(rpc, this)

        assertTrue(a.abortRun(ActiveRunAborter.ActiveRun("android-s1", "run-1")))
        assertEquals(listOf("sessions.abort", "chat.abort"), rpc.calls.map { it.first })
    }

    @Test
    fun `failure of both RPCs is swallowed`() = runTest {
        val rpc = FakeRpc { throw IllegalStateException("boom") }
        val a = aborter(rpc, this)

        assertFalse(a.abortRun(ActiveRunAborter.ActiveRun("android-s1", "run-1")))
    }

    @Test
    fun `a gateway that never answers is given up on after the timeout`() = runTest {
        val never = CompletableDeferred<Frame.Response>()
        val rpc = FakeRpc { never.await() }
        val a = ActiveRunAborter(rpc, backgroundScope, timeoutMs = 3_000)

        val acked = a.abortRun(ActiveRunAborter.ActiveRun("android-s1", "run-1"))

        assertFalse(acked)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `abort returns immediately without running the RPC on the caller`() =
        runTest(StandardTestDispatcher()) {
            val rpc = FakeRpc()
            val a = aborter(rpc, this)
            a.track("android-s1", "run-1")

            a.abort()
            // Nothing has run yet — the RPC is queued on the background scope, not done inline.
            assertTrue(rpc.calls.isEmpty())
            advanceUntilIdle()
            assertEquals(1, rpc.calls.size)
        }

    private companion object {
        fun ok() = Frame.Response("id", ok = true, payload = JSONObject().put("ok", true), error = null)
        fun err() = Frame.Response(
            "id", ok = false, payload = null,
            error = Frame.Error("INVALID_REQUEST", "unknown method", null),
        )
    }
}
