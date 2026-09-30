package com.hakim3691.bta.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore-backed storage for Binance API credentials via
 * EncryptedSharedPreferences (AES-256 keys held in the Android Keystore).
 *
 * - Credentials never appear in logs, exceptions, or BuildConfig.
 * - Values are persisted encrypted at rest and only decrypted in-memory
 *   when a live-trading session is explicitly created.
 */
class CredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "bta_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(apiKey: String, apiSecret: String) {
        prefs.edit()
            .putString(KEY_API, apiKey.trim())
            .putString(KEY_SECRET, apiSecret.trim())
            .apply()
    }

    fun getApiKey(): String = prefs.getString(KEY_API, "") ?: ""

    fun getApiSecret(): String = prefs.getString(KEY_SECRET, "") ?: ""

    fun hasCredentials(): Boolean = getApiKey().isNotEmpty() && getApiSecret().isNotEmpty()

    /** Clears secrets from storage (used by "forget credentials" in Settings). */
    fun clear() {
        prefs.edit().remove(KEY_API).remove(KEY_SECRET).apply()
    }

    companion object {
        private const val KEY_API = "binance_api_key"
        private const val KEY_SECRET = "binance_api_secret"

        @Volatile
        private var instance: CredentialStore? = null

        fun getInstance(context: Context): CredentialStore =
            instance ?: synchronized(this) {
                instance ?: CredentialStore(context.applicationContext).also { instance = it }
            }
    }
}
