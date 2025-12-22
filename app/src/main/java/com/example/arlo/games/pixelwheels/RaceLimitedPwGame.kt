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
    private val onRaceComplete: (position: Int) -> Unit,
    private val onAllRacesComplete: () -> Unit,
    private val onGameExit: () -> Unit
) : PwGame() {

    private var racesCompleted = 0
    private var maestro: ArloMaestro? = null

    override fun create() {
        super.create()
        // Override: Skip main menu, start quick race immediately
        startArloQuickRace()
    }

    private fun startArloQuickRace() {
        // Use our custom maestro that handles race limits
        maestro = ArloMaestro(
            game = this,
            playerCount = 1, // Single player only for kids
            onRaceFinished = { position ->
                racesCompleted++
                onRaceComplete(position)

                if (racesCompleted >= maxRaces) {
                    // All races done - exit game
                    onAllRacesComplete()
                }
                // Otherwise ArloMaestro handles next race
            },
            maxRaces = maxRaces,
            currentRaceCount = { racesCompleted }
        )
        maestro?.start()
    }

    override fun showMainMenu() {
        // Override: Block return to main menu
        // When user tries to quit, we exit the game entirely
        onGameExit()
    }

    fun getRacesCompleted(): Int = racesCompleted
}
