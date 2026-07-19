package com.crsk.openclaw.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Full-screen transparent overlay the USER watches: draws mark boxes + numbers and a tap
 * ripple. Added by the AccessibilityService via TYPE_ACCESSIBILITY_OVERLAY — no
 * SYSTEM_ALERT_WINDOW. Decoupled from the model screenshot (hidden during a `look` capture).
 * All window ops run on the main thread.
 */
class AgentVisionOverlay(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: OverlayView? = null
    @Volatile var available: Boolean = true; private set

    private class Mark(
        val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String,
    )

    private inner class OverlayView : View(service) {
        @Volatile var marks: List<Mark> = emptyList()
        @Volatile var rippleX = -1f
        @Volatile var rippleY = -1f
        @Volatile var rippleR = 0f
        private val box = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x9900E5FF.toInt()
        }
        private val badgeBg = Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL; color = 0xCC0091EA.toInt()
        }
        private val badgeTx = Paint().apply {
            isAntiAlias = true; color = Color.WHITE; textSize = 26f; isFakeBoldText = true
        }
        private val ripple = Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL; color = 0x553F51B5
        }

        override fun onDraw(canvas: Canvas) {
            for (m in marks) {
                canvas.drawRect(m.left, m.top, m.right, m.bottom, box)
                val tw = badgeTx.measureText(m.label)
                canvas.drawRect(m.left, m.top, m.left + tw + 12f, m.top + 32f, badgeBg)
                canvas.drawText(m.label, m.left + 6f, m.top + 26f, badgeTx)
            }
            if (rippleR > 0f) canvas.drawCircle(rippleX, rippleY, rippleR, ripple)
        }
    }

    private fun ensureAttached() {
        if (view != null) return
        val v = OverlayView()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NO_LIMITS makes the window span the full physical display (under the status/nav
            // bars) so the canvas origin == accessibility getBoundsInScreen origin. Without it
            // the content is inset below the status bar and every box is shifted down/clipped.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess { view = v }
            .onFailure { available = false; Log.w(TAG, "vision overlay addView failed", it) }
    }

    fun showMarks(marks: List<ElementNode>) = main.post {
        if (!available) return@post
        ensureAttached()
        val v = view ?: return@post
        v.marks = marks.map {
            Mark(
                it.bounds.left.toFloat(), it.bounds.top.toFloat(),
                it.bounds.right.toFloat(), it.bounds.bottom.toFloat(),
                it.markId.toString(),
            )
        }
        v.invalidate()
    }

    fun flashTap(x: Int, y: Int) = main.post {
        val v = view ?: return@post
        v.rippleX = x.toFloat(); v.rippleY = y.toFloat(); v.rippleR = 36f
        v.invalidate()
        main.postDelayed({ v.rippleR = 0f; v.invalidate() }, 450L)
    }

    /** Toggle visibility without removing the window (used to keep it out of the model screenshot). */
    fun setHiddenForCapture(hidden: Boolean) = main.post {
        view?.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    fun hide() = main.post {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    companion object { private const val TAG = "AgentVisionOverlay" }
}
