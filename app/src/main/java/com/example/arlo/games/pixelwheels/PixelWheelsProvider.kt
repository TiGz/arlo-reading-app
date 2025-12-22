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
import com.example.arlo.R
import com.example.arlo.games.GameProvider
import com.example.arlo.games.GameSession
import com.example.arlo.games.GameSessionResult

/**
 * GameProvider implementation for PixelWheels racing game.
 */
class PixelWheelsProvider : GameProvider {

    override val gameId: String = "pixelwheels"

    override val displayName: String = "Pixel Wheels"

    override val description: String = "Race cartoon cars on fun tracks!"

    override val iconResId: Int = R.drawable.ic_game_racing

    override fun isAvailable(): Boolean {
        // PixelWheels is embedded in the app, always available
        return true
    }

    override fun createLaunchIntent(context: Context, session: GameSession): Intent {
        return PixelWheelsActivity.createIntent(context, session)
    }

    /**
     * Parse the result from PixelWheelsActivity.
     */
    fun parseResult(data: Intent?): GameSessionResult {
        if (data == null) {
            return GameSessionResult(
                sessionId = "",
                racesCompleted = 0,
                bestPosition = 0
            )
        }
        return GameSessionResult(
            sessionId = data.getStringExtra(PixelWheelsActivity.RESULT_SESSION_ID) ?: "",
            racesCompleted = data.getIntExtra(PixelWheelsActivity.RESULT_RACES_COMPLETED, 0),
            bestPosition = data.getIntExtra(PixelWheelsActivity.RESULT_BEST_POSITION, 1),
            completedAt = data.getLongExtra(PixelWheelsActivity.RESULT_ENDED_AT, System.currentTimeMillis())
        )
    }
}
