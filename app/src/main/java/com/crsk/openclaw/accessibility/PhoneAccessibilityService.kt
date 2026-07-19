package com.crsk.openclaw.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PhoneAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface A11yEntryPoint {
        fun holder(): AccessibilityServiceHolder
    }

    private lateinit var holder: AccessibilityServiceHolder

    private val cachedSnapshot = AtomicReference<UiSnapshot?>(null)
    private val lastRefresh = AtomicLong(0L)
    private val isProcessing = AtomicBoolean(false)

    // Background thread for tree traversal. Accessibility callbacks land on a
    // single binder thread; doing the expensive flatten + JSON build there
    // serialises against subsequent accessibility events (including the IME's
    // own cursor-blink updates), which made tapping the chat composer feel
    // laggy. Posting to this dedicated worker lets the callback return in
    // microseconds and keeps the cursor blink path unblocked.
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var visionOverlay: AgentVisionOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        holder = EntryPointAccessors.fromApplication(application, A11yEntryPoint::class.java).holder()
        holder.attach(this)
        visionOverlay = AgentVisionOverlay(this)
        workerThread = HandlerThread("a11y-tree-worker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        Log.i(TAG, "onServiceConnected — UI automation active")
        scheduleRefresh()
    }

    fun visionShow(marks: List<ElementNode>) { visionOverlay?.showMarks(marks) }
    fun visionFlash(x: Int, y: Int) { visionOverlay?.flashTap(x, y) }
    fun visionHide() { visionOverlay?.hide() }
    fun visionSetHiddenForCapture(hidden: Boolean) { visionOverlay?.setHiddenForCapture(hidden) }

    private val recentNotifications = CopyOnWriteArrayList<NotificationEntry>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                val texts = mutableListOf<String>()
                event.text?.forEach { texts.add(it.toString()) }
                val entry = NotificationEntry(
                    packageName = pkg,
                    text = texts.joinToString(" | "),
                    timestamp = System.currentTimeMillis(),
                )
                recentNotifications.add(entry)
                while (recentNotifications.size > MAX_NOTIFICATIONS) {
                    recentNotifications.removeAt(0)
                }
            }
            // TYPE_WINDOW_CONTENT_CHANGED is intentionally NOT in this list —
            // it fires on every cursor blink, IME bar animation, and minor
            // content tweak (hundreds/sec during an active animation), and
            // forced a full tree refresh each time. The agent gets fresh
            // trees on real screen changes (state) and scrolls. On-demand
            // getSnapshot() callers always re-fetch when the cache is stale.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = System.currentTimeMillis()
                if (now - lastRefresh.get() > DEBOUNCE_MS) {
                    scheduleRefresh()
                }
            }
        }
    }

    /**
     * Post a tree refresh to the background worker. Returns immediately so the
     * accessibility callback thread is never held while we walk the window
     * tree — keeps the IME cursor-blink event path unblocked.
     *
     * The CAS guard inside refreshTree() handles concurrency between this
     * async path and the synchronous getSnapshot() fallback path.
     */
    private fun scheduleRefresh() {
        val h = workerHandler ?: return
        lastRefresh.set(System.currentTimeMillis())
        h.post { refreshTree() }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        holder.detach()
        visionOverlay?.hide(); visionOverlay = null
        cachedSnapshot.set(null)
        workerHandler = null
        workerThread?.quitSafely()
        workerThread = null
        Log.i(TAG, "onUnbind — UI automation stopped")
        return super.onUnbind(intent)
    }

    fun getSnapshot(): UiSnapshot? {
        val snap = cachedSnapshot.get()
        if (snap != null && System.currentTimeMillis() - snap.timestamp < STALE_GRACE_MS) {
            return snap
        }
        refreshTree()
        return cachedSnapshot.get()
    }

    fun getCurrentPackage(): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString().orEmpty()
        try { root.recycle() } catch (_: Exception) {}
        return pkg
    }

    fun getCurrentActivity(): String {
        val root = rootInActiveWindow ?: return ""
        val window = root.window
        val title = window?.title?.toString().orEmpty()
        try { root.recycle() } catch (_: Exception) {}
        return title
    }

    suspend fun dispatchTap(x: Int, y: Int, durationMs: Long = 100L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture)
    }

    suspend fun dispatchLongTap(x: Int, y: Int, durationMs: Long = 1000L): Boolean {
        return dispatchTap(x, y, durationMs)
    }

    suspend fun dispatchDoubleTap(x: Int, y: Int): Boolean {
        val first = dispatchTap(x, y, 50L)
        kotlinx.coroutines.delay(120L)
        val second = dispatchTap(x, y, 50L)
        return first && second
    }

    suspend fun dispatchSwipe(
        x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture)
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun setTextOnFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root)
        if (focused != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            try { focused.recycle() } catch (_: Exception) {}
            try { root.recycle() } catch (_: Exception) {}
            return result
        }
        try { root.recycle() } catch (_: Exception) {}
        return false
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && (node.isEditable || node.className?.toString()?.contains("EditText") == true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val found = findFocusedEditable(child)
            try { child.recycle() } catch (_: Exception) {}
            if (found != null) return found
        }
        return null
    }

    private suspend fun dispatchAndAwait(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)
        return deferred.await()
    }

    private fun refreshTree() {
        // CAS guards both the background-worker post path and the synchronous
        // getSnapshot() fallback path. If a refresh is already in flight, we
        // bail — the in-flight one will publish a fresh snapshot momentarily.
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            val windowRoots = mutableListOf<WindowRoot>()
            var keyboardVisible = false
            try {
                windows.forEach { w ->
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        keyboardVisible = true
                    }
                    val root = w.root ?: return@forEach
                    val r = Rect(); w.getBoundsInScreen(r)
                    windowRoots.add(WindowRoot(root, IntBox(r.left, r.top, r.right, r.bottom), w.layer))
                }
            } catch (_: Exception) {
                rootInActiveWindow?.let {
                    val m = resources.displayMetrics
                    windowRoots.add(WindowRoot(it, IntBox(0, 0, m.widthPixels, m.heightPixels), 0))
                }
            }

            if (windowRoots.isEmpty()) return

            val metrics = resources.displayMetrics
            val nodes = UiTreeBuilder.build(windowRoots, metrics.widthPixels, metrics.heightPixels)
            val marks = MarkAssigner.assign(nodes)
            val focusedMarkId = marks.firstOrNull { it.isFocused }?.markId

            cachedSnapshot.set(
                UiSnapshot(
                    nodes = nodes,
                    marks = marks,
                    packageName = getCurrentPackage(),
                    activityName = getCurrentActivity(),
                    timestamp = System.currentTimeMillis(),
                    screenWidth = metrics.widthPixels,
                    screenHeight = metrics.heightPixels,
                    keyboardVisible = keyboardVisible,
                    focusedMarkId = focusedMarkId,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "refreshTree failed", e)
        } finally {
            isProcessing.set(false)
        }
    }

    /** Raw screenshot as a Bitmap. Caller owns it and must recycle. Used by /ui/observe so the
     *  renderer can draw numbered overlays at full resolution before downscaling. */
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deferred = CompletableDeferred<Bitmap?>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    deferred.complete(bitmap)
                }
                override fun onFailure(errorCode: Int) {
                    deferred.complete(null)
                }
            })
        return deferred.await()
    }

    suspend fun takeScreenshotBase64(): String? {
        val bitmap = takeScreenshotBitmap() ?: return null
        val sw = bitmap.width
        val sh = bitmap.height
        val scale = if (sw > 720) 720f / sw else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (sw * scale).toInt(), (sh * scale).toInt(), true)
                .also { bitmap.recycle() }
        } else bitmap
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        scaled.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun getClipboard(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    fun setClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("4ais", text))
    }

    fun getRecentNotifications(): List<NotificationEntry> = recentNotifications.toList()

    fun clearNotifications(): Int {
        val count = recentNotifications.size
        recentNotifications.clear()
        return count
    }

    companion object {
        private const val TAG = "PhoneA11yService"
        // 200ms (was 16ms): real screen changes update the tree fast enough
        // for the agent; an aggressive debounce was firing 60×/sec during
        // animations and starving the accessibility queue.
        private const val DEBOUNCE_MS = 200L
        private const val STALE_GRACE_MS = 750L
        private const val MAX_NOTIFICATIONS = 50
    }
}

data class NotificationEntry(
    val packageName: String,
    val text: String,
    val timestamp: Long,
)

data class UiSnapshot(
    val nodes: List<ElementNode>,
    val marks: List<ElementNode>,
    val packageName: String,
    val activityName: String,
    val timestamp: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val keyboardVisible: Boolean = false,
    val focusedMarkId: Int? = null,
)
