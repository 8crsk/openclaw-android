package com.crsk.openclaw.ui.chat

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquarePen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsk.openclaw.data.model.ChatMessage
import com.crsk.openclaw.data.model.MessageRole
import com.crsk.openclaw.data.model.ToolCall
import com.crsk.openclaw.data.model.ToolCallStatus
import com.crsk.openclaw.ui.components.AmbientGlowBackdrop
import com.crsk.openclaw.ui.components.IMessageBubbleShape
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import com.crsk.openclaw.ui.theme.AccentBlue
import com.crsk.openclaw.ui.theme.BgBase
import com.crsk.openclaw.ui.theme.BgElevated
import com.crsk.openclaw.ui.theme.BorderHairline
import com.crsk.openclaw.ui.theme.BubbleReceived
import com.crsk.openclaw.ui.theme.BubbleSent
import com.crsk.openclaw.ui.theme.Danger
import com.crsk.openclaw.ui.theme.Success
import com.crsk.openclaw.ui.theme.Surface
import com.crsk.openclaw.ui.theme.TextMuted
import com.crsk.openclaw.ui.theme.TextSecondary
import com.crsk.openclaw.ui.theme.Warning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rough working-context budget the gauge measures against. Not the model's hard limit —
 *  a practical "how much has this conversation grown" meter, labeled as an estimate. */
private const val CONTEXT_TOKEN_BUDGET = 32_000

/** Messages within this gap render as one visual group (shared by the timestamp divider). */
private const val GROUP_GAP_MS = 3 * 60_000L

/** Show a centered timestamp divider when the gap from the previous message exceeds this. */
private const val TIME_DIVIDER_GAP_MS = 10 * 60_000L

/** Suggestion card displayed in the empty-state horizontal carousel. Each card has a
 *  tinted gradient background + lucide icon + title + subtitle and inserts a prompt
 *  into the composer on tap. */
private data class SuggestionCard(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val gradientStart: Color,
    val borderColor: Color,
)

