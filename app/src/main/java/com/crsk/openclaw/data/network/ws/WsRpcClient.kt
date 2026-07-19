package com.crsk.openclaw.data.network.ws

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class WsRpcClient @Inject constructor(
    private val token: GatewayToken,
) : WsEventSource, WsRpcCaller {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived WS
        .build()

    private var ws: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Frame.Response>>()
    // Event buffer sized to absorb sustained TextDelta bursts during long agent runs
    // (~100 tok/s × ~5 ms/frame fills 64 in <320 ms, which stalled the listener when
    // the UI dispatcher hiccupped). 256 covers ~1.3 s of slack at the same rate.
    private val _events = MutableSharedFlow<Frame.Event>(extraBufferCapacity = 256)
    override val events: SharedFlow<Frame.Event> = _events.asSharedFlow()

    private val connectMutex = Mutex()
    @Volatile private var connected = false
    @Volatile private var lastFrameMs: Long = 0L
    @Volatile private var tickIntervalMs: Long = 30_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun ensureConnected(port: Int) = connectMutex.withLock {
        if (connected) return@withLock
        connectInternal(port)
        connected = true
        startWatchdog()
    }

    private suspend fun connectInternal(port: Int) {
        val challenge = CompletableDeferred<JSONObject>()
        val helloOk = CompletableDeferred<Frame.Response>()
        var helloReqId: String? = null

        val request = Request.Builder().url("ws://127.0.0.1:$port/").build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFrameMs = System.currentTimeMillis()
                val frame = Frames.parse(text) ?: return
                when (frame) {
                    is Frame.Event -> {
                        if (frame.event == "connect.challenge" && !challenge.isCompleted) {
                            challenge.complete(frame.payload)
                        } else {
                            _events.tryEmit(frame)
                        }
                    }
                    is Frame.Response -> {
                        if (frame.id == helloReqId && !helloOk.isCompleted) {
                            helloOk.complete(frame)
                        }
                        pending.remove(frame.id)?.complete(frame)
                    }
                    else -> { /* server-initiated requests not supported */ }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "ws closed code=$code reason=$reason")
                connected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "ws failure: ${t.message}", t)
                connected = false
                if (!challenge.isCompleted) challenge.completeExceptionally(t)
                if (!helloOk.isCompleted) helloOk.completeExceptionally(t)
            }
        })

        val challengePayload = withTimeout(5_000) { challenge.await() }
        Log.d(TAG, "got challenge nonce=${challengePayload.optString("nonce").take(8)}…")

        // Loopback "backend" client identity — no device crypto needed (verified empirically).
        // If the gateway rejects with DEVICE_AUTH_*, implement Task 4-bis (Ed25519 keypair).
        val authToken = token.ensureToken()
        helloReqId = UUID.randomUUID().toString()
        val connectReq = Frame.Request(
            id = helloReqId!!,
            method = "connect",
            params = JSONObject().apply {
                put("minProtocol", 3)
                put("maxProtocol", 4)
                put("client", JSONObject().apply {
                    // Must be "gateway-client" for the loopback+backend+shared-token scope-grant
                    // shortcut (shouldSkipLocalBackendSelfPairing in openclaw). "openclaw-android"
                    // is a different identity that requires device-keypair pairing.
                    put("id", "gateway-client")
                    put("version", "0.1.0")
                    put("platform", "android")
                    put("mode", "backend")
                })
                put("role", "operator")
                put("scopes", JSONArray().apply {
                    put("operator.read")
                    put("operator.write")
                    // Required so the agent RPC accepts our per-call `model` + `provider`
                    // override — see resolveAllowModelOverrideFromClient in openclaw.
                    put("operator.admin")
                    put("operator.approvals")
                })
                put("caps", JSONArray().apply { put("tool-events") })
                put("commands", JSONArray())
                put("permissions", JSONObject())
                put("auth", JSONObject().put("token", authToken))
                put("userAgent", "4ais-android/0.1.0")
            },
        )
        ws!!.send(Frames.encode(connectReq))

        val res = withTimeout(10_000) { helloOk.await() }
        if (!res.ok) {
            val err = res.error
            throw GatewayHandshakeException("connect failed: ${err?.code}: ${err?.message}")
        }
        res.payload?.optJSONObject("policy")?.optLong("tickIntervalMs")?.takeIf { it > 0 }
            ?.let { tickIntervalMs = it }
        Log.i(TAG, "hello-ok protocol=${res.payload?.optInt("protocol")} tickInterval=${tickIntervalMs}ms")
    }

    override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): Frame.Response = doCall(method, params, timeoutMs)

    /** Same as `call` but with the convenience default timeout. */
    suspend fun call(method: String, params: JSONObject): Frame.Response = doCall(method, params, 60_000L)

    private suspend fun doCall(method: String, params: JSONObject, timeoutMs: Long): Frame.Response {
        val id = UUID.randomUUID().toString()
        val pendingDeferred = CompletableDeferred<Frame.Response>()
        pending[id] = pendingDeferred
        val req = Frame.Request(id, method, params)
        val sent = ws?.send(Frames.encode(req)) ?: false
        if (!sent) {
            pending.remove(id)
            throw GatewayHandshakeException("WebSocket not connected")
        }
        return try {
            withTimeout(timeoutMs) { pendingDeferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
    }

    private fun startWatchdog() {
        scope.launch {
            while (connected) {
                delay(tickIntervalMs)
                val silentMs = System.currentTimeMillis() - lastFrameMs
                if (silentMs > tickIntervalMs * 2) {
                    Log.w(TAG, "tick timeout (${silentMs}ms) — closing")
                    ws?.close(4000, "tick timeout")
                    connected = false
                    break
                }
            }
        }
    }

    fun close() {
        ws?.close(1000, "client shutdown")
        ws = null
        connected = false
    }

    companion object { private const val TAG = "WsRpcClient" }
}

class GatewayHandshakeException(message: String) : Exception(message)
