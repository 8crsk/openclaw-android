package com.crsk.openclaw.data.network.ws

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class GatewayToken @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val configDir: File get() = File(context.filesDir, "home/.openclaw")
    private val tokenFile: File get() = File(configDir, "auth-token")
    private val configFile: File get() = File(configDir, "config.json")

    open fun read(): String? = tokenFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    /**
     * Returns the persistent gateway token. Reads from the sidecar file if present;
     * otherwise generates a fresh 32-byte hex token, writes it to the sidecar (0600),
     * and returns it. The same value is passed to openclaw via the OPENCLAW_GATEWAY_TOKEN
     * env var when the gateway is launched (see NodeProcess.startGateway).
     */
    open fun ensureToken(): String {
        read()?.let { return it }
        configDir.mkdirs()
        val newToken = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        tokenFile.writeText(newToken)
        return newToken
    }
}