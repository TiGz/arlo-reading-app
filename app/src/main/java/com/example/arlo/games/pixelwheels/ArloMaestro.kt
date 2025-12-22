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
import com.agateau.pixelwheels.gamesetup.GameInfo
import com.agateau.pixelwheels.gamesetup.Maestro
import com.agateau.pixelwheels.gamesetup.QuickRaceGameInfo
import com.agateau.pixelwheels.map.Track
import com.agateau.pixelwheels.racescreen.RaceScreen
import com.agateau.pixelwheels.screens.SelectTrackScreen
import com.agateau.pixelwheels.screens.SelectVehicleScreen
import com.badlogic.gdx.Screen
import com.badlogic.gdx.utils.Array as GdxArray

/**
 * Custom Maestro that:
 * 1. Tracks race completions against a limit
 * 2. Shows "Race X of Y" overlay (future enhancement)
 * 3. Triggers exit callback when limit reached
 * 4. Prevents access to main menu
 *
 * This is Arlo's orchestrator for race-limited PixelWheels sessions.
 */
class ArloMaestro(
    game: PwGame,
    playerCount: Int,
    private val onRaceFinished: (position: Int) -> Unit,
    private val maxRaces: Int,
    private val currentRaceCount: () -> Int
) : Maestro(game, playerCount) {

    private val gameInfoBuilder = QuickRaceGameInfo.Builder(
        game.assets.vehicleDefs,
        game.config
    )

    override fun start() {
        // Go directly to track selection (skip main menu)
        game.pushScreen(createSelectTrackScreen())
    }

    private fun createSelectTrackScreen(): Screen {
        val listener = object : SelectTrackScreen.Listener {
            override fun onBackPressed() {
                // Back from track selection = exit game entirely
                stopEnoughInputChecker()
                game.showMainMenu() // This will trigger our exit callback
            }

            override fun onTrackSelected(track: Track) {
                gameInfoBuilder.setTrack(track)
                game.replaceScreen(createSelectVehicleScreen())
            }
        }
        return SelectTrackScreen(game, listener)
    }

    private fun createSelectVehicleScreen(): Screen {
        val listener = object : SelectVehicleScreen.Listener {
            override fun onBackPressed() {
                game.replaceScreen(createSelectTrackScreen())
            }

            override fun onPlayerSelected(player: GameInfo.Player) {
                val players = GdxArray<GameInfo.Player>()
                players.add(player)
                gameInfoBuilder.setPlayers(players)
                game.replaceScreen(createRaceScreen())
            }
        }
        return SelectVehicleScreen(game, listener)
    }

    private fun createRaceScreen(): Screen {
        val gameInfo = gameInfoBuilder.build()

        val listener = object : RaceScreen.Listener {
            override fun onRestartPressed() {
                // Restart = same race, doesn't count toward limit
                (game.screen as? RaceScreen)?.forgetTrack()
                game.replaceScreen(createRaceScreen())
            }

            override fun onQuitPressed() {
                // Quit = exit game entirely
                stopEnoughInputChecker()
                game.showMainMenu()
            }

            override fun onNextTrackPressed() {
                // Race finished - notify callback with position (1 = first place)
                // Note: We pass 1 as position - could extract actual from race results if needed
                onRaceFinished(1)

                val completedRaces = currentRaceCount()
                if (completedRaces >= maxRaces) {
                    // All races done - exit to Arlo
                    stopEnoughInputChecker()
                    game.showMainMenu()
                } else {
                    // More races allowed - let them pick next track
                    game.replaceScreen(createSelectTrackScreen())
                }
            }
        }

        return RaceScreen(game, listener, gameInfo)
    }
}