private val SUGGESTION_CARDS = listOf(
    SuggestionCard(
        title = "Brainstorm",
        subtitle = "Spark new ideas.",
        prompt = "Brainstorm ideas for ",
        icon = Icons.Filled.Lightbulb,
        gradientStart = Color(0xFF1E1A2E),
        borderColor = Color(0x59BF5AF2),
    ),
    SuggestionCard(
        title = "Write",
        subtitle = "Drafts, emails, posts.",
        prompt = "Help me write ",
        icon = Icons.Filled.Edit,
        gradientStart = Color(0xFF0E1B2E),
        borderColor = Color(0x590A84FF),
    ),
    SuggestionCard(
        title = "Explain",
        subtitle = "Make hard things easy.",
        prompt = "Explain ",
        icon = Icons.Filled.School,
        gradientStart = Color(0xFF1E1306),
        borderColor = Color(0x4DFF9F0A),
    ),
    SuggestionCard(
        title = "Code",
        subtitle = "Snippets, reviews, regex.",
        prompt = "Write code that ",
        icon = Icons.Filled.Code,
        gradientStart = Color(0xFF0E1F1A),
        borderColor = Color(0x4D30D158),
    ),
    SuggestionCard(
        title = "Translate",
        subtitle = "Fluent in 100+ languages.",
        prompt = "Translate this to ",
        icon = Icons.Filled.Translate,
        gradientStart = Color(0xFF1F0E18),
        borderColor = Color(0x4DFF375F),
    ),
)

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gatewayRunning by viewModel.gatewayRunning.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()
    val lifetimeIn by viewModel.lifetimeInputTokens.collectAsStateWithLifecycle()
    val lifetimeOut by viewModel.lifetimeOutputTokens.collectAsStateWithLifecycle()

    val view = LocalView.current
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    // Receive haptic: a gentle tap when the agent's reply finishes streaming.
    var wasGenerating by remember { mutableStateOf(false) }
    LaunchedEffect(state.isGenerating) {
        if (wasGenerating && !state.isGenerating) {
            view.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.KEYBOARD_TAP
            )
        }
        wasGenerating = state.isGenerating
    }

    pendingApproval?.let { req ->
        ApprovalDialog(
            request = req,
            onAllow = { viewModel.resolveApproval(true) },
            onDeny = { viewModel.resolveApproval(false) },
        )
    }

    if (showClearConfirm) {
        ClearContextDialog(
            onConfirm = { viewModel.clearChat(); showClearConfirm = false },
            onDismiss = { showClearConfirm = false },
        )
    }

    AmbientGlowBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            ChatTopBar(
                gatewayRunning = gatewayRunning,
                onNewChat = { if (state.messages.isNotEmpty()) showClearConfirm = true },
            )

            if (state.messages.isNotEmpty()) {
                ContextBar(
                    messages = state.messages,
                    turnInputTokens = state.turnInputTokens,
                    turnOutputTokens = state.turnOutputTokens,
                    lifetimeInputTokens = lifetimeIn,
                    lifetimeOutputTokens = lifetimeOut,
                    onClear = { showClearConfirm = true },
                )
            }

            if (!gatewayRunning) GatewayStoppedBanner()
            state.error?.let { ErrorBanner(it) }

            val listState = rememberLazyListState()
            LaunchedEffect(state.messages.size) {
                if (state.messages.isNotEmpty()) {
                    listState.animateScrollToItem(state.messages.size - 1)
                }
            }
            LaunchedEffect(state.isGenerating) {
                if (state.isGenerating && state.messages.isNotEmpty()) {
                    listState.scrollToItem(state.messages.size - 1)
                }
            }
            LaunchedEffect(Unit) {
                com.crsk.openclaw.ui.MainActivity.scrollRequests.collect { messageId ->
                    val idx = state.messages.indexOfFirst { it.id == messageId }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }

            // Swipe-to-reveal timestamps: dragging the conversation left slides the whole
            // list over, exposing each bubble's time docked at the right edge (iMessage).
            val density = LocalDensity.current
            // Reveal distance must exceed the timestamp width so the labels fully tuck
            // away at rest and sit at the content edge when fully swiped in.
            val maxRevealPx = with(density) { 72.dp.toPx() }
            val reveal = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()

            val hazeState = remember { HazeState() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (reveal.value + dragAmount).coerceIn(-maxRevealPx, 0f)
                                scope.launch { reveal.snapTo(next) }
                            },
                            onDragEnd = {
                                scope.launch { reveal.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            },
                            onDragCancel = {
                                scope.launch { reveal.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            },
                        )
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = hazeState)
                        .graphicsLayer { translationX = reveal.value }
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    // Reserve space at the bottom so the floating, blurred composer
                    // doesn't cover the last message.
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                ) {
                    if (state.messages.isEmpty()) {
                        item {
                            EmptyState(
                                onSuggest = viewModel::sendMessage,
                                gatewayRunning = gatewayRunning,
                            )
                        }
                    }
                    itemsIndexed(
                        items = state.messages,
                        key = { _, m -> m.id },
                        // Splitting user vs assistant vs heartbeat bubbles into distinct
                        // slot-table buckets lets Compose reuse layouts within a type and
                        // skip recomposing across types.
                        contentType = { _, m -> if (m.isHeartbeat) "hb" else m.role.name },
                    ) { index, message ->
                        val prev = state.messages.getOrNull(index - 1)
                        val next = state.messages.getOrNull(index + 1)

                        val showDivider = prev == null ||
                            (message.timestamp - prev.timestamp) > TIME_DIVIDER_GAP_MS
                        val isFirstInGroup = showDivider || prev?.role != message.role ||
                            prev.isHeartbeat != message.isHeartbeat ||
                            (message.timestamp - (prev?.timestamp ?: 0L)) > GROUP_GAP_MS
                        val isLastInGroup = next == null || next.role != message.role ||
                            next.isHeartbeat != message.isHeartbeat ||
                            (next.timestamp - message.timestamp) > GROUP_GAP_MS
                        val isLastOverall = next == null
                        val showDelivered = isLastOverall &&
                            message.role == MessageRole.USER && !state.isGenerating

                        if (showDivider) TimeDivider(message.timestamp)
                        if (isFirstInGroup && !showDivider && index > 0) Spacer(Modifier.height(6.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            MessageBubble(
                                message = message,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                showDelivered = showDelivered,
                            )
                            // Docked timestamp, parked just off the right edge until swiped in.
                            // Timestamp is immutable per message — compute once per item slot.
                            val clockText = remember(message.id) { formatClock(message.timestamp) }
                            Text(
                                text = clockText,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .offset(x = 72.dp),
                            )
                        }
                    }
                }

                val onSend = remember { { msg: String -> viewModel.sendMessage(msg) } }
                val onStop = remember { viewModel::stopGenerating }
                ChatInput(
                    isGenerating = state.isGenerating,
                    onSend = onSend,
                    onStop = onStop,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = BgBase,
                                tint = null,
                                blurRadius = 24.dp,
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun ChatTopBar(gatewayRunning: Boolean, onNewChat: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand wordmark — tight tracking, large for an iOS title look.
        Text(
            text = "4AIs",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(10.dp))
        // Status pill — surface chip with a colored dot + label.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF161618))
                .border(1.dp, BorderHairline, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (gatewayRunning) Success else Warning),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (gatewayRunning) "Online" else "Gateway offline",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        // Right cluster: history + new chat (two 36dp round buttons).
        TopIconButton(icon = Lucide.Clock, contentDescription = "History", onClick = onNewChat)
        Spacer(Modifier.width(10.dp))
        TopIconButton(icon = Lucide.SquarePen, contentDescription = "New conversation", onClick = onNewChat)
    }
}

@Composable
private fun TopIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF161618))
            .border(1.dp, BorderHairline, CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Token meter showing REAL counts reported by openclaw's lifecycle/usage events.
 *  Top line: this-turn input/output tokens. Bottom line: lifetime total. Bar shows
 *  the turn's input-token fraction of a budget so you can see when one turn is
 *  blowing up. */
@Composable
private fun ContextBar(
    messages: List<ChatMessage>,
    turnInputTokens: Long,
    turnOutputTokens: Long,
    lifetimeInputTokens: Long,
    lifetimeOutputTokens: Long,
    onClear: () -> Unit,
) {
    // If no usage events ever arrived (free Nemotron upstream often omits usage),
    // fall back to the visible-conversation estimate so the bar isn't always 0.
    val estimatedTokens = remember(messages) { estimateTokens(messages) }
    val turnTokens = (turnInputTokens + turnOutputTokens).toInt()
    val displayTurn = if (turnTokens > 0) turnTokens else estimatedTokens
    val fraction = (displayTurn.toFloat() / CONTEXT_TOKEN_BUDGET).coerceIn(0f, 1f)
    val barColor = when {
        fraction > 0.95f -> Danger
        fraction > 0.80f -> Warning
        else -> AccentBlue
    }
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "ctxFill",
    )
    val lifetime = lifetimeInputTokens + lifetimeOutputTokens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "This turn",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.width(6.dp))
                val turnLabel = if (turnTokens > 0) {
                    "${formatTokens(turnInputTokens.toInt())} in · ${formatTokens(turnOutputTokens.toInt())} out"
                } else {
                    "~${formatTokens(estimatedTokens)} (est.)"
                }
                Text(
                    text = turnLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                if (lifetime > 0L) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "· lifetime ${formatTokens(lifetime.toInt())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Surface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onClear) {
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelLarge,
                color = AccentBlue,
            )
        }
    }
}

