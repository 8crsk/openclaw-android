package com.crsk.openclaw.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when Android Keystore-backed EncryptedSharedPreferences can't be initialised.
 *  Callers (e.g. MainActivity startup) should catch this and surface a user-visible error
 *  ("Secure storage unavailable. Update Android System WebView / Play Services.") rather
 *  than silently degrading to plaintext storage. */
class SecureStorageException(cause: Throwable) :
    RuntimeException("Android Keystore-backed secure storage is unavailable on this device.", cause)

@Singleton
class EncryptedKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        // Wipe any pre-existing plaintext fallback file from older app versions
        // (we used to silently degrade to a plain SharedPreferences on Keystore
        // init failure — that left credentials in cleartext on disk). One-shot,
        // safe to run on every launch.
        runCatching {
            val legacy = context.getSharedPreferences("encrypted_keys_fallback", Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                legacy.edit().clear().apply()
                Log.w(TAG, "wiped legacy plaintext fallback prefs (older versions of the app)")
            }
        }
    }

    private val prefs: SharedPreferences by lazy { createPrefs() }

    private fun createPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "encrypted_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Hard-fail. Previous code silently fell back to a plaintext
            // SharedPreferences here, which made a one-line Keystore exception on
            // an obscure device cause undetectable cleartext storage of the
            // user's provider API keys. We'd rather crash loudly
            // and have the user update Play Services than ship plaintext secrets.
            Log.e(TAG, "EncryptedSharedPreferences init failed — refusing to fall back to plaintext", e)
            throw SecureStorageException(e)
        }
    }

    /** Key for the given provider id (see ProviderCatalog). Empty string when unset. */
    fun getProviderKey(providerId: String): String =
        prefs.getString(slotFor(providerId), "") ?: ""

    fun setProviderKey(providerId: String, key: String) {
        prefs.edit().putString(slotFor(providerId), key).apply()
    }

    fun getComposioKey(): String = prefs.getString(KEY_COMPOSIO, "") ?: ""

    fun setComposioKey(key: String) {
        prefs.edit().putString(KEY_COMPOSIO, key).apply()
    }

    /** Clear every secret in the encrypted store. Used by the Settings → Reset app flow
     *  (DPDP §13 / GDPR Art. 17 right to erasure). After calling, the app should also
     *  clear DataStore prefs and on-disk OpenClaw state. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "EncryptedKeyStore"
        private const val KEY_COMPOSIO = "composio_api_key"

        /** The NVIDIA slot keeps its historical name so keys saved by older
         *  installs survive the multi-provider migration. */
        private fun slotFor(providerId: String): String =
            if (providerId == "nim") "nvidia_api_key" else "provider_key_$providerId"
    }
}
