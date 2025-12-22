package com.example.arlo.games

import com.example.arlo.data.DailyStats
import com.example.arlo.data.ParentSettings
import com.example.arlo.data.ReadingStatsRepository

/**
 * Manages game rewards based on reading progress.
 * Games are unlocked when daily reading goals are met.
 */
class GameRewardsManager(
    private val statsRepository: ReadingStatsRepository
) {
    /**
     * Check if the user is eligible for game rewards.
     */
    suspend fun checkGameRewardEligibility(): GameRewardState {
        val today = statsRepository.getTodayStats()
        val parentSettings = statsRepository.getParentSettings()

        // Check if game rewards are enabled by parent
        if (!parentSettings.gameRewardsEnabled) {
            return GameRewardState.NoRewardsAvailable
        }

        // Check if goal met for first time today
        if (today.goalMet && !today.gameRewardClaimed) {
            return GameRewardState.NewRewardAvailable(
                racesEarned = calculateRacesEarned(today, parentSettings)
            )
        }

        // Check for unclaimed races from today
        val unclaimedRaces = today.racesEarned - today.racesUsed
        if (unclaimedRaces > 0) {
            return GameRewardState.RacesAvailable(unclaimedRaces)
        }

        return GameRewardState.NoRewardsAvailable
    }

    /**
     * Calculate how many races were earned based on performance.
     * - 1 race for meeting daily goal
     * - +1 bonus for exceeding goal by 50%
     * - +1 bonus for streak of 5+ gold stars
     * Capped by parent's maxRacesPerDay setting.
     */
    private fun calculateRacesEarned(stats: DailyStats, settings: ParentSettings): Int {
        var races = 1  // Base: 1 race for meeting goal

        // Bonus race for exceeding goal by 50%
        if (stats.totalPoints >= (settings.dailyPointsTarget * 1.5).toInt()) {
            races++
        }

        // Bonus race for perfect streak (5+ gold stars in a row)
        if (stats.longestStreak >= 5) {
            races++
        }

        // Cap at parent's configured max (default 3)
        return races.coerceAtMost(settings.maxRacesPerDay)
    }

    /**
     * Claim the game reward, marking it as used.
     * Call this when user accepts the reward.
     */
    suspend fun claimReward(): GameSession? {
        val today = statsRepository.getTodayStats()
        val parentSettings = statsRepository.getParentSettings()

        android.util.Log.d("GameRewardsManager", "claimReward: goalMet=${today.goalMet}, gameRewardClaimed=${today.gameRewardClaimed}, racesEarned=${today.racesEarned}, racesUsed=${today.racesUsed}")
        android.util.Log.d("GameRewardsManager", "parentSettings: gameRewardsEnabled=${parentSettings.gameRewardsEnabled}, maxRacesPerDay=${parentSettings.maxRacesPerDay}")

        if (!today.goalMet) {
            android.util.Log.w("GameRewardsManager", "Goal not met - returning null")
            return null
        }

        val racesEarned = if (!today.gameRewardClaimed) {
            calculateRacesEarned(today, parentSettings)
        } else {
            today.racesEarned - today.racesUsed
        }

        android.util.Log.d("GameRewardsManager", "Calculated racesEarned: $racesEarned")

        if (racesEarned <= 0) {
            android.util.Log.w("GameRewardsManager", "No races earned - returning null")
            return null
        }

        // Update stats to mark reward as claimed
        statsRepository.claimGameReward(racesEarned)

        return GameSession.create(maxRaces = racesEarned)
    }

    /**
     * Record that a game session was completed.
     */
    suspend fun recordSessionComplete(result: GameSessionResult) {
        statsRepository.recordGameSessionUsed(result.racesCompleted)
    }
}
