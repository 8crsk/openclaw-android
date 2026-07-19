package com.crsk.openclaw.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF0A84FF)
private val Purple = Color(0xFF5E5CE6)
private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9F0A)
private val Glass = Color(0xFF111111)
private val GlassBorder = Color(0x4D0A84FF)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xFF888888)
private val TextDim = Color(0xFF555555)
private val Divider = Color(0xFF1C1C1E)
private val ChipBg = Color(0xFF2C2C2E)

@Composable
fun OverlayContent(
    state: OverlayUiState,
    anchorOnRight: Boolean,
    onBubbleTap: () -> Unit,
    onStop: () -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onDismissCard: () -> Unit,
) {
    Box(modifier = Modifier.padding(4.dp)) {
        if (state.isExpanded) {
            ExpandedCard(
                state = state,
                onStop = onStop,
                onAllow = onAllow,
                onDeny = onDeny,
                onDismiss = onDismissCard,
                modifier = Modifier.padding(
                    end = if (anchorOnRight) 60.dp else 0.dp,
                    start = if (anchorOnRight) 0.dp else 60.dp,
                ),
            )
        }
        Bubble(
            state = state,
            onTap = onBubbleTap,
            modifier = Modifier.align(
                if (anchorOnRight) Alignment.CenterEnd else Alignment.CenterStart
            ),
        )
    }
}

@Composable
private fun Bubble(
    state: OverlayUiState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (state.currentAction != null) 1.06f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(modifier = modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Blue.copy(alpha = 0.35f), Color.Transparent),
                        radius = 80f,
                    )
                ),
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Blue, Purple)))
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✦",
                color = Color.White,
                fontSize = 20.sp,
            )
        }
        AppBadge(
            packageName = state.currentPackage,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(20.dp),
        )
    }
}

@Composable
private fun AppBadge(packageName: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val icon = remember(packageName) {
        if (packageName.isNullOrBlank()) return@remember null
        runCatching {
            val pm = ctx.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, 48, 48)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Glass)
            .border(1.dp, ChipBg, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(14.dp))
        } else {
            Text(text = "✦", color = Color.White, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ExpandedCard(
    state: OverlayUiState,
    onStop: () -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(150)) + expandIn(tween(200)),
        exit = fadeOut(tween(120)) + shrinkOut(tween(150)),
    ) {
        Column(
            modifier = modifier
                .widthIn(min = 220.dp, max = 240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Glass)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Blue),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "4AIs working",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Red)
                        .clickable(onClick = onStop)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("■ Stop", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Recent actions", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

            val rows = state.recentActions
            rows.forEachIndexed { index, action ->
                val isCurrent = (index == 0 && state.currentAction != null)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        text = if (isCurrent) "→ $action" else "✓ $action",
                        color = if (isCurrent) Blue else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    )
                }
                if (index != rows.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                }
            }

            val approval = state.pendingApproval
            if (approval != null) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Divider)
                        .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    val riskTone = when (approval.riskLevel) {
                        com.crsk.openclaw.data.network.ws.RiskLevel.High -> Red
                        else -> Orange
                    }
                    val riskText = when (approval.riskLevel) {
                        com.crsk.openclaw.data.network.ws.RiskLevel.High ->
                            "⚠ High risk" + (approval.riskCategory?.let { " · $it" } ?: "")
                        com.crsk.openclaw.data.network.ws.RiskLevel.Elevated ->
                            "⚠ Approval needed" + (approval.riskCategory?.let { " · $it" } ?: "")
                        com.crsk.openclaw.data.network.ws.RiskLevel.Low -> "⚠ Approval needed"
                    }
                    Text(
                        riskText,
                        color = riskTone,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(approval.summary, color = TextMuted, fontSize = 10.sp)
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(ChipBg)
                                .clickable(onClick = onDeny)
                                .padding(vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Deny", color = TextPrimary, fontSize = 9.sp)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Blue)
                                .clickable(onClick = onAllow)
                                .padding(vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Allow", color = Color.White, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}
