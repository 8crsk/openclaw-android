package com.crsk.openclaw.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * iMessage bubble shape. A rounded rectangle plus a small bezier tail at the bottom
 * corner on the speaker's side. When [hasTail] is false the bubble is just a rounded
 * rect — used for the non-last bubbles in a consecutive group, matching how iOS only
 * draws the tail on the final bubble in a stack.
 *
 * The tail is drawn outside the message body, so the parent should reserve a small
 * amount of horizontal padding on the speaker side to avoid clipping at screen edge.
 */
class IMessageBubbleShape(
    private val isUser: Boolean,
    private val hasTail: Boolean,
    private val cornerRadius: Dp = 18.dp,
    private val tailWidth: Dp = 8.dp,
    private val tailHeight: Dp = 10.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { cornerRadius.toPx() }.coerceAtMost(size.minDimension / 2f)
        val tw = with(density) { tailWidth.toPx() }
        val th = with(density) { tailHeight.toPx() }

        val w = size.width
        val h = size.height
        val path = Path()

        if (isUser) {
            // Sent bubble: tail at bottom-right.
            path.moveTo(r, 0f)
            path.lineTo(w - r, 0f)
            path.cubicTo(w, 0f, w, 0f, w, r)
            path.lineTo(w, h - r)
            if (hasTail) {
                // From bubble bottom-right corner, curl out to the tail tip, then
                // curl back into the body just left of the corner. The control points
                // are placed so the tail bulges convex on the outside and concave on
                // the inside, matching the SF iMessage tail.
                path.cubicTo(
                    w, h - r * 0.35f,
                    w + tw * 0.55f, h - th * 0.05f,
                    w + tw, h,
                )
                path.cubicTo(
                    w - tw * 0.15f, h,
                    w - r * 0.45f, h - th * 0.10f,
                    w - r, h,
                )
            } else {
                path.cubicTo(w, h, w, h, w - r, h)
            }
            path.lineTo(r, h)
            path.cubicTo(0f, h, 0f, h, 0f, h - r)
            path.lineTo(0f, r)
            path.cubicTo(0f, 0f, 0f, 0f, r, 0f)
        } else {
            // Received bubble: tail at bottom-left.
            path.moveTo(r, 0f)
            path.lineTo(w - r, 0f)
            path.cubicTo(w, 0f, w, 0f, w, r)
            path.lineTo(w, h - r)
            path.cubicTo(w, h, w, h, w - r, h)
            path.lineTo(r, h)
            if (hasTail) {
                path.cubicTo(
                    r * 0.45f, h,
                    tw * 0.15f, h,
                    -tw, h,
                )
                path.cubicTo(
                    -tw * 0.55f, h - th * 0.05f,
                    0f, h - r * 0.35f,
                    0f, h - r,
                )
            } else {
                path.cubicTo(0f, h, 0f, h, 0f, h - r)
            }
            path.lineTo(0f, r)
            path.cubicTo(0f, 0f, 0f, 0f, r, 0f)
        }
        path.close()
        return Outline.Generic(path)
    }
}
