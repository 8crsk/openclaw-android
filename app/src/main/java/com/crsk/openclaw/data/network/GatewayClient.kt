package com.crsk.openclaw.data.network

import com.crsk.openclaw.data.model.ChatMessage
import com.crsk.openclaw.data.network.ws.ChatSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming chunks from an agent turn. Rough order is:
 *   Lifecycle("agent_start") → (TextDelta | ToolStart → ToolResult)* → Lifecycle("agent_end") → Done
 * AgentError chunks arrive mid-stream and do NOT terminate the flow unless they're fatal — only Done does.
 */
sealed interface ChunkEvent {
    data class TextDelta(val text: String) : ChunkEvent
    data class ToolStart(val callId: String, val name: String, val argsJson: String) : ChunkEvent
    data class ToolResult(val callId: String, val name: String, val ok: Boolean, val text: String) : ChunkEvent
    data class Lifecycle(val phase: String) : ChunkEvent
    data class AgentError(val text: String, val recoverable: Boolean) : ChunkEvent
    /** Token usage emitted by openclaw on lifecycle / assistant frames. Either input/output
     *  may be 0 when only a partial number is reported; ChatViewModel sums them itself. */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ChunkEvent
    data object Done : ChunkEvent
}

@Singleton
class GatewayClient @Inject constructor(
    private val session: ChatSession,
) {
    fun streamChat(messages: List<ChatMessage>): Flow<ChunkEvent> = session.streamChat(messages)
}

class GatewayException(message: String) : Exception(message)
