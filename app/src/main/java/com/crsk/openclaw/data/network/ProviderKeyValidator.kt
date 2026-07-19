package com.crsk.openclaw.data.network

import com.crsk.openclaw.data.providers.AiProvider
import com.crsk.openclaw.data.providers.KeyAuthStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed interface KeyValidationResult {
    data object Valid : KeyValidationResult
    data object InvalidKey : KeyValidationResult
    data class NetworkError(val message: String) : KeyValidationResult
    data class UnexpectedStatus(val code: Int) : KeyValidationResult
}

/**
 * Validates a bring-your-own API key with a cheap GET <baseUrl>/models probe.
 * Auth headers vary per provider (see [KeyAuthStyle]); everything else is shared.
 */
@Singleton
class ProviderKeyValidator @Inject constructor() {

    suspend fun validate(provider: AiProvider, apiKey: String): KeyValidationResult =
        withContext(Dispatchers.IO) {
            if (!apiKey.matches(provider.keyPattern)) return@withContext KeyValidationResult.InvalidKey

            val conn = (URL("${provider.baseUrl}/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                when (provider.authStyle) {
                    KeyAuthStyle.BEARER -> setRequestProperty("Authorization", "Bearer $apiKey")
                    KeyAuthStyle.ANTHROPIC -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                }
                setRequestProperty("Accept", "application/json")
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> KeyValidationResult.Valid
                    401, 403 -> KeyValidationResult.InvalidKey
                    else -> KeyValidationResult.UnexpectedStatus(code)
                }
            } catch (e: Exception) {
                KeyValidationResult.NetworkError(e.message ?: "Couldn't reach ${provider.displayName}")
            } finally {
                conn.disconnect()
            }
        }
}
