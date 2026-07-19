package com.crsk.openclaw.data.network.ws

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {
    @Test
    fun parsesConnectChallengeEvent() {
        val json = """{"type":"event","event":"connect.challenge","payload":{"nonce":"abc","ts":123}}"""
        val frame = Frames.parse(json) as Frame.Event
        assertEquals("connect.challenge", frame.event)
        assertEquals("abc", frame.payload.getString("nonce"))
        assertEquals(123L, frame.payload.getLong("ts"))
        assertNull(frame.seq)
    }

    @Test
    fun parsesEventWithSeq() {
        val json = """{"type":"event","event":"tick","payload":{},"seq":42}"""
        val frame = Frames.parse(json) as Frame.Event
        assertEquals(42L, frame.seq)
    }

    @Test
    fun parsesResponseOk() {
        val json = """{"type":"res","id":"r1","ok":true,"payload":{"x":1}}"""
        val frame = Frames.parse(json) as Frame.Response
        assertEquals("r1", frame.id)
        assertTrue(frame.ok)
        assertEquals(1, frame.payload!!.getInt("x"))
        assertNull(frame.error)
    }

    @Test
    fun parsesResponseError() {
        val json = """{"type":"res","id":"r2","ok":false,"error":{"code":"AUTH_TOKEN_MISMATCH","message":"bad","details":{"retryable":true}}}"""
        val frame = Frames.parse(json) as Frame.Response
        assertEquals(false, frame.ok)
        assertEquals("AUTH_TOKEN_MISMATCH", frame.error!!.code)
        assertEquals("bad", frame.error.message)
        assertEquals(true, frame.error.details!!.getBoolean("retryable"))
    }

    @Test
    fun encodesRequest() {
        val req = Frame.Request("r3", "agent", JSONObject().put("message", "hi"))
        val json = JSONObject(Frames.encode(req))
        assertEquals("req", json.getString("type"))
        assertEquals("r3", json.getString("id"))
        assertEquals("agent", json.getString("method"))
        assertEquals("hi", json.getJSONObject("params").getString("message"))
    }

    @Test
    fun parseReturnsNullForUnknownType() {
        assertNull(Frames.parse("""{"type":"weird"}"""))
    }

    @Test
    fun parseReturnsNullForMalformedJson() {
        assertNull(Frames.parse("not json"))
    }
}
