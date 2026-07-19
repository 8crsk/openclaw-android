package com.crsk.openclaw.accessibility

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app navigation notes — the AppAgent exploration pattern. The agent saves short
 * notes about how an app's UI is laid out the first time it figures something out
 * ("DMs are behind the airplane icon top-right"); on subsequent runs the gateway
 * injects those notes into every /agent/act response so the model starts with prior
 * knowledge instead of re-discovering.
 *
 * Storage: one JSON file per package at <filesDir>/home/.openclaw/app-docs/<pkg>.json.
 * Sits next to openclaw's own state dir so it's wiped together if the user clears
 * app data, and not synced anywhere.
 */
@Singleton
class AppDocsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val rootDir: File by lazy {
        File(context.filesDir, "home/.openclaw/app-docs").also { it.mkdirs() }
    }

    /** Max notes kept per package. New notes evict the oldest. Bounds per-turn token cost. */
    private val maxNotesPerPackage = 15

    /** Truncation cap per individual note. Forces the agent to be terse. */
    private val maxNoteLength = 160

    private fun fileFor(pkg: String): File {
        // Sanitize package name to a safe filename. Real Android packages match
        // [a-zA-Z0-9._] so this is mostly defensive against bad input.
        val safe = pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
        return File(rootDir, "$safe.json")
    }

    /** Return saved notes for a package, or empty list if none. */
    @Synchronized
    fun read(pkg: String): List<String> {
        if (pkg.isBlank()) return emptyList()
        val f = fileFor(pkg)
        if (!f.exists()) return emptyList()
        return try {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("notes") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Append a note. Dedupes obvious duplicates (substring match) and evicts oldest if over cap. */
    @Synchronized
    fun append(pkg: String, note: String): Boolean {
        if (pkg.isBlank() || note.isBlank()) return false
        val trimmed = note.trim().take(maxNoteLength)
        val existing = read(pkg).toMutableList()
        // Skip if a substring duplicate already exists in either direction —
        // prevents the agent from spamming "DMs are top right" 12 times.
        if (existing.any { it.contains(trimmed, ignoreCase = true) || trimmed.contains(it, ignoreCase = true) }) {
            return false
        }
        existing.add(trimmed)
        while (existing.size > maxNotesPerPackage) existing.removeAt(0)
        write(pkg, existing)
        return true
    }

    @Synchronized
    fun clear(pkg: String) {
        if (pkg.isBlank()) return
        fileFor(pkg).delete()
    }

    @Synchronized
    fun clearAll() {
        rootDir.listFiles()?.forEach { it.delete() }
    }

    /** All packages we have notes for, with their note counts. For Settings UI. */
    @Synchronized
    fun summarize(): List<Pair<String, Int>> =
        rootDir.listFiles().orEmpty()
            .mapNotNull { f ->
                val pkg = f.nameWithoutExtension
                val count = runCatching { JSONObject(f.readText()).optJSONArray("notes")?.length() ?: 0 }
                    .getOrDefault(0)
                if (count > 0) pkg to count else null
            }
            .sortedByDescending { it.second }

    private fun write(pkg: String, notes: List<String>) {
        val json = JSONObject().apply {
            put("package", pkg)
            put("updated", System.currentTimeMillis())
            put("notes", JSONArray().apply { notes.forEach { put(it) } })
        }
        fileFor(pkg).writeText(json.toString())
    }
}