@Composable
private fun ClearContextDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        title = { Text("Clear conversation?", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Text(
                "This clears the chat and resets the agent's context. This can't be undone.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Clear", color = Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AccentBlue) }
        },
    )
}

@Composable
private fun TimeDivider(timestamp: Long) {
    val label = remember(timestamp) { formatDivider(timestamp) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = TextMuted,
        )
    }
}

@Composable
private fun GatewayStoppedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Danger.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Gateway not running. Start it from Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = Danger,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Danger.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = Danger,
        )
    }
}

@Composable
private fun EmptyState(onSuggest: (String) -> Unit, gatewayRunning: Boolean = true) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // ── Hero AI orb ──────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            com.crsk.openclaw.ui.components.AIOrb(size = 180.dp, active = gatewayRunning)
        }

        Spacer(Modifier.height(32.dp))

        // ── Time-aware greeting ──────────────────────────────────────
        val cal = remember { java.util.Calendar.getInstance() }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val subhead = when {
            hour < 5 -> "Still up?"
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            hour < 22 -> "Good evening."
            else -> "Good night."
        }

        Text(
            text = subhead,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "How can I help\nyou today?",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            lineHeight = androidx.compose.ui.unit.TextUnit(46f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (gatewayRunning) "Your private agent is listening."
                else "Start the gateway in Settings to begin.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(28.dp))

        // ── Overline + horizontal suggestion carousel ────────────────
        Text(
            text = "SUGGESTIONS",
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))

        val scrollState = androidx.compose.foundation.rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(Modifier.horizontalScroll(scrollState)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SUGGESTION_CARDS.forEach { card ->
                SuggestionTile(card = card, onTap = { onSuggest(card.prompt) })
            }
        }
    }
}

