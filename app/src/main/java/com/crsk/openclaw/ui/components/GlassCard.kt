package com.crsk.openclaw.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crsk.openclaw.ui.theme.AccentBlue
import com.crsk.openclaw.ui.theme.BgElevated
import com.crsk.openclaw.ui.theme.Surface as SurfaceColor

/**
 * iOS grouped-list card. Flat opaque fill on the system gray ramp, no border by default,
 * no sheen, no translucency. `selected` adds a 1.5dp accent ring to mark the active choice.
 * Name kept for source compatibility — it's not actually glass anymore.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    selected: Boolean = false,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val fill = if (strong) SurfaceColor else BgElevated
    val ringModifier = if (selected) Modifier.border(1.5.dp, AccentBlue, shape) else Modifier
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Surface(
        modifier = modifier.then(clickModifier).then(ringModifier),
        shape = shape,
        color = fill,
    ) {
        Box(Modifier.padding(contentPadding)) {
            content()
        }
    }
}
