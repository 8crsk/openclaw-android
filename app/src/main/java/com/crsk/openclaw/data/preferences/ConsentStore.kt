package com.crsk.openclaw.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.consentDataStore by preferencesDataStore(name = "consents")

/**
 * The consents the user explicitly agreed to during the setup wizard.
 *
 * Versioned: any time we add a new consent category or materially change the
 * privacy policy, bump [CURRENT_CONSENT_VERSION] and the app will re-prompt on
 * next launch (the wizard checks [ConsentStore.needsReconsent]).
 *
 * Persisted with the timestamp it was granted at, plus the IP-country at consent
 * time, so we can prove the lawful basis under DPDP §6 / GDPR Art. 7 audit.
 */
data class ConsentRecord(
    val version: Int,
    val aiProcessingConsent: Boolean,        // required to use the agent
    val analyticsConsent: Boolean,           // legacy — telemetry was removed; nothing reads this
    val termsAccepted: Boolean,              // 18+ + ToS + Privacy
    val grantedAtEpochMs: Long,
    val grantedFromCountry: String,          // ISO-3166 alpha-2; empty if unknown
)

@Singleton
class ConsentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Bump whenever the privacy policy changes materially or a new consent category is added. */
    val currentVersion: Int = CURRENT_CONSENT_VERSION

    private val versionKey = intPreferencesKey("consent_version")
    private val aiKey = booleanPreferencesKey("consent_ai_processing")
    private val analyticsKey = booleanPreferencesKey("consent_analytics")
    private val termsKey = booleanPreferencesKey("consent_terms")
    private val grantedAtKey = longPreferencesKey("consent_granted_at")
    private val grantedCountryKey = stringPreferencesKey("consent_granted_country")

    /** Live observable record. `null` = no consent has been granted at all. */
    val record: Flow<ConsentRecord?> = context.consentDataStore.data.map { prefs ->
        val v = prefs[versionKey] ?: return@map null
        ConsentRecord(
            version = v,
            aiProcessingConsent = prefs[aiKey] ?: false,
            analyticsConsent = prefs[analyticsKey] ?: false,
            termsAccepted = prefs[termsKey] ?: false,
            grantedAtEpochMs = prefs[grantedAtKey] ?: 0L,
            grantedFromCountry = prefs[grantedCountryKey].orEmpty(),
        )
    }

    /** Returns true if the user has never consented, or consented to an older policy version. */
    val needsReconsent: Flow<Boolean> = context.consentDataStore.data.map { prefs ->
        val v = prefs[versionKey] ?: return@map true
        v < CURRENT_CONSENT_VERSION
    }

    /** Legacy analytics opt-in. Telemetry was removed with the cloud stack; kept for record shape. */
    val analyticsConsentGranted: Flow<Boolean> = context.consentDataStore.data.map { it[analyticsKey] ?: false }

    suspend fun grant(
        aiProcessingConsent: Boolean,
        analyticsConsent: Boolean,
        termsAccepted: Boolean,
        grantedFromCountry: String = "",
    ) {
        context.consentDataStore.edit { prefs ->
            prefs[versionKey] = CURRENT_CONSENT_VERSION
            prefs[aiKey] = aiProcessingConsent
            prefs[analyticsKey] = analyticsConsent
            prefs[termsKey] = termsAccepted
            prefs[grantedAtKey] = System.currentTimeMillis()
            prefs[grantedCountryKey] = grantedFromCountry
        }
    }

    /** Withdraw all consents. Used by Settings → Privacy → Withdraw consent. */
    suspend fun withdrawAll() {
        context.consentDataStore.edit { it.clear() }
    }

    companion object {
        const val CURRENT_CONSENT_VERSION = 1
    }
}
