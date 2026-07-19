package com.crsk.openclaw.data.network.ws

import org.json.JSONObject

sealed interface Frame {
    data class Request(val id: String, val method: String, val params: JSONObject) : Frame
    data class Response(
        val id: String,
        val ok: Boolean,
        val payload: JSONObject?,
        val error: Error?,
    ) : Frame
    data class Event(val event: String, val payload: JSONObject, val seq: Long?) : Frame

    data class Error(val code: String, val message: String, val details: JSONObject?)
}

object Frames {
    fun encode(req: Frame.Request): String = JSONObject().apply {
        put("type", "req")
        put("id", req.id)
        put("method", req.method)
        put("params", req.params)
    }.toString()

    fun parse(raw: String): Frame? = try {
        val json = JSONObject(raw)
        when (json.optString("type")) {
            "res" -> {
                val errJson = json.optJSONObject("error")
                Frame.Response(
                    id = json.getString("id"),
                    ok = json.optBoolean("ok", false),
                    payload = json.optJSONObject("payload"),
                    error = errJson?.let {
                        Frame.Error(
                            code = it.optString("code"),
                            message = it.optString("message"),
                            details = it.optJSONObject("details"),
                        )
                    },
                )
            }
            "event" -> Frame.Event(
                event = json.optString("event"),
                payload = json.optJSONObject("payload") ?: JSONObject(),
                seq = if (json.has("seq")) json.optLong("seq") else null,
            )
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
