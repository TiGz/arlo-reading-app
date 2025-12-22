/*
 * Copyright 2024 Arlo Reading App
 *
 * This file is part of Arlo Reading App.
 *
 * Arlo Reading App is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.example.arlo.games.pixelwheels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.example.arlo.games.GameSession

/**
 * Activity that hosts PixelWheels with race-limiting enforcement.
 *
 * Key responsibilities:
 * 1. Launch RaceLimitedPwGame instead of standard PwGame
 * 2. Track race completions via ArloMaestro callbacks
 * 3. Enforce exit after maxRaces reached
 * 4. Return race count to Arlo via Activity result
 */
class PixelWheelsActivity : AndroidApplication() {

    private var maxRaces: Int = 1
    private var racesCompleted: Int = 0
    private var bestPosition: Int = Int.MAX_VALUE  // Track best finishing position
    private var sessionId: String = ""
    private var startedAt: Long = 0

    private lateinit var game: RaceLimitedPwGame

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("PixelWheelsActivity", "onCreate called")

        // Keep screen on during game
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Extract session config from intent
        maxRaces = intent.getIntExtra(EXTRA_MAX_RACES, 1)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        startedAt = System.currentTimeMillis()

        android.util.Log.d("PixelWheelsActivity", "maxRaces=$maxRaces, sessionId=$sessionId")

        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            hideStatusBar = true
            useWakelock = true
            useAccelerometer = false
            useCompass = false
        }

        android.util.Log.d("PixelWheelsActivity", "Creating RaceLimitedPwGame...")

        // Create our race-limited game variant
        game = RaceLimitedPwGame(
            maxRaces = maxRaces,
            onRaceComplete = { position -> handleRaceComplete(position) },
            onAllRacesComplete = { finishWithResult() },
            onGameExit = { finishWithResult() }
        )

        android.util.Log.d("PixelWheelsActivity", "Calling initialize(game, config)...")
        initialize(game, config)
        android.util.Log.d("PixelWheelsActivity", "initialize complete")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Maintain immersive mode
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun handleRaceComplete(position: Int) {
        racesCompleted++
        // Track best finishing position (lower is better)
        if (position < bestPosition) {
            bestPosition = position
        }
        // ArloMaestro handles the flow - if this was the last race, onAllRacesComplete will be called
    }

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            putExtra(RESULT_RACES_COMPLETED, racesCompleted)
            putExtra(RESULT_BEST_POSITION, if (bestPosition == Int.MAX_VALUE) 1 else bestPosition)
            putExtra(RESULT_SESSION_ID, sessionId)
            putExtra(RESULT_STARTED_AT, startedAt)
            putExtra(RESULT_ENDED_AT, System.currentTimeMillis())
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // For kids app, back press just exits the game
        finishWithResult()
    }

    companion object {
        const val EXTRA_MAX_RACES = "max_races"
        const val EXTRA_SESSION_ID = "session_id"
        const val RESULT_RACES_COMPLETED = "races_completed"
        const val RESULT_BEST_POSITION = "best_position"
        const val RESULT_SESSION_ID = "session_id"
        const val RESULT_STARTED_AT = "started_at"
        const val RESULT_ENDED_AT = "ended_at"

        fun createIntent(context: Context, session: GameSession): Intent {
            return Intent(context, PixelWheelsActivity::class.java).apply {
                putExtra(EXTRA_MAX_RACES, session.maxRaces)
                putExtra(EXTRA_SESSION_ID, session.sessionId)
            }
        }
    }
}
