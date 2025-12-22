/*
 * Copyright 2024 Arlo Reading App
 *
 * This file is part of Pixel Wheels.
 *
 * Pixel Wheels is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.agateau.pixelwheels;

/**
 * Interface for race-limited game sessions.
 * Implementations track races completed against a maximum limit.
 *
 * ARLO MODIFICATION - This interface allows PixelWheels UI components
 * to check race limits without depending on Arlo's RaceLimitedPwGame class.
 */
public interface RaceLimitedGame {
    /**
     * Returns the number of races remaining in this session.
     * @return races remaining (0 or more)
     */
    int getRacesRemaining();

    /**
     * Returns true if the player has races remaining.
     * @return true if more races can be played
     */
    boolean hasRacesRemaining();
}
