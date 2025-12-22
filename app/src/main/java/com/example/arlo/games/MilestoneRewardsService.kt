package com.example.arlo.games

import android.util.Log
import com.example.arlo.data.EarnedMilestone
import com.example.arlo.data.MilestoneCheckResult
import com.example.arlo.data.MilestoneType
import com.example.arlo.data.ReadingStatsRepository

/**
 * Central coordinator for all milestone rewards.
 * Checks and awards milestones based on reading progress.
 */
class MilestoneRewardsService(
    private val statsRepository: ReadingStatsRepository,
    private val raceCreditsManager: RaceCreditsManager? = null
) {
    companion object {
        private const val TAG = "MilestoneRewardsService"
    }

    /**
     * Check and award instant milestones after a successful collaborative attempt.
     * Called after each collaborative success.
     *
     * @param currentPoints Total points earned today
     * @param currentStreak Current streak of consecutive correct answers
     * @return MilestoneCheckResult with any newly earned milestones
     */
    suspend fun checkAndAwardInstantMilestones(
        currentPoints: Int,
        currentStreak: Int
    ): MilestoneCheckResult {
        val settings = statsRepository.getParentSettings()
        val earnedMilestones = mutableListOf<EarnedMilestone>()
        var totalRacesAwarded = 0

        // Get current daily race total to check against cap
        val currentRacesToday = statsRepository.getTotalRacesAwardedToday()
        val maxRacesPerDay = settings.maxRacesPerDay
        val racesRemaining = (maxRacesPerDay - currentRacesToday).coerceAtLeast(0)

        if (racesRemaining == 0) {
            Log.d(TAG, "Daily race cap reached ($maxRacesPerDay), no more milestones can be claimed today")
            return MilestoneCheckResult(earnedMilestones, 0)
        }

        // 1. Daily Target Milestone
        val dailyTarget = settings.dailyPointsTarget
        if (currentPoints >= dailyTarget && settings.racesForDailyTarget > 0) {
            if (!statsRepository.isMilestoneClaimed(MilestoneType.DAILY_TARGET, "REACHED")) {
                val racesToAward = minOf(settings.racesForDailyTarget, racesRemaining - totalRacesAwarded)
                if (racesToAward > 0) {
                    statsRepository.claimMilestone(MilestoneType.DAILY_TARGET, "REACHED", racesToAward)
                    statsRepository.updateMilestoneRaceSources(racesFromDailyTarget = racesToAward)
                    earnedMilestones.add(EarnedMilestone(
                        type = MilestoneType.DAILY_TARGET,
                        milestoneId = "REACHED",
                        racesAwarded = racesToAward,
                        displayMessage = "Daily goal reached! +$racesToAward race${if (racesToAward > 1) "s" else ""}"
                    ))
                    totalRacesAwarded += racesToAward
                    Log.d(TAG, "Awarded DAILY_TARGET milestone: $racesToAward races")
                }
            }
        }

        // 2. Score Multiple Milestones (2x, 3x, 4x, etc.)
        if (settings.racesPerMultiple > 0 && dailyTarget > 0) {
            val currentMultiple = currentPoints / dailyTarget
            val lastClaimedMultiple = statsRepository.getHighestMultipleClaimedToday()

            for (multiple in (lastClaimedMultiple + 1)..currentMultiple) {
                if (multiple >= 2) {  // Only award for 2x and above
                    val racesToAward = minOf(settings.racesPerMultiple, racesRemaining - totalRacesAwarded)
                    if (racesToAward > 0) {
                        val milestoneId = "${multiple}X"
                        statsRepository.claimMilestone(MilestoneType.MULTIPLE, milestoneId, racesToAward)
                        statsRepository.updateMilestoneRaceSources(racesFromMultiples = racesToAward)
                        earnedMilestones.add(EarnedMilestone(
                            type = MilestoneType.MULTIPLE,
                            milestoneId = milestoneId,
                            racesAwarded = racesToAward,
                            displayMessage = "${multiple}x points! +$racesToAward race${if (racesToAward > 1) "s" else ""}"
                        ))
                        totalRacesAwarded += racesToAward
                        Log.d(TAG, "Awarded MULTIPLE milestone $milestoneId: $racesToAward races")
                    }
                }
            }
        }

        // 3. Streak Milestones (every N correct in a row)
        if (settings.racesPerStreakAchievement > 0 && settings.streakThreshold > 0) {
            val streakThreshold = settings.streakThreshold
            val lastClaimedStreakMilestone = statsRepository.getHighestStreakMilestoneToday()

            // Check for streak milestones (5, 10, 15, etc.)
            var streakMilestone = streakThreshold
            while (streakMilestone <= currentStreak) {
                if (streakMilestone > lastClaimedStreakMilestone) {
                    val racesToAward = minOf(settings.racesPerStreakAchievement, racesRemaining - totalRacesAwarded)
                    if (racesToAward > 0) {
                        val milestoneId = streakMilestone.toString()
                        statsRepository.claimMilestone(MilestoneType.STREAK, milestoneId, racesToAward)
                        statsRepository.updateMilestoneRaceSources(racesFromStreaks = racesToAward)
                        earnedMilestones.add(EarnedMilestone(
                            type = MilestoneType.STREAK,
                            milestoneId = milestoneId,
                            racesAwarded = racesToAward,
                            displayMessage = "$streakMilestone in a row! +$racesToAward race${if (racesToAward > 1) "s" else ""}"
                        ))
                        totalRacesAwarded += racesToAward
                        Log.d(TAG, "Awarded STREAK milestone $milestoneId: $racesToAward races")
                    }
                }
                streakMilestone += streakThreshold
            }
        }

        // Update racesEarned in daily stats and sync to SharedPreferences
        if (totalRacesAwarded > 0) {
            val todayStats = statsRepository.getTodayStats()
            val newRacesEarned = todayStats.racesEarned + totalRacesAwarded
            statsRepository.claimGameReward(newRacesEarned)
            // Sync to SharedPreferences (single source of truth for PixelWheels)
            raceCreditsManager?.syncFromDatabase(newRacesEarned, todayStats.racesUsed)
        }

        return MilestoneCheckResult(earnedMilestones, totalRacesAwarded)
    }

    /**
     * Check for page completion milestone.
     * Called when navigating away from a page.
     *
     * @param bookId The book being read
     * @param pageId The page that was just completed
     * @return EarnedMilestone if page completion threshold was met, null otherwise
     */
    suspend fun checkPageCompletion(bookId: Long, pageId: Long): EarnedMilestone? {
        val settings = statsRepository.getParentSettings()
        if (settings.racesPerPageCompletion <= 0) return null

        // Check if already claimed for this page today
        val milestoneId = pageId.toString()
        if (statsRepository.isMilestoneClaimed(MilestoneType.PAGE, milestoneId)) {
            return null
        }

        // Get completion rate
        val completionRate = statsRepository.getPageCompletionRate(bookId, pageId)
        if (completionRate < settings.completionThreshold) {
            Log.d(TAG, "Page $pageId completion rate $completionRate < threshold ${settings.completionThreshold}")
            return null
        }

        // Check daily cap
        val currentRacesToday = statsRepository.getTotalRacesAwardedToday()
        val racesRemaining = (settings.maxRacesPerDay - currentRacesToday).coerceAtLeast(0)
        val racesToAward = minOf(settings.racesPerPageCompletion, racesRemaining)

        if (racesToAward <= 0) {
            Log.d(TAG, "Daily race cap reached, cannot award page completion")
            return null
        }

        // Award the milestone
        statsRepository.claimMilestone(MilestoneType.PAGE, milestoneId, racesToAward)
        statsRepository.updateMilestoneRaceSources(racesFromPages = racesToAward)

        // Update racesEarned and sync to SharedPreferences
        val todayStats = statsRepository.getTodayStats()
        val newRacesEarned = todayStats.racesEarned + racesToAward
        statsRepository.claimGameReward(newRacesEarned)
        raceCreditsManager?.syncFromDatabase(newRacesEarned, todayStats.racesUsed)

        Log.d(TAG, "Awarded PAGE completion milestone for page $pageId: $racesToAward races")

        return EarnedMilestone(
            type = MilestoneType.PAGE,
            milestoneId = milestoneId,
            racesAwarded = racesToAward,
            displayMessage = "Page complete! +$racesToAward race${if (racesToAward > 1) "s" else ""}"
        )
    }

    /**
     * Check for chapter completion milestone.
     * Called when chapter changes.
     *
     * @param bookId The book being read
     * @param chapterTitle The chapter that was just completed
     * @return EarnedMilestone if chapter completion threshold was met, null otherwise
     */
    suspend fun checkChapterCompletion(bookId: Long, chapterTitle: String): EarnedMilestone? {
        val settings = statsRepository.getParentSettings()
        if (settings.racesPerChapterCompletion <= 0) return null

        // Check if already claimed for this chapter today
        val milestoneId = "${bookId}_$chapterTitle"
        if (statsRepository.isMilestoneClaimed(MilestoneType.CHAPTER, milestoneId)) {
            return null
        }

        // Get completion rate
        val completionRate = statsRepository.getChapterCompletionRate(bookId, chapterTitle)
        if (completionRate < settings.completionThreshold) {
            Log.d(TAG, "Chapter '$chapterTitle' completion rate $completionRate < threshold ${settings.completionThreshold}")
            return null
        }

        // Check daily cap
        val currentRacesToday = statsRepository.getTotalRacesAwardedToday()
        val racesRemaining = (settings.maxRacesPerDay - currentRacesToday).coerceAtLeast(0)
        val racesToAward = minOf(settings.racesPerChapterCompletion, racesRemaining)

        if (racesToAward <= 0) {
            Log.d(TAG, "Daily race cap reached, cannot award chapter completion")
            return null
        }

        // Award the milestone
        statsRepository.claimMilestone(MilestoneType.CHAPTER, milestoneId, racesToAward)
        statsRepository.updateMilestoneRaceSources(racesFromChapters = racesToAward)

        // Update racesEarned and sync to SharedPreferences
        val todayStats = statsRepository.getTodayStats()
        val newRacesEarned = todayStats.racesEarned + racesToAward
        statsRepository.claimGameReward(newRacesEarned)
        raceCreditsManager?.syncFromDatabase(newRacesEarned, todayStats.racesUsed)

        Log.d(TAG, "Awarded CHAPTER completion milestone for '$chapterTitle': $racesToAward races")

        return EarnedMilestone(
            type = MilestoneType.CHAPTER,
            milestoneId = milestoneId,
            racesAwarded = racesToAward,
            displayMessage = "Chapter complete! +$racesToAward race${if (racesToAward > 1) "s" else ""}"
        )
    }

    /**
     * Check for book completion milestone.
     * Called when reaching the last page of a book.
     *
     * @param bookId The book that was completed
     * @return EarnedMilestone if book completion threshold was met, null otherwise
     */
    suspend fun checkBookCompletion(bookId: Long): EarnedMilestone? {
        val settings = statsRepository.getParentSettings()
        if (settings.racesPerBookCompletion <= 0) return null

        // Check if already claimed for this book today
        val milestoneId = bookId.toString()
        if (statsRepository.isMilestoneClaimed(MilestoneType.BOOK, milestoneId)) {
            return null
        }

        // Get completion rate
        val completionRate = statsRepository.getBookCompletionRate(bookId)
        if (completionRate < settings.completionThreshold) {
            Log.d(TAG, "Book $bookId completion rate $completionRate < threshold ${settings.completionThreshold}")
            return null
        }

        // Check daily cap
        val currentRacesToday = statsRepository.getTotalRacesAwardedToday()
        val racesRemaining = (settings.maxRacesPerDay - currentRacesToday).coerceAtLeast(0)
        val racesToAward = minOf(settings.racesPerBookCompletion, racesRemaining)

        if (racesToAward <= 0) {
            Log.d(TAG, "Daily race cap reached, cannot award book completion")
            return null
        }

        // Award the milestone
        statsRepository.claimMilestone(MilestoneType.BOOK, milestoneId, racesToAward)
        statsRepository.updateMilestoneRaceSources(racesFromBooks = racesToAward)

        // Update racesEarned and sync to SharedPreferences
        val todayStats = statsRepository.getTodayStats()
        val newRacesEarned = todayStats.racesEarned + racesToAward
        statsRepository.claimGameReward(newRacesEarned)
        raceCreditsManager?.syncFromDatabase(newRacesEarned, todayStats.racesUsed)

        Log.d(TAG, "Awarded BOOK completion milestone for book $bookId: $racesToAward races")

        return EarnedMilestone(
            type = MilestoneType.BOOK,
            milestoneId = milestoneId,
            racesAwarded = racesToAward,
            displayMessage = "Book complete! +$racesToAward race${if (racesToAward > 1) "s" else ""}"
        )
    }

    /**
     * Get total unclaimed races available (for game unlock dialog).
     */
    suspend fun getUnclaimedRaces(): Int {
        val todayStats = statsRepository.getTodayStats()
        return (todayStats.racesEarned - todayStats.racesUsed).coerceAtLeast(0)
    }

    /**
     * Check if user has any unclaimed races.
     */
    suspend fun hasUnclaimedRaces(): Boolean {
        return getUnclaimedRaces() > 0
    }
}
