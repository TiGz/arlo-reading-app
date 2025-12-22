package com.example.arlo.games

import android.content.Context
import android.content.Intent

/**
 * Interface for game providers.
 * Each game (e.g., PixelWheels) implements this to integrate with Arlo.
 */
interface GameProvider {
    /** Unique identifier for this game */
    val gameId: String

    /** Display name shown in UI */
    val displayName: String

    /** Short description of the game */
    val description: String

    /** Resource ID for game icon */
    val iconResId: Int

    /** Check if the game is available (installed, etc.) */
    fun isAvailable(): Boolean

    /** Create an Intent to launch the game with the given session config */
    fun createLaunchIntent(context: Context, session: GameSession): Intent
}
