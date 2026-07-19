package com.crsk.openclaw.node

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Append-only log file with a soft size cap. When a write would push the file
 * past [maxBytes], the oldest content is dropped (keeping the tail).
 *
 * Trim happens on append, not on a timer, so it is bounded by writer activity.
 * Not thread-safe — wrap calls in @Synchronized at the call site or use one
 * instance per thread.
 */
class RollingLogFile(private val file: File, private val maxBytes: Long) : AutoCloseable {
    init { file.parentFile?.mkdirs() }
    private var writer: BufferedWriter = BufferedWriter(FileWriter(file, /* append = */ true))

    @Synchronized
    fun append(line: String) {
        if (file.length() + line.length > maxBytes) {
            writer.close()
            trimToTail()
            writer = BufferedWriter(FileWriter(file, /* append = */ true))
        }
        writer.write(line)
        writer.flush()
    }

    private fun trimToTail() {
        if (!file.exists()) return
        val keepBytes = (maxBytes / 2).coerceAtLeast(1024L)
        val bytes = file.readBytes()
        val start = (bytes.size - keepBytes.toInt()).coerceAtLeast(0)
        // Snap to the next newline so we don't leave a torn line at the head.
        var snap = start
        while (snap < bytes.size && bytes[snap] != '\n'.code.toByte()) snap++
        if (snap < bytes.size) snap++ // skip the newline itself
        file.writeBytes(bytes.copyOfRange(snap, bytes.size))
    }

    @Synchronized
    override fun close() { runCatching { writer.close() } }
}
