package com.crsk.openclaw.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Builds the vision-first /ui/observe payload: an annotated screenshot plus a legend
 * mapping the single mark id to each interactable node. The model sees red numbered
 * badges and grounds its `act(tap, id=N)` calls against the legend, where N == the badge
 * number == ElementNode.markId == the value tap resolves against — droidrun-portal pattern.
 */
object UiObserveRenderer {

    private const val MAX_LONG_EDGE = 1024
    private const val JPEG_QUALITY = 70
    private const val BOX_STROKE = 4f
    private const val BADGE_PADDING = 6f

    /**
     * Text-only legend. Cheap — no screenshot bytes. Returned by every /agent/act call
     * so the model knows what's on screen without paying for an image.
     */
    fun renderTextOnly(
        marks: List<ElementNode>,
        pkg: String,
        activity: String,
        keyboardVisible: Boolean,
        focusedMarkId: Int?,
    ): JSONObject {
        val legend = JSONArray()
        marks.forEach { n ->
            legend.put(JSONObject().apply {
                put("id", n.markId)
                put("role", n.className.substringAfterLast('.'))
                val display = n.displayText
                if (display.isNotEmpty()) put("text", display.take(40))
                if (n.isEditable) put("edit", true)
                if (n.isScrollable) put("scroll", true)
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("pkg", pkg)
            put("activity", activity)
            put("count", marks.size)
            put("keyboardVisible", keyboardVisible)
            if (focusedMarkId != null) put("focused", focusedMarkId)
            put("legend", legend)
        }
    }

    /**
     * Full vision payload — screenshot + legend. Only used when the model explicitly asks
     * (verb="look"). Significantly more expensive than renderTextOnly.
     *
     * @param shot raw screenshot. May be a hardware bitmap; we copy to ARGB_8888.
     * @param marks interactables in mark order (index == markId), pre-filtered of occluded nodes.
     */
    fun render(
        shot: Bitmap,
        marks: List<ElementNode>,
        pkg: String,
        activity: String,
        keyboardVisible: Boolean,
        focusedMarkId: Int?,
    ): JSONObject {
        // Hardware-backed bitmaps from AccessibilityService.takeScreenshot are read-only;
        // copy to a mutable ARGB_8888 surface we can draw on.
        val mutable = shot.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(mutable)
        val boxPaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = BOX_STROKE; color = Color.RED
        }
        val badgeBg = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.RED }
        val badgeText = Paint().apply {
            isAntiAlias = true; color = Color.WHITE; textSize = 28f; isFakeBoldText = true
        }

        val legend = JSONArray()
        marks.forEach { n ->
            val b = n.bounds
            val left = b.left.coerceIn(0, mutable.width - 1).toFloat()
            val top = b.top.coerceIn(0, mutable.height - 1).toFloat()
            val right = b.right.coerceIn(0, mutable.width).toFloat()
            val bottom = b.bottom.coerceIn(0, mutable.height).toFloat()
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = n.markId.toString()
            val tw = badgeText.measureText(label)
            val th = badgeText.textSize
            canvas.drawRect(left, top, left + tw + BADGE_PADDING * 2, top + th + BADGE_PADDING * 2, badgeBg)
            canvas.drawText(label, left + BADGE_PADDING, top + th + BADGE_PADDING / 2, badgeText)

            legend.put(JSONObject().apply {
                put("id", n.markId)
                put("role", n.className.substringAfterLast('.'))
                val display = n.displayText
                if (display.isNotEmpty()) put("text", display.take(40))
                if (n.isEditable) put("edit", true)
                if (n.isScrollable) put("scroll", true)
            })
        }

        val scaled = downscale(mutable)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (scaled !== mutable) scaled.recycle()
        mutable.recycle()

        return JSONObject().apply {
            put("ok", true)
            put("pkg", pkg)
            put("activity", activity)
            put("screen", JSONArray().apply { put(shot.width); put(shot.height) })
            put("count", marks.size)
            put("keyboardVisible", keyboardVisible)
            if (focusedMarkId != null) put("focused", focusedMarkId)
            put("legend", legend)
            put("screenshot_jpeg", b64)
            put("format", "jpeg")
        }
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= MAX_LONG_EDGE) return src
        val scale = MAX_LONG_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt(),
            (src.height * scale).toInt(),
            true,
        )
    }
}
