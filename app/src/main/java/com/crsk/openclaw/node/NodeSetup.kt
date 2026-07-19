package com.crsk.openclaw.node

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedReader
import javax.inject.Inject
import javax.inject.Singleton

sealed interface NodeSetupState {
    data object NotStarted : NodeSetupState
    data class Step(val phase: String, val detail: String, val progress: Float) : NodeSetupState
    data class Failed(val error: String) : NodeSetupState
    data object Complete : NodeSetupState
}

/**
 * Provisions OpenClaw on first launch and starts the gateway.
 *
 * On first run, runs node-setup.js (bundled in assets) which:
 *   1. Extracts the bundled npm.tgz
 *   2. npm installs openclaw into filesDir/openclaw/
 *   3. Writes the ~/.openclaw/config.json skeleton + gateway auth token
 *   4. Installs the MCP shim files
 *
 * On subsequent runs it skips directly to starting the gateway. API keys never
 * pass through node-setup.js — NodeProcess.refreshMcpConfig writes providers
 * from the device keystore on every gateway start.
 *
 * The node binary is at nativeLibraryDir/libnode.so and config is at filesDir/home/.openclaw/
 */
@Singleton
class NodeSetup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nodeProcess: NodeProcess,
) {
    private val _state = MutableStateFlow<NodeSetupState>(NodeSetupState.NotStarted)
    val state: StateFlow<NodeSetupState> = _state.asStateFlow()

    private val assetsDir: File get() = File(context.filesDir, "assets")
    // Bump suffix when node-setup.js writes new config-shape changes (providers, agents, etc.)
    // so existing installs re-run setup and re-emit config.json. Kept in lockstep with
    // SETUP_VERSION in app/src/main/assets/node-setup.js — they MUST match, otherwise
    // this gate will short-circuit isProvisioned() before node-setup.js gets a chance
    // to re-run, and the new config (e.g. a new proxy URL) never lands on disk.
    private val doneMarker: File
        get() = File(context.filesDir, "home/.openclaw/.4ais_setup_done_v10")

    fun isProvisioned(): Boolean = doneMarker.exists()

    suspend fun ensureRunning(port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // Already provisioned — just (re)start the gateway
                if (isProvisioned()) {
                    step("starting", "Starting OpenClaw gateway…", 0.9f)
                    startGateway(port)
                    return@withContext Result.success(Unit)
                }

                // First launch: copy assets to filesDir so node-setup.js can read them
                step("assets", "Preparing setup files…", 0.05f)
                copyAssetsToFilesDir()

                // Run node-setup.js
                step("setup", "Installing OpenClaw (needs network, ~3 min)…", 0.10f)
                val setupScript = File(assetsDir, "node-setup.js")
                val env = mapOf(
                    "FILES_DIR" to context.filesDir.absolutePath,
                    "ASSETS_DIR" to assetsDir.absolutePath,
                    "OPENCLAW_PORT" to port.toString(),
                    "NODE_BIN" to nodeProcess.nodeBin,
                )
                val proc = nodeProcess.runScript(setupScript, envExtras = env)

                // Stream stdout lines as progress
                val reader = proc.inputStream.bufferedReader()
                val errReader = proc.errorStream.bufferedReader()

                var ready = false
                val stdoutLines = mutableListOf<String>()

                reader.forEachLine { line ->
                    stdoutLines.add(line)
                    when {
                        line.contains("phase=npm")       -> step("npm",      line, 0.15f)
                        line.contains("phase=openclaw")  -> step("openclaw", line, 0.20f)
                        line.contains("phase=config")    -> step("config",   line, 0.80f)
                        line.contains("phase=mcp")       -> step("mcp",      line, 0.85f)
                        line.contains("phase=done")      -> step("done",     line, 0.90f)
                        line.contains("OPENCLAW_READY")  -> ready = true
                        line.contains("installed")       -> step("openclaw", line, advanceInstallProgress())
                    }
                }

                val exitCode = proc.waitFor()
                val stderr = errReader.readText().takeLast(600)

                if (exitCode != 0 || !ready) {
                    val detail = if (stderr.isNotBlank()) stderr else stdoutLines.takeLast(5).joinToString("\n")
                    return@withContext Result.failure(
                        IllegalStateException("Setup failed (exit $exitCode):\n$detail")
                    )
                }

                // Start the gateway
                step("starting", "Starting OpenClaw gateway…", 0.92f)
                startGateway(port)

                _state.value = NodeSetupState.Complete
                Result.success(Unit)
            } catch (t: Throwable) {
                _state.value = NodeSetupState.Failed(t.message ?: t.javaClass.simpleName)
                Result.failure(t)
            }
        }

    suspend fun stop() = withContext(Dispatchers.IO) {
        nodeProcess.stopGateway()
    }

    private suspend fun startGateway(port: Int) {
        if (nodeProcess.isGatewayAlive()) return

        val configDir = File(context.filesDir, "home/.openclaw")
        val process = nodeProcess.startGateway(configDir, port)

        // Drain logs in background, keeping a ring buffer so a failure surfaces the real cause
        val recent = drainProcessOutput(process, "gateway")

        val startMs = System.currentTimeMillis()
        val healthy = nodeProcess.waitForHealth(port, maxSeconds = 90)
        val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(1)

        if (!healthy) {
            val status = if (process.isAlive) "still running" else "exited (code ${process.exitValue()})"
            val tail = recent.tail().ifBlank { "(no output captured)" }
            nodeProcess.stopGateway()
            throw IllegalStateException(
                "OpenClaw gateway failed to start on port $port after ${elapsedSec}s — process $status.\n" +
                "Last output:\n$tail"
            )
        }
    }

    private fun copyAssetsToFilesDir() {
        // Always overwrite. This function only runs when doneMarker is absent (fresh install
        // or version bump), so the small cost of recopying npm.tgz + scripts is fine and
        // ensures bumped asset versions actually reach disk.
        assetsDir.mkdirs()
        val am = context.assets
        for (name in am.list("") ?: emptyArray()) {
            val dest = File(assetsDir, name)
            runCatching {
                am.open(name).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
        val mcpDest = File(assetsDir, "mcp-shizuku")
        mcpDest.mkdirs()
        for (name in am.list("mcp-shizuku") ?: emptyArray()) {
            val dest = File(mcpDest, name)
            runCatching {
                am.open("mcp-shizuku/$name").use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private var installStepCount = 0
    private fun advanceInstallProgress(): Float {
        installStepCount++
        return (0.20f + (installStepCount.toFloat() / 500f).coerceAtMost(0.55f))
    }

    private fun step(phase: String, detail: String, progress: Float) {
        _state.value = NodeSetupState.Step(phase, detail, progress)
    }

    private fun drainProcessOutput(process: Process, tag: String): OutputTail {
        val buffer = OutputTail(capacity = 40)
        val stderrLog = RollingLogFile(
            file = File(context.filesDir, "logs/gateway.err"),
            maxBytes = 256 * 1024,
        )
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    android.util.Log.d("NodeSetup/$tag", line)
                    buffer.add(line)
                }
            }
        }.apply { isDaemon = true }.start()
        Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    android.util.Log.w("NodeSetup/$tag", line)
                    buffer.add(line)
                    stderrLog.append(line + "\n")
                }
            }.also { stderrLog.close() }
        }.apply { isDaemon = true }.start()
        return buffer
    }

    private class OutputTail(private val capacity: Int) {
        private val lines = ArrayDeque<String>()
        @Synchronized fun add(line: String) {
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast(line)
        }
        @Synchronized fun tail(): String = lines.joinToString("\n")
    }
}
