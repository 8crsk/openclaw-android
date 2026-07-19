package com.crsk.openclaw.shizuku

import com.crsk.openclaw.accessibility.UiRoutes
import com.crsk.openclaw.data.network.ws.GatewayToken
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuBridge @Inject constructor(
    private val shizuku: ShizukuManager,
    private val uiRoutes: UiRoutes,
    private val gatewayToken: GatewayToken,
    private val auditLog: com.crsk.openclaw.data.AgentAuditLog,
) : NanoHTTPD("127.0.0.1", 3001) {

    private val auditScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private fun auditExec(argv: List<String>, ok: Boolean, summary: String) {
        auditScope.launch {
            runCatching {
                auditLog.record(
                    com.crsk.openclaw.data.AuditEvent(
                        timestampMs = System.currentTimeMillis(),
                        verb = "exec",
                        targetPackage = argv.firstOrNull().orEmpty(),
                        summary = "${argv.take(4).joinToString(" ")}${if (argv.size > 4) " …" else ""} — $summary",
                        ok = ok,
                    ),
                )
            }
        }
    }

    fun startBridge() {
        // Make sure the auth token exists before the bridge starts serving so the
        // first-request race (gateway not yet started but bridge already up) doesn't
        // 401 the agent on a cold app launch. ensureToken is idempotent and shared
        // with NodeProcess.startGateway, so both sides see the same value.
        gatewayToken.ensureToken()
        uiRoutes.wireShellExec { argv ->
            // Defense-in-depth: the same allowlist that gates /exec also gates the
            // shell-exec injected into UiRoutes for /agent/act fallbacks.
            val check = ExecAllowlist.validate(argv)
            if (check is ExecAllowlist.Result.Denied) {
                Triple(126, "", "denied by exec allowlist: ${check.reason}")
            } else {
                shizuku.exec(argv, null, 10_000)
            }
        }
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
    }

    fun stopBridge() {
        if (isAlive) stop()
    }

    override fun serve(session: IHTTPSession): Response {
        // Bearer auth — required on EVERY route. Without this, any app on the device
        // with INTERNET permission can curl 127.0.0.1:3001/exec and run shell at the
        // Shizuku UID. Loopback is not a security boundary on Android.
        if (!isAuthorized(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/plain", "unauthorized",
            )
        }
        return when {
            session.uri == "/health" -> json(JSONObject().put("status", shizuku.status.value::class.simpleName))
            session.uri == "/exec" -> handleExec(session)
            session.uri.startsWith("/ui/") -> uiRoutes.handle(session)
            session.uri.startsWith("/agent/") -> uiRoutes.handle(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val expected = gatewayToken.read() ?: return false
        // NanoHTTPD lowercases all header names.
        val raw = session.headers["authorization"]?.trim() ?: return false
        val provided = raw.removePrefix("Bearer ").trim()
        if (provided.isBlank()) return false
        // Constant-time comparison — prevents timing oracles on the token.
        val a = provided.toByteArray()
        val b = expected.toByteArray()
        if (a.size != b.size) return false
        return java.security.MessageDigest.isEqual(a, b)
    }

    private fun handleExec(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST only")
        }
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            val body = files["postData"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "no body")
            val req = JSONObject(body)
            val argvJson = req.getJSONArray("argv")
            val argv = (0 until argvJson.length()).map { argvJson.getString(it) }
            // Strict allowlist — see ExecAllowlist. Refuses sh, su, mount, fully-
            // qualified paths to non-allowlisted binaries, and `am start` to
            // sensitive activities (e.g. the dev-settings/ADB toggle).
            val check = ExecAllowlist.validate(argv)
            if (check is ExecAllowlist.Result.Denied) {
                auditExec(argv, ok = false, summary = "denied by allowlist: ${check.reason}")
                return json(
                    JSONObject()
                        .put("error", "denied: ${check.reason}")
                        .put(
                            "hint",
                            "Only allowlisted binaries (am, pm, input, dumpsys, settings, cmd, wm, screencap, monkey) can be exec'd. " +
                                "For UI work use /agent/act or /ui/* verbs — they cover tap/type/swipe/scroll/screenshot without needing shell.",
                        ),
                    Response.Status.FORBIDDEN,
                )
            }
            val stdin = if (req.has("stdin") && !req.isNull("stdin")) req.getString("stdin") else null
            val timeout = req.optInt("timeoutMs", 10_000)
            val (exit, out, err) = shizuku.exec(argv, stdin, timeout)
            auditExec(argv, ok = exit == 0, summary = "exit=$exit")
            json(
                JSONObject()
                    .put("exitCode", exit)
                    .put("stdout", out)
                    .put("stderr", err)
            )
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            val isShizukuMissing = msg.contains("Shizuku", ignoreCase = true) || msg.contains("not ready", ignoreCase = true)
            val status = if (isShizukuMissing) Response.Status.SERVICE_UNAVAILABLE else Response.Status.INTERNAL_ERROR
            // Phrasing matters: prior wording ("Shell access requires Shizuku. Set it
            // up...") nudged the agent to surface "Shizuku not enabled" to the user
            // and stop. Reframed to push the model back onto /ui/* + /agent/act, which
            // cover essentially everything an agent task needs.
            json(
                JSONObject()
                    .put("error", msg)
                    .put("hint", if (isShizukuMissing)
                        "Shell access is unavailable (optional component). Use /agent/act verbs (tap, type, swipe, back, home) or /ui/* routes for UI work — they cover all normal Android automation without shell."
                    else ""),
                status
            )
        }
    }

    private fun json(obj: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())
}
