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

    companion object {
        private const val PREFS_NAME = "tts_preferences"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_COLLABORATIVE_MODE = "collaborative_mode"
        private const val KEY_AUTO_ADVANCE = "auto_advance"
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private const val DEFAULT_COLLABORATIVE_MODE = true  // Default ON
        private const val DEFAULT_AUTO_ADVANCE = true
    }
}
