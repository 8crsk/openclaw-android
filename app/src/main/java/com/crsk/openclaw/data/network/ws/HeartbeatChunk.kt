package com.crsk.openclaw.data.network.ws

/**
 * Events emitted by HeartbeatChannel for runs not owned by a user-initiated chat turn.
 * Mirrors ChunkEvent but tagged with messageId so multiple concurrent heartbeats stay separate.
 */
sealed interface HeartbeatChunk {
    val messageId: String

    data class NewMessage(override val messageId: String, val timestampMs: Long) : HeartbeatChunk
    data class TextDelta(override val messageId: String, val text: String) : HeartbeatChunk
    data class ToolStart(override val messageId: String, val callId: String, val name: String, val argsJson: String) : HeartbeatChunk
    data class ToolResult(override val messageId: String, val callId: String, val name: String, val ok: Boolean, val text: String) : HeartbeatChunk
    data class Done(override val messageId: String) : HeartbeatChunk
    /** Whole bubble should be removed (OK-suppression triggered at end-of-run). */
    data class Suppress(override val messageId: String) : HeartbeatChunk
    data class Error(override val messageId: String, val text: String) : HeartbeatChunk
}
