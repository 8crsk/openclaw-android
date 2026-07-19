package com.crsk.openclaw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.crsk.openclaw.ui.theme.BgBase

/**
 * Flat black backdrop. iMessage doesn't paint ambient glows on the chat field —
 * the pure black is the look. Kept as a wrapper so call sites don't have to change.
 */
@Composable
fun AmbientGlowBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase),
    ) {
        content()
    }
}
