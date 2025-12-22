package com.example.arlo.games

/**
 * Configuration for a game reward session.
 * Defines limits and settings for a single play session.
 */
data class GameSession(
    val sessionId: String,
    val maxRaces: Int,
    val difficulty: Difficulty = Difficulty.EASY,
    val earnedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(maxRaces: Int, difficulty: Difficulty = Difficulty.EASY): GameSession {
            return GameSession(
                sessionId = java.util.UUID.randomUUID().toString(),
                maxRaces = maxRaces,
                difficulty = difficulty
            )
        }
    }
}

enum class Difficulty {
    EASY,
    MEDIUM,
    HARD
}

/**
 * Result returned when a game session completes.
 */
data class GameSessionResult(
    val sessionId: String,
    val racesCompleted: Int,
    val bestPosition: Int = 1,  // Best finishing position (1st, 2nd, etc.)
    val completedAt: Long = System.currentTimeMillis()
)

/**
 * State of game reward eligibility.
 */
sealed class GameRewardState {
    /** New reward just earned - show celebration */
    data class NewRewardAvailable(val racesEarned: Int) : GameRewardState()

    /** Has unclaimed races from today */
    data class RacesAvailable(val racesRemaining: Int) : GameRewardState()

    /** No rewards available - need to meet daily goal */
    object NoRewardsAvailable : GameRewardState()
}
