package com.crsk.openclaw.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.crsk.openclaw.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Owns the overlay [ComposeView] and its [WindowManager] attachment. Started by
 * [com.crsk.openclaw.service.GatewayService.onCreate], stopped by `onDestroy`.
 */
class OverlayManager(
    private val context: Context,
    private val bridge: AgentOverlayBridge,
    private val preferences: AppPreferences,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var visibilityJob: Job? = null
    private var stateJob: Job? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var composeView: ComposeView? = null
    private var attached: Boolean = false

    private var bubbleX by androidx.compose.runtime.mutableIntStateOf(-1)
    private var bubbleY by androidx.compose.runtime.mutableIntStateOf(-1)

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        visibilityJob = scope.launch {
            val enabledFlow = preferences.overlayEnabled
            val visibleFlow = bridge.overlayState.map { it.visible }
            kotlinx.coroutines.flow.combine(enabledFlow, visibleFlow) { enabled, visible ->
                enabled && visible
            }
                .distinctUntilChanged()
                .collect { shouldShow ->
                    updateAttachment(shouldShow)
                }
        }
    }

    fun stop() {
        visibilityJob?.cancel(); visibilityJob = null
        stateJob?.cancel(); stateJob = null
        if (attached) {
            runCatching { windowManager.removeView(composeView) }
            attached = false
        }
        composeView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        scope.cancel()
    }

    private suspend fun updateAttachment(visible: Boolean) {
        if (visible && !attached) {
            val (savedX, savedY) = withContext(Dispatchers.IO) {
                preferences.overlayBubbleX.first() to preferences.overlayBubbleY.first()
            }
            bubbleX = savedX
            bubbleY = savedY
            attachOverlay()
        } else if (!visible && attached) {
            detachOverlay()
        }
    }

    private fun attachOverlay() {
        if (!OverlayPermissionHelper.hasPermission(context)) {
            Log.w(TAG, "attach skipped: SYSTEM_ALERT_WINDOW not granted")
            return
        }
        val cv = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayManager)
            setViewTreeViewModelStoreOwner(this@OverlayManager)
            setViewTreeSavedStateRegistryOwner(this@OverlayManager)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        attachContent(cv)
        composeView = cv

        val lp = buildLayoutParams(isExpanded = false)
        val density = context.resources.displayMetrics.density
        val insetPx = (16 * density).toInt()
        if (bubbleX < 0 || bubbleY < 0) {
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.x = insetPx
            lp.y = insetPx + 64
        } else {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = bubbleX
            lp.y = bubbleY
        }

        cv.setOnTouchListener(DragListener(lp, density))

        runCatching { windowManager.addView(cv, lp) }
            .onSuccess { attached = true }
            .onFailure { Log.e(TAG, "addView failed", it) }

        stateJob = scope.launch {
            val collectorScope = this
            var autoCollapseJob: Job? = null
            bridge.overlayState.collect { state ->
                reflectExpansion(state.isExpanded)
                autoCollapseJob?.cancel()
                if (state.isExpanded && state.pendingApproval == null) {
                    autoCollapseJob = collectorScope.launch {
                        kotlinx.coroutines.delay(8_000L)
                        bridge.setExpanded(false)
                    }
                }
            }
        }
    }

    private fun detachOverlay() {
        stateJob?.cancel(); stateJob = null
        runCatching { composeView?.let { windowManager.removeView(it) } }
        composeView = null
        attached = false
    }

    private fun attachContent(cv: ComposeView) {
        cv.setContent {
            val state by bridge.overlayState.collectAsState()
            val anchorOnRight = bubbleX < context.resources.displayMetrics.widthPixels / 2 ||
                bubbleX < 0
            Box {
                OverlayContent(
                    state = state,
                    anchorOnRight = anchorOnRight,
                    onBubbleTap = { bridge.setExpanded(!state.isExpanded) },
                    onStop = {
                        bridge.requestStop()
                        bridge.setExpanded(false)
                    },
                    onAllow = {
                        bridge.submitApprovalDecision(true)
                    },
                    onDeny = {
                        bridge.submitApprovalDecision(false)
                    },
                    onDismissCard = { bridge.setExpanded(false) },
                )
            }
        }
    }

    private fun reflectExpansion(expanded: Boolean) {
        val cv = composeView ?: return
        val lp = (cv.layoutParams as? WindowManager.LayoutParams) ?: return
        val newFlags = baseFlags(expanded)
        if (lp.flags != newFlags) {
            lp.flags = newFlags
            runCatching { windowManager.updateViewLayout(cv, lp) }
        }
    }

    private fun buildLayoutParams(isExpanded: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(isExpanded),
            PixelFormat.TRANSLUCENT,
        )

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun baseFlags(isExpanded: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (isExpanded) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return flags
    }

    /**
     * Drag handler for the bubble. Movement under 8dp is treated as a tap (Compose
     * gets the click). Persists final position to DataStore on ACTION_UP. Coords clamp
     * to screen bounds.
     */
    private inner class DragListener(
        private val lp: WindowManager.LayoutParams,
        private val density: Float,
    ) : View.OnTouchListener {
        private val tapSlopPx = 8 * density
        private var downX = 0f
        private var downY = 0f
        private var initialX = 0
        private var initialY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = lp.x
                    initialY = lp.y
                    dragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > tapSlopPx || abs(dy) > tapSlopPx)) {
                        dragging = true
                        lp.gravity = Gravity.TOP or Gravity.START
                    }
                    if (dragging) {
                        val rawX = (initialX + dx).toInt()
                        val rawY = (initialY + dy).toInt()
                        val metrics = context.resources.displayMetrics
                        val bubblePx = (64 * density).toInt()
                        lp.x = rawX.coerceIn(0, (metrics.widthPixels - bubblePx).coerceAtLeast(0))
                        lp.y = rawY.coerceIn(0, (metrics.heightPixels - bubblePx).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(composeView, lp) }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        bubbleX = lp.x
                        bubbleY = lp.y
                        scope.launch(Dispatchers.IO) {
                            preferences.setOverlayBubblePosition(bubbleX, bubbleY)
                        }
                        return true
                    }
                }
            }
            return false
        }
    }

    companion object {
        private const val TAG = "OverlayManager"
    }
}
