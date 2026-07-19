package com.crsk.openclaw.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/** A single recorded agent action. Local-only — never uploaded, never persisted to backup. */
data class AuditEvent(
    val timestampMs: Long,
    val verb: String,              // tap, type, swipe, exec, app/launch, etc.
    val targetPackage: String,     // foreground package at the time, or "" if unknown
    val summary: String,           // short human-readable description
    val ok: Boolean,
)

/**
 * Append-only local audit log of every agent action. Used for:
 *   - User transparency: Settings → Agent activity surfaces the last 7 days.
 *   - DPDP / GDPR accountability: we can show users what the agent did on their phone.
 *   - Forensics: if something went wrong, the user can see when and where.
 *
 * Storage: JSONL at <filesDir>/audit/agent.log. Capped at MAX_BYTES (default ~2 MiB) —
 * older lines are evicted from the front on rollover. Never leaves the device.
 */
@Singleton
class AgentAuditLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val logFile: File get() = File(context.filesDir, "audit/agent.log")

    suspend fun record(event: AuditEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = logFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val line = JSONObject().apply {
                put("t", event.timestampMs)
                put("v", event.verb)
                put("p", event.targetPackage)
                put("s", event.summary.take(500))
                put("ok", event.ok)
            }.toString() + "\n"
            logFile.appendText(line)
            rolloverIfNeeded()
        }
    }

    /** Returns the most recent events up to `limit`, oldest first. */
    suspend fun recent(limit: Int = 200): List<AuditEvent> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!logFile.exists()) return@withContext emptyList()
            logFile.useLines { lines ->
                lines.mapNotNull { line ->
                    runCatching {
                        val o = JSONObject(line)
                        AuditEvent(
                            timestampMs = o.optLong("t"),
                            verb = o.optString("v"),
                            targetPackage = o.optString("p"),
                            summary = o.optString("s"),
                            ok = o.optBoolean("ok", true),
                        )
                    }.getOrNull()
                }.toList().takeLast(limit)
            }
        }
    }

    /** Wipe the audit log. Surfaced as "Clear" in Settings → Agent activity. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (logFile.exists()) logFile.delete()
        }
    }

    private fun rolloverIfNeeded() {
        val size = logFile.length()
        if (size <= MAX_BYTES) return
        // Drop the first half. Simpler than time-windowed eviction and bounded
        // memory — we just rewrite the file in place.
        runCatching {
            RandomAccessFile(logFile, "rw").use { raf ->
                val cutoff = size / 2
                val tailLen = (size - cutoff).toInt()
                val tail = ByteArray(tailLen)
                raf.seek(cutoff)
                raf.readFully(tail)
                raf.seek(0)
                raf.write(tail)
                raf.setLength(tailLen.toLong())
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 2L * 1024 * 1024 // 2 MiB
    }
}
