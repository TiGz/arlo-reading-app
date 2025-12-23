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

import com.agateau.pixelwheels.PwGame
import com.agateau.pixelwheels.RaceLimitedGame
import com.agateau.pixelwheels.gamesetup.Difficulty as PwDifficulty
import com.example.arlo.games.Difficulty as ArloDifficulty

/**
 * Modified PwGame that:
 * 1. Skips main menu, goes directly to quick race
 * 2. Uses ArloMaestro instead of QuickRaceMaestro
 * 3. Blocks return to main menu (enforces race-only mode)
 *
 * Architecture note: This is a Java/Kotlin interop class.
 * PwGame is Java, so we extend it in Kotlin and override key methods.
 */
class RaceLimitedPwGame(
    private val maxRaces: Int,
    private val difficulty: ArloDifficulty = ArloDifficulty.BEGINNER,
    private val onRaceComplete: (position: Int) -> Unit,
    private val onAllRacesComplete: () -> Unit,
    private val onGameExit: () -> Unit
) : PwGame(), RaceLimitedGame {

    private var racesCompleted = 0
    private var maestro: ArloMaestro? = null
    private var isInitialized = false  // Track if we've completed initialization

    override fun create() {
        android.util.Log.d("RaceLimitedPwGame", "create() called - calling super.create()")
        super.create()
        android.util.Log.d("RaceLimitedPwGame", "super.create() complete - applying difficulty: $difficulty")

        // Apply difficulty to game config - maps Arlo difficulty to PixelWheels difficulty
        val pwDifficulty = when (difficulty) {
            ArloDifficulty.BEGINNER -> PwDifficulty.BEGINNER
            ArloDifficulty.TRAINING -> PwDifficulty.TRAINING
            ArloDifficulty.EASY -> PwDifficulty.EASY
            ArloDifficulty.MEDIUM -> PwDifficulty.MEDIUM
            ArloDifficulty.HARD -> PwDifficulty.HARD
        }
        config.difficulty = pwDifficulty
        android.util.Log.d("RaceLimitedPwGame", "Set config.difficulty to: ${config.difficulty}")

        // Override: Skip main menu, start quick race immediately
        startArloQuickRace()
        isInitialized = true
        android.util.Log.d("RaceLimitedPwGame", "Initialization complete, isInitialized=true")
    }

    private fun startArloQuickRace() {
        android.util.Log.d("RaceLimitedPwGame", "startArloQuickRace() - creating ArloMaestro with maxRaces=$maxRaces")
        // Use our custom maestro that handles race limits
        maestro = ArloMaestro(
            game = this,
            playerCount = 1, // Single player only for kids
            onRaceFinished = { position ->
                racesCompleted++
                android.util.Log.d("RaceLimitedPwGame", "Race finished! position=$position, racesCompleted=$racesCompleted/$maxRaces")
                onRaceComplete(position)

                if (racesCompleted >= maxRaces) {
                    // All races done - exit game
                    android.util.Log.d("RaceLimitedPwGame", "All races complete - calling onAllRacesComplete")
                    onAllRacesComplete()
                }
                // Otherwise ArloMaestro handles next race
            },
            maxRaces = maxRaces,
            currentRaceCount = { racesCompleted }
        )
        android.util.Log.d("RaceLimitedPwGame", "ArloMaestro created - calling start()")
        maestro?.start()
        android.util.Log.d("RaceLimitedPwGame", "ArloMaestro.start() complete")
    }

    override fun showMainMenu() {
        android.util.Log.d("RaceLimitedPwGame", "showMainMenu() called - isInitialized=$isInitialized")
        // PwGame.create() calls showMainMenu() internally during initialization
        // Only trigger exit if we've completed our initialization
        if (isInitialized) {
            android.util.Log.d("RaceLimitedPwGame", "Triggering onGameExit")
            onGameExit()
        } else {
            android.util.Log.d("RaceLimitedPwGame", "Ignoring showMainMenu during initialization")
            // Do nothing - we'll start ArloQuickRace after super.create() returns
        }
    }

    fun getRacesCompleted(): Int = racesCompleted

    override fun getRacesRemaining(): Int = (maxRaces - racesCompleted).coerceAtLeast(0)

    override fun hasRacesRemaining(): Boolean = racesCompleted < maxRaces
}
