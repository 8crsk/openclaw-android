package com.crsk.openclaw.data.network.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatChannelOkSuppressionTest {
    private fun classify(text: String, ackMaxChars: Int = 300): HeartbeatOkOutcome =
        HeartbeatChannel.classifyOk(text, ackMaxChars)

    @Test fun `pure HEARTBEAT_OK is suppressed`() {
        assertEquals(HeartbeatOkOutcome.Suppress, classify("HEARTBEAT_OK"))
    }

    @Test fun `HEARTBEAT_OK with short trailing text under ackMaxChars is suppressed`() {
        assertEquals(HeartbeatOkOutcome.Suppress, classify("HEARTBEAT_OK\n\nnothing to do"))
    }

    @Test fun `text with leading HEARTBEAT_OK and short body is suppressed`() {
        assertEquals(HeartbeatOkOutcome.Suppress, classify("  HEARTBEAT_OK  "))
    }

    @Test fun `text without HEARTBEAT_OK is kept verbatim`() {
        val result = classify("You have 3 unread messages from Mom.")
        assertTrue(result is HeartbeatOkOutcome.Keep)
        assertEquals("You have 3 unread messages from Mom.", (result as HeartbeatOkOutcome.Keep).strippedText)
    }

    @Test fun `text with trailing HEARTBEAT_OK over ackMaxChars keeps token stripped`() {
        val body = "x".repeat(400)
        val result = classify("$body\nHEARTBEAT_OK", ackMaxChars = 300)
        assertTrue(result is HeartbeatOkOutcome.Keep)
        assertEquals(body, (result as HeartbeatOkOutcome.Keep).strippedText.trim())
    }

    @Test fun `text with leading HEARTBEAT_OK strips token when body is long`() {
        val body = "y".repeat(400)
        val result = classify("HEARTBEAT_OK\n$body", ackMaxChars = 300)
        assertTrue(result is HeartbeatOkOutcome.Keep)
        assertEquals(body, (result as HeartbeatOkOutcome.Keep).strippedText.trim())
    }
}
