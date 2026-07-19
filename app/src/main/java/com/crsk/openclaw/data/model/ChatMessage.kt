package com.crsk.openclaw.data.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class ToolCallStatus { Pending, Running, Done, Failed }

@Immutable
data class ToolCall(
    val name: String,
    val argumentsJson: String = "",
    val resultPreview: String? = null,
    val status: ToolCallStatus = ToolCallStatus.Pending,
    val callId: String = "",
)

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val toolCalls: PersistentList<ToolCall> = persistentListOf(),
    val isHeartbeat: Boolean = false,
)
