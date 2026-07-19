package com.crsk.openclaw.ui.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 4AIs agent orb — three concentric layers (halo, mid ring, core sphere) that breathe.
 * Direct port of the React Native brief: outer halo dims/blooms, core scales 1.0 ↔ 1.06 on
 * the same 4.4s loop. When `active = false` it desaturates to grey for the offline state.
 */
@Composable
fun AIOrb(
    size: Dp = 180.dp,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "orbBreath")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4400, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    val coreScale = 1.0f + 0.06f * breath
    val haloAlpha = 0.55f + 0.45f * breath

    val haloColor = if (active) Color(0xFF0A84FF) else Color(0xFF8E8E93)
    val coreGradient = if (active) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF5AB3FF), Color(0xFF0A84FF), Color(0xFF0040A8)),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFF6E6E76), Color(0xFF3A3A40), Color(0xFF1B1B1F)),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }

    Box(
        modifier = modifier.size(size * 1.5f),
        contentAlignment = Alignment.Center,
    ) {
        // a. Outer halo — 1.5× core diameter, breathing opacity.
        Box(
            modifier = Modifier
                .size(size * 1.5f)
                .graphicsLayer { alpha = haloAlpha }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            haloColor.copy(alpha = 0.33f),
                            haloColor.copy(alpha = 0f),
                        ),
                    )
                ),
        )

        // b. Mid ring — 1.18× core diameter, hairline border in tinted blue.
        Box(
            modifier = Modifier
                .size(size * 1.18f)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = haloColor.copy(alpha = 0.13f),
                    shape = CircleShape,
                ),
        )

        // c. Core sphere — gradient + specular highlight + inner ring + scale breathing.
        Box(
            modifier = Modifier
                .size(size)
                .scale(coreScale)
                .clip(CircleShape)
                .background(coreGradient)
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.18f),
                    shape = CircleShape,
                ),
        ) {
            // Specular highlight overlay — white-fade gradient top-left.
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0f),
                            ),
                            start = Offset(size.value * 0.2f, size.value * 0.1f),
                            end = Offset(size.value * 0.7f, size.value * 0.55f),
                        )
                    ),
            )
        }
    }
}
