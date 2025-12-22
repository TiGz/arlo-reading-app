package com.example.arlo.games

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences-based manager for race credits.
 * Single source of truth for available races.
 *
 * Design:
 * - Reading app INCREMENTS when races are earned
 * - PixelWheels DECREMENTS when a race is completed
 */
class RaceCreditsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get current available race credits.
     */
    fun getAvailableRaces(): Int {
        return prefs.getInt(KEY_AVAILABLE_RACES, 0)
    }

    /**
     * Add race credits (called by reading app when races are earned).
     */
    fun addRaces(count: Int) {
        val current = getAvailableRaces()
        val newTotal = current + count
        android.util.Log.d(TAG, "addRaces: $current + $count = $newTotal")
        prefs.edit().putInt(KEY_AVAILABLE_RACES, newTotal).apply()
    }

    /**
     * Use one race credit (called by PixelWheels when a race is completed).
     * Returns true if credit was available and used, false if no credits.
     */
    fun useOneRace(): Boolean {
        val current = getAvailableRaces()
        if (current <= 0) {
            android.util.Log.w(TAG, "useOneRace: No credits available")
            return false
        }
        val newTotal = current - 1
        android.util.Log.d(TAG, "useOneRace: $current -> $newTotal")
        prefs.edit().putInt(KEY_AVAILABLE_RACES, newTotal).apply()
        return true
    }

    /**
     * Set race credits to a specific value (for initialization/reset).
     */
    fun setRaces(count: Int) {
        android.util.Log.d(TAG, "setRaces: $count")
        prefs.edit().putInt(KEY_AVAILABLE_RACES, count.coerceAtLeast(0)).apply()
    }

    /**
     * Sync race credits from database values.
     * Call this when races are earned to ensure SharedPreferences stays in sync.
     *
     * @param racesEarned Total races earned today (from DB)
     * @param racesUsed Total races used today (from DB)
     */
    fun syncFromDatabase(racesEarned: Int, racesUsed: Int) {
        val available = (racesEarned - racesUsed).coerceAtLeast(0)
        android.util.Log.d(TAG, "syncFromDatabase: earned=$racesEarned, used=$racesUsed, available=$available")
        prefs.edit().putInt(KEY_AVAILABLE_RACES, available).apply()
    }

    companion object {
        private const val TAG = "RaceCreditsManager"
        private const val PREFS_NAME = "race_credits"
        private const val KEY_AVAILABLE_RACES = "available_races"

        @Volatile
        private var instance: RaceCreditsManager? = null

        fun getInstance(context: Context): RaceCreditsManager {
            return instance ?: synchronized(this) {
                instance ?: RaceCreditsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
