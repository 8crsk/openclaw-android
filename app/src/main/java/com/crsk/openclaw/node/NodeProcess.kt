package com.crsk.openclaw.node

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the embedded Node.js process launched from nativeLibraryDir.
 *
 * The node binary is at nativeLibraryDir/libnode.so (ELF, executable).
 * All shared-lib dependencies (libcrypto.so, libicudata.so, …) sit alongside it.
 * LD_LIBRARY_PATH is set to nativeLibraryDir so the dynamic linker finds them.
 *
 * The binary was compiled with TERMUX_APP__PACKAGE_NAME=com.crsk.openclaw, so
 * hardcoded paths inside Node.js point to our app's filesDir automatically.
 */
@Singleton
class NodeProcess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewayToken: com.crsk.openclaw.data.network.ws.GatewayToken,
    private val preferences: com.crsk.openclaw.data.preferences.AppPreferences,
    private val keyStore: com.crsk.openclaw.data.preferences.EncryptedKeyStore,
    private val composio: com.crsk.openclaw.data.composio.ComposioRepository,
) {
    val nodeBin: String get() = "${context.applicationInfo.nativeLibraryDir}/libnode.so"
    val libDir: String get() = context.applicationInfo.nativeLibraryDir
    val filesDir: File get() = context.filesDir

    private var gatewayProcess: Process? = null

    // Memoised hash of the most recent config we wrote. refreshMcpConfig now
    // skips the disk write when the JSON content is unchanged — saves 100–300 ms
    // per warm gateway start and reduces flash wear.
    @Volatile private var lastConfigHash: String? = null

    fun buildEnv(extras: Map<String, String> = emptyMap()): Map<String, String> {
        val homeDir = File(filesDir, "home").also { it.mkdirs() }
        val tmpDir = File(filesDir, "tmp").also { it.mkdirs() }
        return buildMap {
            put("LD_LIBRARY_PATH", libDir)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("PREFIX", File(filesDir, "usr").absolutePath)
            // filesDir/bin is first so the agent's `act` wrapper (written by
            // writeActWrapper) shadows nothing and is resolvable from openclaw's exec tool.
            put("PATH", "${File(filesDir, "bin").absolutePath}:${File(filesDir, "openclaw/node_modules/.bin").absolutePath}:$libDir:/system/bin:/system/xbin")
            put("NODE_ENV", "production")
            put("npm_config_cache", File(filesDir, "npm-cache").absolutePath)
            put("npm_config_userconfig", File(filesDir, "npm-config").absolutePath)
            putAll(extras)
        }
    }

    fun runScript(
        script: File,
        args: List<String> = emptyList(),
        envExtras: Map<String, String> = emptyMap(),
        workDir: File = filesDir,
    ): Process {
        require(script.exists()) { "Script not found: $script" }
        return ProcessBuilder(listOf(nodeBin, script.absolutePath) + args).apply {
            environment().clear()
            environment().putAll(buildEnv(envExtras))
            directory(workDir)
            redirectErrorStream(false)
        }.start()
    }

    fun startGateway(
        configDir: File,
        port: Int,
        envExtras: Map<String, String> = emptyMap(),
    ): Process {
        val openclawBin = resolveOpenclawEntryPoint()
        require(openclawBin.exists()) { "openclaw entry point not found: $openclawBin" }

        // Rewrite the mcp.servers.shizuku-phone block to the CURRENT install paths.
        // Android changes nativeLibraryDir on every install -r, so any path baked into
        // config.json at provision time goes stale on the next update.
        refreshMcpConfig(configDir)

        // Install the `act` command into filesDir/bin (on PATH, see buildEnv). Rewritten
        // every start so an APK update redeploys it WITHOUT needing a clean reinstall —
        // unlike node-setup.js, this is not gated by the run-once setup marker.
        writeActWrapper()

        Log.i("NodeProcess", "Starting gateway: $nodeBin ${openclawBin.absolutePath} gateway --port $port --allow-unconfigured")

        // openclaw treats gateway.auth.token as a non-persisted secret — the value must
        // come from OPENCLAW_GATEWAY_TOKEN env var, not the config file. GatewayToken
        // also writes the same value to ~/.openclaw/auth-token for the Android client to read.
        val token = gatewayToken.ensureToken()

        // openclaw 2026.5.12 looks for OPENCLAW_CONFIG_PATH (a FILE path), and
        // defaults to ~/.openclaw/openclaw.json — not the OPENCLAW_CONFIG (dir)
        // env we used to set, and not ~/.openclaw/config.json (our filename).
        // Without this env var openclaw falls back to compiled defaults for the
        // whole config, including agents.defaults.heartbeat — so the heartbeat
        // would run with the 30 m default instead of the user's interval.
        val env = buildEnv(envExtras) + mapOf(
            "OPENCLAW_CONFIG_PATH" to File(configDir, "config.json").absolutePath,
            "HOME" to File(filesDir, "home").absolutePath,
            "OPENCLAW_GATEWAY_TOKEN" to token,
        )

        val proc = ProcessBuilder(
            listOf(nodeBin, openclawBin.absolutePath, "gateway", "--port", port.toString(), "--allow-unconfigured")
        ).apply {
            environment().clear()
            environment().putAll(env)
            directory(filesDir)
            redirectErrorStream(false)
        }.start()

        gatewayProcess = proc
        return proc
    }

    fun stopGateway() {
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
    }

    fun isGatewayAlive(): Boolean = gatewayProcess?.isAlive == true

    suspend fun waitForHealth(port: Int, maxSeconds: Int = 90): Boolean = withContext(Dispatchers.IO) {
        repeat(maxSeconds) {
            if (!isGatewayAlive()) return@withContext false // Early exit if process died
            if (checkHealth(port)) return@withContext true
            delay(1_000)
        }
        false
    }

    fun checkHealth(port: Int): Boolean = try {
        val conn = (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1_500
            readTimeout = 1_500
            requestMethod = "GET"
        }
        val ok = conn.responseCode == 200 &&
            conn.contentType?.contains("json", ignoreCase = true) == true
        conn.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    /**
     * Write the on-device `act` command (see [ActWrapperScript]) to filesDir/bin/act and
     * mark it executable. Idempotent: content is constant, so we skip the write when the
     * file already matches. The gateway's exec tool finds it via PATH (filesDir/bin first).
     */
    private fun writeActWrapper() {
        runCatching {
            val binDir = File(filesDir, "bin").apply { mkdirs() }
            val act = File(binDir, "act")
            val body = ActWrapperScript.render()
            if (!act.exists() || act.readText() != body) {
                act.writeText(body)
            }
            act.setExecutable(true, false)
        }.onFailure { Log.w("NodeProcess", "writeActWrapper failed: ${it.message}") }
    }

    private fun refreshMcpConfig(configDir: File) {
        val cfgFile = File(configDir, "config.json")
        if (!cfgFile.exists()) return
        runCatching {
            // Write the launcher script that restores LD_LIBRARY_PATH (which
            // openclaw strips per host-env-security blockedPrefixes). Rewritten
            // every gateway start so libDir tracks the current install.
            val mcpDir = File(configDir, "mcp/shizuku-phone").apply { mkdirs() }
            val launchScript = File(mcpDir, "launch.sh")
            launchScript.writeText(
                LauncherScript.render(
                    libDir = libDir,
                    homeDir = File(filesDir, "home").absolutePath,
                    shimEntry = File(mcpDir, "index.js").absolutePath,
                )
            )
            val executableSet = launchScript.setExecutable(true, false)

            val cfg = org.json.JSONObject(cfgFile.readText())

            // openclaw 2026.5.12 dropped support for transport:"stdio" — it now
            // only allows "sse" or "streamable-http". With the old stdio entry
            // present, the entire config fails schema validation and the
            // gateway exits ("Gateway failed to start: Invalid config").
            // Per memory `openclaw_mcp_not_exposed`, the stdio shim never
            // actually reached the agent anyway; the agent uses the
            // curl→ShizukuBridge path on :3001 via the phone-capability
            // extraSystemPrompt. So we drop the mcp section entirely until/
            // unless we port the shim to streamable-http. The launch.sh and
            // shim files are still written above as a no-op so any future
            // re-enable doesn't need to rewrite the disk layout.
            cfg.remove("mcp")
            @Suppress("UNUSED_VARIABLE") val unusedExecBit = executableSet

            // ── Composio MCP injection (streamable-http transport) ────────────
            // If the user has connected any toolkits via Settings → Connections, we get
            // a single MCP URL covering all of them from ComposioRepository (cached
            // after each connect/disconnect). Inject it as an mcp.servers.composio entry
            // using openclaw 2026.5.12's "streamable-http" transport (the stdio variant
            // is dropped above but HTTP is still supported).
            val composioUrl = runCatching { composio.cachedMcpUrlBlocking() }.getOrNull()
            if (!composioUrl.isNullOrBlank()) {
                val mcp = org.json.JSONObject()
                val servers = org.json.JSONObject()
                servers.put("composio", org.json.JSONObject().apply {
                    put("transport", "streamable-http")
                    put("url", composioUrl)
                })
                mcp.put("servers", servers)
                cfg.put("mcp", mcp)
                Log.i("NodeProcess", "Injected Composio MCP server")
            }

            // ── Providers: rebuilt from the on-device keystore every start ────
            // The single source of truth for BYO keys is EncryptedSharedPreferences;
            // config.json is a derived artifact. Every provider the user has saved
            // a key for is written as a CUSTOM openai-completions provider (explicit
            // baseUrl + models array — never openclaw's built-in plugins, whose
            // static model catalogs have drifted from the live APIs). Providers
            // whose key was removed disappear from the config on the next start.
            val models = cfg.optJSONObject("models")
                ?: org.json.JSONObject().also { cfg.put("models", it) }
            val providers = org.json.JSONObject()
            for (p in com.crsk.openclaw.data.providers.ProviderCatalog.all) {
                val key = keyStore.getProviderKey(p.id)
                if (key.isBlank()) continue
                providers.put(p.id, org.json.JSONObject().apply {
                    put("baseUrl", p.baseUrl)
                    put("apiKey", key)
                    put("api", "openai-completions")
                    // Pin the embedded "pi" agent harness. openclaw routes
                    // openai-completions providers to the external "codex" harness
                    // when agentRuntime is unset → codex isn't registered on-device
                    // → the agent throws. resolveModelRuntimePolicy reads
                    // providerConfig.agentRuntime.id.
                    put("agentRuntime", org.json.JSONObject().put("id", "pi"))
                    put("models", org.json.JSONArray().apply {
                        p.models.forEach { m ->
                            put(org.json.JSONObject().apply {
                                put("id", m.id)
                                put("name", m.displayName)
                                put("reasoning", m.reasoning)
                                put("input", org.json.JSONArray().put("text"))
                                put("contextWindow", m.contextWindow)
                                put("maxTokens", m.maxTokens)
                            })
                        }
                    })
                })
            }
            models.put("providers", providers)
            val agents = cfg.optJSONObject("agents")
                ?: org.json.JSONObject().also { cfg.put("agents", it) }
            val defaults = agents.optJSONObject("defaults")
                ?: org.json.JSONObject().also { agents.put("defaults", it) }
            // Preserve user-edited heartbeat config (interval, activeHours, tasks).
            // We deliberately do NOT rewrite defaults.optJSONObject("heartbeat") here.
            // If it's missing entirely (older installs), seed the disabled default so
            // the user can toggle it on from Settings without a reinstall.
            if (defaults.optJSONObject("heartbeat") == null) {
                defaults.put("heartbeat", org.json.JSONObject().apply {
                    put("every", "0m")
                    put("target", "none")
                    put("skipWhenBusy", true)
                    // Fresh session per tick so the agent recomputes from prompt
                    // instead of parroting its previous reply.
                    put("isolatedSession", true)
                    put("ackMaxChars", 300)
                    put("lightContext", false)
                    put("activeHours", org.json.JSONObject().apply {
                        put("start", "00:00")
                        put("end", "23:59")
                        put("timezone", java.util.TimeZone.getDefault().id)
                    })
                })
            }
            val modelCfg = defaults.optJSONObject("model")
                ?: org.json.JSONObject().also { defaults.put("model", it) }
            // Default model = the user's Settings selection. Per-call `model` from
            // ChatSession still overrides this; the baseline covers non-overriding
            // pathways (heartbeat, fallback). Falls back to the catalog default
            // when the stored selection doesn't match the stored provider.
            val catalog = com.crsk.openclaw.data.providers.ProviderCatalog
            val provider = catalog.byId(preferences.apiProvider.value) ?: catalog.default
            val selected = preferences.selectedModel.value
            val modelId = provider.models.firstOrNull { it.id == selected }?.id
                ?: provider.defaultModel.id
            modelCfg.put("primary", "${provider.id}/$modelId")
            modelCfg.put("fallbacks", org.json.JSONArray())

            val serialised = cfg.toString(2)
            val hash = sha256(serialised)
            if (hash == lastConfigHash) {
                Log.d("NodeProcess", "Config unchanged (hash=${hash.take(8)}), skipping write")
            } else {
                cfgFile.writeText(serialised)
                lastConfigHash = hash
                Log.i("NodeProcess", "Refreshed config: providers from keystore, primary=${provider.id}/$modelId (hash=${hash.take(8)})")
            }
        }.onFailure { Log.w("NodeProcess", "refreshMcpConfig failed: ${it.message}") }
    }

    private fun sha256(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xff
                append(((v ushr 4) and 0xf).toString(16))
                append((v and 0xf).toString(16))
            }
        }
    }

    private fun resolveOpenclawEntryPoint(): File {
        val pkgDir = File(filesDir, "openclaw/node_modules/openclaw")
        val pkgJson = File(pkgDir, "package.json")
        if (pkgJson.exists()) {
            val json = org.json.JSONObject(pkgJson.readText())
            val bin = json.optJSONObject("bin")
            val entry = bin?.optString("openclaw")
                ?: json.optString("main")
            if (!entry.isNullOrBlank()) {
                val resolved = File(pkgDir, entry)
                if (resolved.exists()) return resolved
            }
        }
        // Fallback: try common patterns
        for (candidate in listOf("dist/cli.js", "dist/index.js", "bin/openclaw.js", "index.js")) {
            val f = File(pkgDir, candidate)
            if (f.exists()) return f
        }
        // Last resort: the .bin script (may work if it's a symlink to JS)
        return File(filesDir, "openclaw/node_modules/.bin/openclaw")
    }
}