@Composable
private fun SuggestionTile(card: SuggestionCard, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 160.dp, height = 180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(card.gradientStart, Color(0xFF0E0E10)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
            .border(1.dp, card.borderColor, RoundedCornerShape(20.dp))
            .pointerInput(card.title) { detectTapGestures(onTap = { onTap() }) }
            .padding(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Icon tile — 38×38 rounded-12 with hairline border, white icon.
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x0FFFFFFF))
                    .border(1.dp, card.borderColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = card.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    showDelivered: Boolean,
) {
    val isUser = message.role == MessageRole.USER
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current

    // Single Animatable drives the iMessage pop-in. Once it's reached 1f it's static —
    // no more frames are scheduled, unlike three animateFloatAsState observers that
    // each keep listening on the snapshot system. rememberSaveable persists "already
    // played" across rotation, so we don't replay it.
    var entered by rememberSaveable(message.id) { mutableStateOf(false) }
    val intro = remember(message.id) { Animatable(if (entered) 1f else 0f) }
    LaunchedEffect(message.id) {
        if (!entered) {
            intro.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
            )
            entered = true
        }
    }
    val introValue = intro.value
    val scale = 0.85f + 0.15f * introValue
    val alpha = introValue.coerceIn(0f, 1f)
    val translationY = 24f * (1f - introValue)

    // Long-press → lift + iOS-style Copy menu.
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    val lift by animateFloatAsState(
        targetValue = if (menuOpen) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "bubbleLift",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                this.translationY = translationY
                this.scaleX = scale
                this.scaleY = scale
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (isUser) 1f else 0f,
                    pivotFractionY = 1f,
                )
            },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (message.isHeartbeat) {
            HeartbeatChip(message.timestamp)
            Spacer(Modifier.height(4.dp))
        }
        if (message.content.isNotEmpty() || message.isStreaming) {
            val shape = bubbleShape(isUser, isLastInGroup)
            Box {
                Box(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                        .clip(shape)
                        .background(if (isUser) BubbleSent else BubbleReceived)
                        .pointerInput(message.id) {
                            detectTapGestures(
                                onLongPress = {
                                    if (message.content.isNotEmpty()) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        menuOpen = true
                                    }
                                },
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    if (message.content.isEmpty() && message.isStreaming) {
                        TypingIndicator()
                    } else {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = {
                            Icon(Lucide.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            clipboard.setText(AnnotatedString(message.content))
                            menuOpen = false
                        },
                    )
                }
            }
        }
        if (!isUser && message.toolCalls.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.toolCalls.forEach { tc ->
                    key(tc.callId) { ToolCallChip(tc) }
                }
            }
        }
        if (showDelivered) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Delivered",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

/** iMessage bubble shape. Tail draws only on the last bubble in a consecutive group. */
private fun bubbleShape(isUser: Boolean, isLast: Boolean): IMessageBubbleShape =
    IMessageBubbleShape(isUser = isUser, hasTail = isLast)

/** Three dots that bob and fade, like iMessage's "typing" indicator. Gray, in a gray bubble. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(14.dp),
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 540, delayMillis = i * 140, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dotAlpha$i",
            )
            val bob by transition.animateFloat(
                initialValue = 0f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 540, delayMillis = i * 140, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dotBob$i",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer { this.translationY = bob }
                    .clip(CircleShape)
                    .background(TextSecondary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun HeartbeatChip(timestamp: Long) {
    val fmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Lucide.Clock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Heartbeat · " + fmt.format(Date(timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun ChatInput(
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    // rememberSaveable so a half-typed message survives rotation / process death.
    var text by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Message", color = TextMuted) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank() && !isGenerating) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onSend(text); text = ""
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                    cursorColor = AccentBlue,
                ),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isGenerating) {
            FilledIconButton(
                onClick = onStop,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Surface,
                    contentColor = TextSecondary,
                ),
            ) {
                Icon(Lucide.Square, "Stop generating", modifier = Modifier.size(16.dp))
            }
        } else {
            // Send button springs in only when there's something to send (iMessage).
            val sendScale by animateFloatAsState(
                targetValue = if (text.isNotBlank()) 1f else 0.6f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
                label = "sendScale",
            )
            FilledIconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSend(text); text = ""
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale },
                shape = CircleShape,
                enabled = text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = AccentBlue,
                    contentColor = Color.White,
                    disabledContainerColor = Surface,
                    disabledContentColor = TextMuted,
                ),
            ) {
                Icon(Lucide.ArrowUp, "Send", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── helpers ──────────────────────────────────────────────────────────────────

private fun estimateTokens(messages: List<ChatMessage>): Int {
    var chars = 0
    for (m in messages) {
        chars += m.content.length
        for (tc in m.toolCalls) {
            chars += tc.argumentsJson.length + (tc.resultPreview?.length ?: 0)
        }
    }
    return chars / 4 // rough chars→tokens heuristic
}

private fun formatTokens(tokens: Int): String =
    if (tokens >= 1000) String.format(Locale.US, "%.1fk", tokens / 1000f) else tokens.toString()

private fun formatClock(timestamp: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))

private fun formatDivider(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val isToday = sameDay.format(Date(now)) == sameDay.format(Date(timestamp))
    val pattern = if (isToday) "h:mm a" else "MMM d, h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
