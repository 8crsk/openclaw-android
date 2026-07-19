package com.crsk.openclaw.data.network.ws

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatChannelInFlightExclusionTest {

    /**
     * A minimal [WsEventSource] whose event flow is driven entirely from the test.
     * Avoids any dependency on [WsRpcClient] or Android [Context].
     */
    private class FakeWs(private val flow: MutableSharedFlow<Frame.Event>) : WsEventSource {
        override val events: SharedFlow<Frame.Event> = flow.asSharedFlow()
    }

    /** No-op caller. The legacy event:"agent" path doesn't call RPCs. */
    private class NoCalls : WsRpcCaller {
        override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): Frame.Response =
            error("test should not call RPCs in this test")
    }

    private fun agentFrame(
        runId: String,
        stream: String = "assistant",
        delta: String = "x",
    ): Frame.Event {
        val payload = JSONObject().apply {
            put("runId", runId)
            put("stream", stream)
            put("data", JSONObject().put("delta", delta))
        }
        return Frame.Event(event = "agent", payload = payload, seq = null)
    }

    @Test
    fun `frames with in-flight runId are not emitted as heartbeat chunks`() =
        runTest(UnconfinedTestDispatcher()) {
            val eventsFlow = MutableSharedFlow<Frame.Event>(extraBufferCapacity = 64)
            val ws = FakeWs(eventsFlow)

            val registry = InFlightRunRegistry()
            registry.register("chat-1")

            val channel = HeartbeatChannel(ws, registry, NoCalls())
            channel.start()

            val collected = mutableListOf<HeartbeatChunk>()
            val collector = launch(UnconfinedTestDispatcher()) {
                channel.chunks.take(2).toList(collected)
            }

            // This frame is owned by an in-flight chat run — must be dropped.
            eventsFlow.emit(agentFrame("chat-1", delta = "owned"))

            // These belong to a heartbeat run — must be claimed and produce chunks.
            eventsFlow.emit(agentFrame("heart-2", delta = "hi "))
            eventsFlow.emit(agentFrame("heart-2", delta = "world"))

            collector.join()

            assertEquals(2, collected.size)
            assertTrue("first chunk must be NewMessage", collected[0] is HeartbeatChunk.NewMessage)
            assertEquals("hi ", (collected[1] as HeartbeatChunk.TextDelta).text)
        }
}