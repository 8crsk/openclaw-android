package com.crsk.openclaw.util

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceId {

    /** Returns a stable, 16-hex-char device pseudonym. */
    fun get(context: Context): String {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        return normalize(raw ?: fallbackUuid())
    }

    /**
     * Hash + normalize input to 16 lowercase hex chars. Lets us accept any input format
     * (ANDROID_ID is 16 hex but historically has been longer/shorter on weird builds)
     * while always producing a server-validatable shape.
     */
    fun normalize(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        // 16 hex chars = 8 bytes = first 8 of the SHA-256 digest.
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun fallbackUuid(): String = java.util.UUID.randomUUID().toString()
}
