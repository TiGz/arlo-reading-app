package com.example.arlo

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages secure storage of the Anthropic API key using EncryptedSharedPreferences.
 */
class ApiKeyManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "arlo_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API, key).apply()
    }

    fun getApiKey(): String? {
        // DEV ONLY: Hardcoded key for testing - remove before release!
        val devKey = BuildConfig.ANTHROPIC_API_KEY
        Log.d(TAG, "BuildConfig.ANTHROPIC_API_KEY = '${devKey.take(20)}...' (length=${devKey.length})")
        if (devKey.isNotBlank() && devKey != "\"\"") {
            Log.d(TAG, "Using baked-in API key")
            return devKey
        }
        val storedKey = prefs.getString(KEY_API, null)
        Log.d(TAG, "Stored key present: ${storedKey != null}")
        return storedKey
    }

    fun hasApiKey(): Boolean {
        val has = !getApiKey().isNullOrBlank()
        Log.d(TAG, "hasApiKey() = $has")
        return has
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API).apply()
    }

    companion object {
        private const val TAG = "ApiKeyManager"
        private const val KEY_API = "anthropic_api_key"
    }
}
