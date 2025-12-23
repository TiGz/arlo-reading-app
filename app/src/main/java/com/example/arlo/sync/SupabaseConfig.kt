package com.example.arlo.sync

import android.content.Context
import android.provider.Settings
import com.example.arlo.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions

object SupabaseConfig {

    private var isInitialized = false

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Functions)
        }.also { isInitialized = true }
    }

    /**
     * Check if Supabase is properly configured.
     * Returns false if credentials are missing.
     */
    fun isConfigured(): Boolean {
        return BuildConfig.SUPABASE_URL.isNotBlank() &&
               BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    }

    /**
     * Get the Android device ID for identifying this tablet.
     * This ID persists across app reinstalls but not factory resets.
     */
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }
}
