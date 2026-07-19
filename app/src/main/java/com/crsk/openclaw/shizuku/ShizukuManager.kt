package com.crsk.openclaw.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _status = MutableStateFlow<ShizukuStatus>(ShizukuStatus.NotInstalled)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var commandService: IShizukuCommandService? = null

    /** Guards against re-spamming the Shizuku permission dialog every evaluate(). */
    @Volatile
    private var permissionRequested = false

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        evaluate()
        ensurePermissionAndBind()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        commandService = null
        evaluate()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        val granted = result == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "permission result: granted=$granted")
        if (granted) bindUserService() else evaluate()
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context, ShizukuCommandService::class.java)
        ).daemon(false).processNameSuffix("shizuku_cmd").version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder?.pingBinder() == true) {
                val svc = IShizukuCommandService.Stub.asInterface(binder)
                commandService = svc
                runCatching { svc.version() }
                    .onSuccess { _status.value = ShizukuStatus.Ready(it ?: "?") }
                    .onFailure { _status.value = ShizukuStatus.Failed(it.message ?: "version() failed") }
            } else {
                _status.value = ShizukuStatus.Failed("dead binder on connect")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            commandService = null
            evaluate()
        }
    }

    @Volatile
    private var initialized = false

    fun init() {
        synchronized(this) {
            if (initialized) {
                evaluate()
                return
            }
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
            initialized = true
        }
        evaluate()
        ensurePermissionAndBind()
    }

    fun shutdown() {
        synchronized(this) {
            if (!initialized) return
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
            commandService = null
            permissionRequested = false
            initialized = false
        }
    }

    /**
     * Force re-detection of Shizuku state. Call from a polling timer while in
     * NotInstalled/NotRunning so we auto-advance once the user finishes off-app setup
     * (Play Store install, wireless ADB pairing, tapping Start in the Shizuku app).
     */
    fun refresh() = evaluate()

    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Auto-handshake. If Shizuku is alive and our app already has permission, bind the
     * privileged user service; otherwise issue the permission request — at most once per
     * process (the [permissionRequested] guard), so a service restart doesn't re-spam the
     * dialog. Safe to call repeatedly. An explicit [requestPermission] clears the guard.
     */
    fun ensurePermissionAndBind() {
        if (!Shizuku.pingBinder()) {
            Log.d(TAG, "ensurePermissionAndBind: binder not alive yet")
            return
        }
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "ensurePermissionAndBind: pre-v11 Shizuku is unsupported")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (commandService == null) {
                Log.d(TAG, "permission already granted — binding user service")
                bindUserService()
            }
            return
        }
        if (permissionRequested) {
            Log.d(TAG, "permission not granted; request already issued this session")
            return
        }
        permissionRequested = true
        Log.d(TAG, "requesting Shizuku permission")
        runCatching { Shizuku.requestPermission(REQ_CODE) }
            .onFailure { Log.e(TAG, "Shizuku.requestPermission failed", it) }
    }

    /** Explicit user action (Settings button) — always re-issues the permission request. */
    fun requestPermission() {
        permissionRequested = false
        ensurePermissionAndBind()
    }

    private fun bindUserService() {
        _status.value = ShizukuStatus.Connecting
        Log.d(TAG, "binding Shizuku user service")
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure {
                Log.e(TAG, "bindUserService failed", it)
                _status.value = ShizukuStatus.Failed(it.message ?: "bind failed")
            }
    }

    private fun evaluate() {
        _status.value = when {
            !isShizukuInstalled() -> ShizukuStatus.NotInstalled
            !Shizuku.pingBinder() -> ShizukuStatus.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.PermissionRequired
            commandService == null -> { bindUserService(); ShizukuStatus.Connecting }
            else -> ShizukuStatus.Ready(commandService?.version() ?: "?")
        }
    }

    fun exec(argv: List<String>, stdin: String? = null, timeoutMs: Int = 10_000): Triple<Int, String, String> {
        val svc = commandService ?: error("Shizuku not ready: ${_status.value}")
        val raw = svc.exec(argv.toTypedArray(), stdin, timeoutMs)
        val (exitStr, rest) = raw.split("\n", limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        val exit = exitStr.toIntOrNull() ?: -1
        val parts = rest.split("---STDERR---", limit = 2)
        val stdout = parts.getOrNull(0).orEmpty().trimEnd('\n')
        val stderr = parts.getOrNull(1).orEmpty().trimStart('\n')
        return Triple(exit, stdout, stderr)
    }

    companion object {
        private const val REQ_CODE = 4243
        private const val TAG = "ShizukuManager"
    }
}
