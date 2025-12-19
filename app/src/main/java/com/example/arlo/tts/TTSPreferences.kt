package com.example.arlo.tts

import android.content.Context
import android.content.SharedPreferences

class TTSPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_SPEECH_RATE, rate.coerceIn(0.25f, 1.0f)).apply()
    }

    fun getSpeechRate(): Float {
        return prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
    }

    fun saveCollaborativeMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COLLABORATIVE_MODE, enabled).apply()
    }

    fun getCollaborativeMode(): Boolean {
        return prefs.getBoolean(KEY_COLLABORATIVE_MODE, DEFAULT_COLLABORATIVE_MODE)
    }

    fun saveAutoAdvance(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ADVANCE, enabled).apply()
    }

    fun getAutoAdvance(): Boolean {
        return prefs.getBoolean(KEY_AUTO_ADVANCE, DEFAULT_AUTO_ADVANCE)
    }

    /**
     * Kid mode locks the app into collaborative reading mode.
     * When enabled, hides the collaborative toggle button (can't turn off collaborative mode).
     * Voice and speed controls remain accessible.
     * Unlocked via 5 taps on the book title (secret gesture for parents).
     */
    fun saveKidMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KID_MODE, enabled).apply()
    }

    fun getKidMode(): Boolean {
        return prefs.getBoolean(KEY_KID_MODE, DEFAULT_KID_MODE)
    }

    fun saveKokoroVoice(voice: String) {
        prefs.edit().putString(KEY_KOKORO_VOICE, voice).apply()
    }

    fun getKokoroVoice(): String {
        return prefs.getString(KEY_KOKORO_VOICE, DEFAULT_KOKORO_VOICE) ?: DEFAULT_KOKORO_VOICE
    }

    /**
     * Check if the currently selected voice is a Kokoro voice (requires network).
     * Kokoro voices start with bf_ (British female) or bm_ (British male).
     * On-device Android TTS voices don't need pre-caching.
     */
    fun isKokoroVoice(): Boolean {
        val voice = getKokoroVoice()
        return voice.startsWith("bf_") || voice.startsWith("bm_")
    }

    companion object {
        private const val PREFS_NAME = "tts_preferences"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_COLLABORATIVE_MODE = "collaborative_mode"
        private const val KEY_AUTO_ADVANCE = "auto_advance"
        private const val KEY_KOKORO_VOICE = "kokoro_voice"
        private const val KEY_KID_MODE = "kid_mode"
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private const val DEFAULT_COLLABORATIVE_MODE = true  // Default ON
        private const val DEFAULT_AUTO_ADVANCE = false  // Default OFF - one sentence at a time
        private const val DEFAULT_KOKORO_VOICE = "bm_lewis"  // British male (Kokoro)
        private const val DEFAULT_KID_MODE = true  // Default ON - kids locked into collaborative mode
    }
}
