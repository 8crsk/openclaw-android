package com.crsk.openclaw.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.data.model.ChatMessage
import com.crsk.openclaw.data.model.MessageRole
import com.crsk.openclaw.data.model.ToolCall
import com.crsk.openclaw.data.model.ToolCallStatus
import com.crsk.openclaw.data.network.ChunkEvent
import com.crsk.openclaw.data.network.GatewayClient
import com.crsk.openclaw.data.network.ws.ApprovalChannel
import com.crsk.openclaw.data.network.ws.ApprovalKind
import com.crsk.openclaw.data.network.ws.ApprovalRequest
import com.crsk.openclaw.data.network.ws.ChatSession
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.data.providers.ProviderCatalog
import com.crsk.openclaw.service.GatewayHealthMonitor
import com.crsk.openclaw.service.HealthState
import com.crsk.openclaw.service.ServiceHealth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ChatUiState(
    /** PersistentList so prefix references survive tail mutations — Compose sees a new
     *  top-level reference (invalidates the reader) without copying every element. */
    val messages: PersistentList<ChatMessage> = persistentListOf(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    /** Tokens spent on the most recent (or in-flight) agent turn. Resets on every sendMessage. */
    val turnInputTokens: Long = 0,
    val turnOutputTokens: Long = 0,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gatewayClient: GatewayClient,
    private val preferences: AppPreferences,
    private val gatewayProcess: GatewayProcess,
    private val healthMonitor: GatewayHealthMonitor,
    private val chatSession: ChatSession,
    private val approvalChannel: ApprovalChannel,
    private val heartbeatChannel: com.crsk.openclaw.data.network.ws.HeartbeatChannel,
    private val heartbeatNotifier: com.crsk.openclaw.service.HeartbeatNotifier,
    private val processLifecycle: com.crsk.openclaw.util.AppForegroundState,
    private val overlayBridge: com.crsk.openclaw.overlay.AgentOverlayBridge,
    private val accessibilityHolder: com.crsk.openclaw.accessibility.AccessibilityServiceHolder,
    @dagger.hilt.android.qualifiers.ApplicationContext private val applicationContext: android.content.Context,
) : ViewModel() {

    init {
        approvalChannel.start()
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Cumulative tokens since install (or last reset in Settings). Drives the chat header chip. */
    val lifetimeInputTokens: StateFlow<Long> = preferences.lifetimeInputTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val lifetimeOutputTokens: StateFlow<Long> = preferences.lifetimeOutputTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    init {
        viewModelScope.launch {
            approvalChannel.pending.collect {
                _pendingApproval.value = it
                overlayBridge.onApprovalRequired(it)
            }
        }
    }

    init {
        android.util.Log.i("ChatViewModel", "init: subscribing to heartbeat chunks")
        viewModelScope.launch {
            heartbeatChannel.chunks.collect { chunk ->
                android.util.Log.i("ChatViewModel", "heartbeat chunk: $chunk")
                applyHeartbeatChunk(chunk)
            }
        }
    }

    init {
        viewModelScope.launch {
            overlayBridge.stopRequested.collect { stopGenerating() }
        }
    }

    init {
        viewModelScope.launch {
            overlayBridge.approvalDecision.collect { allow ->
                resolveApproval(allow)
            }
        }
    }

    fun resolveApproval(allow: Boolean) {
        val req = _pendingApproval.value ?: return
        _pendingApproval.value = null
        overlayBridge.onApprovalResolved()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { approvalChannel.resolve(req.id, req.kind, allow) }
        }
    }

    val gatewayRunning: StateFlow<Boolean> = gatewayProcess.status
        .map { it is GatewayStatus.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val healthState: StateFlow<HealthState> = healthMonitor.health

    private var streamJob: Job? = null

    fun sendMessage(content: String) {
        if (content.isBlank() || _state.value.isGenerating) return

        if (!gatewayProcess.isRunning()) {
            _state.value = _state.value.copy(error = "Gateway not running. Start it from Settings.")
            return
        }

        val userMessage = ChatMessage(role = MessageRole.USER, content = content.trim())
        // Soft cap on visible history. The model itself only sees the last few
        // turns (memoryTurns=6 in ChatSession), so trimming older UI bubbles
        // doesn't change semantics — it just keeps LazyColumn slot tables
        // small and `messages.add(...)` PersistentList operations fast on
        // marathon sessions. When we cross the cap we trim back to the keep
        // target so we're not trimming on every single send.
        val prior = _state.value.messages.let { all ->
            if (all.size > MAX_VISIBLE_MESSAGES) {
                val drop = all.size - KEEP_VISIBLE_MESSAGES
                all.subList(drop, all.size).toPersistentList()
            } else all
        }
        val baseMessages: PersistentList<ChatMessage> = prior.add(userMessage)
        val pendingAssistant = ChatMessage(role = MessageRole.ASSISTANT, content = "", isStreaming = true)

        _state.value = _state.value.copy(
            messages = baseMessages.add(pendingAssistant),
            isGenerating = true,
            error = null,
            // Reset per-turn token counters; they accumulate as Usage events arrive.
            turnInputTokens = 0,
            turnOutputTokens = 0,
        )
        overlayBridge.onAgentStarted()

        streamJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            // Insertion-ordered map keyed by callId so chips render in the order the agent invoked them.
            val toolCallsByCallId = LinkedHashMap<String, ToolCall>()
            // Coalesce streaming text: emitting chat state on every TextDelta forces a
            // full LazyColumn recompose per character. Throttle text to ~16 fps; emit
            // structural events (tool start/result, errors, done) immediately.
            var lastEmitMs = 0L
            // Cache the immutable snapshot so unchanged tool-call lists keep the same
            // reference across emissions — MessageBubble stays skippable.
            var cachedToolCalls: PersistentList<ToolCall> = persistentListOf()
            var toolCallsDirty = false

            // Extracted so both the primary path and the 429-fallback path share
            // identical chunk handling without copy-paste drift.
            suspend fun handleChunk(event: ChunkEvent) {
                when (event) {
                    is ChunkEvent.TextDelta -> accumulated.append(event.text)
                    is ChunkEvent.ToolStart -> {
                        toolCallsByCallId[event.callId] = ToolCall(
                            name = event.name,
                            argumentsJson = event.argsJson,
                            status = ToolCallStatus.Running,
                            callId = event.callId,
                        )
                        toolCallsDirty = true
                        overlayBridge.onToolStarted(
                            ToolDetailFormatter.overlayLabel(event.name, event.argsJson)
                        )
                        // Refresh the currentPackage badge based on whichever app is now in focus.
                        val pkg = runCatching {
                            accessibilityHolder.getServiceOrNull()?.getCurrentPackage()
                        }.getOrNull()
                        overlayBridge.setCurrentPackage(pkg?.takeIf { it.isNotBlank() })
                    }
                    is ChunkEvent.ToolResult -> {
                        val existing = toolCallsByCallId[event.callId]
                            ?: ToolCall(name = event.name, callId = event.callId)
                        toolCallsByCallId[event.callId] = existing.copy(
                            resultPreview = event.text.take(500),
                            status = if (event.ok) ToolCallStatus.Done else ToolCallStatus.Failed,
                        )
                        toolCallsDirty = true
                        overlayBridge.onToolFinished()
                    }
                    is ChunkEvent.Lifecycle -> { /* could surface a "thinking…" indicator */ }
                    is ChunkEvent.Usage -> {
                        // Accumulate per-turn + lifetime token counts.
                        val cur = _state.value
                        val newTurnIn = cur.turnInputTokens + event.inputTokens
                        val newTurnOut = cur.turnOutputTokens + event.outputTokens
                        _state.value = cur.copy(
                            turnInputTokens = newTurnIn,
                            turnOutputTokens = newTurnOut,
                        )
                        viewModelScope.launch {
                            preferences.addTokenUsage(event.inputTokens.toLong(), event.outputTokens.toLong())
                        }
                        // Runaway guard: a single user turn going past 250k input tokens is almost
                        // certainly a tool-loop blowup. Kill the stream and tell the user so they
                        // don't accidentally spend ₹100s on one stuck conversation.
                        if (newTurnIn > RUNAWAY_TURN_INPUT_TOKENS) {
                            android.util.Log.w("ChatViewModel",
                                "Runaway turn: $newTurnIn input tokens, cancelling stream")
                            _state.value = _state.value.copy(
                                error = "Stopped — this turn used over ${formatK(newTurnIn)} input tokens. " +
                                    "Try Clear to reset and rephrase.",
                            )
                            streamJob?.cancel()
                            stopGenerating()
                        }
                    }
                    is ChunkEvent.AgentError -> {
                        // Non-fatal — surface to the user but keep streaming.
                        _state.value = _state.value.copy(error = event.text)
                    }
                    is ChunkEvent.Done -> {
                        toolCallsByCallId.forEach { (id, tc) ->
                            if (tc.status == ToolCallStatus.Running) {
                                toolCallsByCallId[id] = tc.copy(status = ToolCallStatus.Done)
                            }
                        }
                        toolCallsDirty = true
                        overlayBridge.onAgentDone()
                    }
                }

                val now = System.currentTimeMillis()
                if (event !is ChunkEvent.TextDelta || now - lastEmitMs >= 60L) {
                    lastEmitMs = now
                    if (toolCallsDirty) {
                        cachedToolCalls = toolCallsByCallId.values.toPersistentList()
                        toolCallsDirty = false
                    }
                    _state.value = _state.value.copy(
                        messages = baseMessages.add(pendingAssistant.copy(
                            content = accumulated.toString(),
                            isStreaming = true,
                            toolCalls = cachedToolCalls,
                        )),
                    )
                }
            }

            val reflection = preferences.reflectionEnabled.first()
            val (providerId, modelId) = selectProvider(
                preferences.apiProvider.value,
                preferences.selectedModel.value,
            )

            try {
                // Safety timeout: if the entire stream takes longer than 35 minutes
                // (slightly above openclaw's 30-min agent timeout), force-close.
                // This prevents the UI from hanging forever if the server drops
                // the connection without sending lifecycle:end.
                val completed = withTimeoutOrNull(35 * 60_000L) {
                    chatSession.streamChat(baseMessages, providerId, modelId, emptyMap(), reflection)
                        .collect { event -> handleChunk(event) }
                }
                if (completed == null) {
                    android.util.Log.w("ChatViewModel", "stream timed out after 35 min")
                }
            } catch (e: Exception) {
                finishWithError(baseMessages, e.message ?: "Failed to get response", accumulated.toString())
            }

            if (_state.value.isGenerating) {
                val sealedToolCalls = toolCallsByCallId.values
                    .map { if (it.status == ToolCallStatus.Running) it.copy(status = ToolCallStatus.Done) else it }
                    .toPersistentList()
                _state.value = _state.value.copy(
                    messages = baseMessages.add(pendingAssistant.copy(
                        content = accumulated.toString(),
                        isStreaming = false,
                        toolCalls = sealedToolCalls,
                    )),
                    isGenerating = false,
                )
            }

        }
    }

    fun stopGenerating() {
        streamJob?.cancel()
        streamJob = null
        val current = _state.value.messages
        val updated = if (current.isNotEmpty() && current.last().isStreaming) {
            current.set(current.lastIndex, current.last().copy(isStreaming = false))
        } else current
        _state.value = _state.value.copy(messages = updated, isGenerating = false)
        overlayBridge.onAgentStopped()
    }

    fun clearChat() {
        stopGenerating()
        chatSession.resetSession()
        // ChatUiState() defaults messages to empty + per-turn counters to 0. Server-side
        // openclaw context for every sessionKey used since the last Clear is fired off
        // asynchronously inside resetSession() (best-effort).
        _state.value = ChatUiState()
    }

    /** Wipe the persistent lifetime token total (Settings → Reset usage). */
    fun resetLifetimeTokens() {
        viewModelScope.launch { preferences.resetLifetimeTokens() }
    }

    private fun finishWithError(baseMessages: PersistentList<ChatMessage>, errorText: String, partial: String = "") {
        _state.value = ChatUiState(
            messages = baseMessages.add(ChatMessage(
                role = MessageRole.ASSISTANT,
                content = partial.ifEmpty { errorText },
                isStreaming = false,
            )),
            isGenerating = false,
            error = errorText,
        )
    }

    private fun applyHeartbeatChunk(chunk: com.crsk.openclaw.data.network.ws.HeartbeatChunk) {
        // Single targeted update on a PersistentList — O(log n) structural-shared tail
        // edit instead of an O(n) toMutableList() + full re-copy per chunk.
        val messages = _state.value.messages
        val idx = messages.indexOfFirst { it.id == chunk.messageId }
        val updated: PersistentList<ChatMessage> = when (chunk) {
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.NewMessage -> {
                if (idx == -1) {
                    messages.add(com.crsk.openclaw.data.model.ChatMessage(
                        id = chunk.messageId,
                        role = com.crsk.openclaw.data.model.MessageRole.ASSISTANT,
                        content = "",
                        timestamp = chunk.timestampMs,
                        isStreaming = true,
                        isHeartbeat = true,
                    ))
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.TextDelta -> {
                if (idx >= 0) messages.set(idx, messages[idx].copy(content = messages[idx].content + chunk.text))
                else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.ToolStart -> {
                if (idx >= 0) {
                    val tc = com.crsk.openclaw.data.model.ToolCall(
                        name = chunk.name, argumentsJson = chunk.argsJson,
                        status = com.crsk.openclaw.data.model.ToolCallStatus.Running,
                        callId = chunk.callId,
                    )
                    messages.set(idx, messages[idx].copy(toolCalls = messages[idx].toolCalls.add(tc)))
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.ToolResult -> {
                if (idx >= 0) {
                    val newToolCalls = messages[idx].toolCalls.map {
                        if (it.callId == chunk.callId) it.copy(
                            resultPreview = chunk.text.take(500),
                            status = if (chunk.ok) com.crsk.openclaw.data.model.ToolCallStatus.Done
                            else com.crsk.openclaw.data.model.ToolCallStatus.Failed,
                        ) else it
                    }.toPersistentList()
                    messages.set(idx, messages[idx].copy(toolCalls = newToolCalls))
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.Done -> {
                if (idx >= 0) {
                    val stripped = messages[idx].content
                        .trim()
                        .removePrefix("HEARTBEAT_OK").trim()
                        .removeSuffix("HEARTBEAT_OK").trim()
                    if (stripped.isNotBlank()) {
                        heartbeatNotifier.notifyMessage(chunk.messageId, stripped)
                    }
                    messages.set(idx, messages[idx].copy(content = stripped, isStreaming = false))
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.Suppress -> {
                if (idx >= 0) messages.removeAt(idx) else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.Error -> {
                if (idx >= 0) {
                    val curMsg = messages[idx]
                    messages.set(idx, curMsg.copy(
                        content = (if (curMsg.content.isBlank()) "" else curMsg.content + "\n\n") +
                            "[heartbeat error] " + chunk.text,
                        isStreaming = false,
                    ))
                } else messages.add(com.crsk.openclaw.data.model.ChatMessage(
                    id = chunk.messageId,
                    role = com.crsk.openclaw.data.model.MessageRole.ASSISTANT,
                    content = "[heartbeat error] " + chunk.text,
                    isHeartbeat = true,
                ))
            }
        }
        if (updated !== messages) {
            _state.value = _state.value.copy(messages = updated)
        }
    }
}

/** Per-user-turn input-token ceiling. Above this we kill the stream — a single message
 *  shouldn't cost more than this. At ~$0.30/M (Gemini Flash) that's $0.08 worst case. */
private const val RUNAWAY_TURN_INPUT_TOKENS: Long = 250_000

/** Soft cap on the visible chat history. When `messages.size` exceeds this we
 *  trim back to KEEP_VISIBLE_MESSAGES so the LazyColumn doesn't accumulate a
 *  giant slot table over multi-day sessions. The model only sees the last few
 *  turns anyway (memoryTurns=6 in ChatSession). */
private const val MAX_VISIBLE_MESSAGES = 500
private const val KEEP_VISIBLE_MESSAGES = 400

private fun formatK(n: Long): String = if (n >= 1000) "${n / 1000}k" else n.toString()

/** Pure routing decision — tested without constructing ChatViewModel.
 *
 *  Resolves the user's Settings selection against the provider catalog:
 *  an unknown provider id falls back to the catalog default provider, and a
 *  model that doesn't belong to the resolved provider falls back to that
 *  provider's default model. Guarantees the returned pair always names a
 *  provider/model combination that refreshMcpConfig will have written into
 *  openclaw's config. */
fun selectProvider(
    providerId: String,
    modelId: String,
): Pair<String, String> {
    val provider = ProviderCatalog.byId(providerId) ?: ProviderCatalog.default
    val model = provider.models.firstOrNull { it.id == modelId } ?: provider.defaultModel
    return provider.id to model.id
}
