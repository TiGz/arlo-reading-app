package com.example.arlo.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingStatsDao {

    // ==================== DAILY STATS ====================

    @Query("SELECT * FROM daily_stats WHERE date = :date")
    suspend fun getDailyStats(date: String): DailyStats?

    @Query("SELECT * FROM daily_stats WHERE date = :date")
    fun observeDailyStats(date: String): Flow<DailyStats?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyStats(stats: DailyStats)

    @Query("SELECT * FROM daily_stats ORDER BY date DESC LIMIT :days")
    suspend fun getRecentDailyStats(days: Int): List<DailyStats>

    @Query("SELECT * FROM daily_stats WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    suspend fun getDailyStatsRange(startDate: String, endDate: String): List<DailyStats>

    /**
     * Finalize all past days that haven't been finalized yet.
     * Sets goalMetFinal based on whether totalPoints >= dailyPointsTarget for that day.
     * Only affects days before the specified date where goalMetFinal is still null.
     */
    @Query("""
        UPDATE daily_stats
        SET goalMetFinal = (totalPoints >= dailyPointsTarget)
        WHERE date < :todayDate AND goalMetFinal IS NULL
    """)
    suspend fun finalizePastDays(todayDate: String)

    @Query("SELECT SUM(starsEarned) FROM daily_stats")
    fun observeTotalStars(): Flow<Int?>

    @Query("SELECT SUM(starsEarned) FROM daily_stats")
    suspend fun getTotalStars(): Int?

    @Query("SELECT SUM(perfectWords) FROM daily_stats")
    suspend fun getTotalPerfectWords(): Int?

    @Query("SELECT SUM(sentencesRead) FROM daily_stats")
    suspend fun getTotalSentencesRead(): Int?

    // ==================== DIFFICULT WORDS ====================

    @Query("SELECT * FROM difficult_words WHERE normalizedWord = :normalizedWord LIMIT 1")
    suspend fun getDifficultWord(normalizedWord: String): DifficultWord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDifficultWord(word: DifficultWord)

    @Query("SELECT * FROM difficult_words WHERE masteryLevel < 5 ORDER BY successfulAttempts * 1.0 / totalAttempts ASC LIMIT :limit")
    suspend fun getMostDifficultWords(limit: Int = 10): List<DifficultWord>

    @Query("SELECT * FROM difficult_words WHERE masteryLevel < 5 ORDER BY lastAttemptDate DESC LIMIT :limit")
    suspend fun getRecentDifficultWords(limit: Int = 10): List<DifficultWord>

    @Query("SELECT * FROM difficult_words WHERE masteryLevel >= 5 ORDER BY lastAttemptDate DESC")
    suspend fun getMasteredWords(): List<DifficultWord>

    @Query("SELECT * FROM difficult_words ORDER BY totalAttempts DESC")
    fun observeAllDifficultWords(): Flow<List<DifficultWord>>

    @Query("SELECT COUNT(*) FROM difficult_words WHERE masteryLevel >= 5")
    fun observeMasteredWordCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM difficult_words WHERE masteryLevel < 5")
    fun observePracticeWordCount(): Flow<Int>

    // ==================== COLLABORATIVE ATTEMPTS ====================

    @Insert
    suspend fun insertCollaborativeAttempt(attempt: CollaborativeAttempt)

    @Query("SELECT * FROM collaborative_attempts WHERE bookId = :bookId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentAttemptsForBook(bookId: Long, limit: Int = 50): List<CollaborativeAttempt>

    @Query("SELECT * FROM collaborative_attempts WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getAttemptsSince(since: Long): List<CollaborativeAttempt>

    @Query("SELECT COUNT(*) FROM collaborative_attempts WHERE isFirstTrySuccess = 1 AND timestamp > :since")
    suspend fun getFirstTrySuccessCount(since: Long): Int

    @Query("SELECT COUNT(*) FROM collaborative_attempts WHERE timestamp > :since")
    suspend fun getTotalAttemptsCount(since: Long): Int

    // ==================== ACHIEVEMENTS ====================

    @Query("SELECT * FROM achievements")
    fun observeAllAchievements(): Flow<List<Achievement>>

    @Query("SELECT * FROM achievements WHERE unlockedAt IS NOT NULL ORDER BY unlockedAt DESC")
    fun observeUnlockedAchievements(): Flow<List<Achievement>>

    @Query("SELECT * FROM achievements WHERE id = :id")
    suspend fun getAchievement(id: String): Achievement?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAchievement(achievement: Achievement)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAchievementIfNotExists(achievement: Achievement)

    @Query("UPDATE achievements SET progress = :progress WHERE id = :id")
    suspend fun updateAchievementProgress(id: String, progress: Int)

    @Query("UPDATE achievements SET unlockedAt = :unlockedAt WHERE id = :id")
    suspend fun unlockAchievement(id: String, unlockedAt: Long = System.currentTimeMillis())

    // ==================== WEEKLY GOALS ====================

    @Query("SELECT * FROM weekly_goals WHERE weekStart = :weekStart")
    suspend fun getWeeklyGoal(weekStart: String): WeeklyGoal?

    @Query("SELECT * FROM weekly_goals WHERE weekStart = :weekStart")
    fun observeWeeklyGoal(weekStart: String): Flow<WeeklyGoal?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeeklyGoal(goal: WeeklyGoal)

    @Query("SELECT * FROM weekly_goals ORDER BY weekStart DESC LIMIT :weeks")
    suspend fun getRecentWeeklyGoals(weeks: Int): List<WeeklyGoal>

    @Query("SELECT COUNT(*) FROM weekly_goals WHERE completedDays >= targetDays")
    suspend fun getSuccessfulWeeksCount(): Int

    // ==================== BOOK STATS ====================

    @Query("SELECT * FROM book_stats WHERE bookId = :bookId")
    suspend fun getBookStats(bookId: Long): BookStats?

    @Query("SELECT * FROM book_stats WHERE bookId = :bookId")
    fun observeBookStats(bookId: Long): Flow<BookStats?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookStats(stats: BookStats)

    @Query("SELECT COUNT(*) FROM book_stats WHERE completedAt IS NOT NULL")
    fun observeCompletedBooksCount(): Flow<Int>

    @Query("SELECT SUM(totalStarsEarned) FROM book_stats")
    suspend fun getTotalBookStars(): Int?

    // ==================== AGGREGATED STATS ====================

    @Query("""
        SELECT
            SUM(starsEarned) as totalStars,
            SUM(perfectWords) as totalPerfect,
            SUM(sentencesRead) as totalSentences,
            SUM(pagesCompleted) as totalPages,
            MAX(longestStreak) as bestStreak
        FROM daily_stats
    """)
    suspend fun getLifetimeStats(): LifetimeStatsResult?

    @Query("""
        SELECT
            SUM(starsEarned) as totalStars,
            SUM(perfectWords) as totalPerfect,
            SUM(sentencesRead) as totalSentences,
            SUM(pagesCompleted) as totalPages,
            MAX(longestStreak) as bestStreak
        FROM daily_stats
        WHERE date BETWEEN :startDate AND :endDate
    """)
    suspend fun getPeriodStats(startDate: String, endDate: String): LifetimeStatsResult?

    // ==================== NEW: STAR BREAKDOWN QUERIES ====================

    @Query("""
        SELECT
            SUM(goldStars) as goldStars,
            SUM(silverStars) as silverStars,
            SUM(bronzeStars) as bronzeStars,
            SUM(totalPoints) as totalPoints
        FROM daily_stats
    """)
    suspend fun getLifetimeStarBreakdown(): StarBreakdownResult?

    @Query("""
        SELECT
            SUM(goldStars) as goldStars,
            SUM(silverStars) as silverStars,
            SUM(bronzeStars) as bronzeStars,
            SUM(totalPoints) as totalPoints
        FROM daily_stats
        WHERE date = :date
    """)
    suspend fun getDayStarBreakdown(date: String): StarBreakdownResult?

    @Query("""
        SELECT
            SUM(totalPoints) as totalPoints,
            SUM(activeReadingTimeMs) as totalActiveTimeMs,
            COUNT(CASE WHEN totalPoints > 0 THEN 1 END) as daysWithActivity,
            SUM(goldStars) as goldStars,
            SUM(silverStars) as silverStars,
            SUM(bronzeStars) as bronzeStars
        FROM daily_stats
        WHERE date BETWEEN :startDate AND :endDate
    """)
    suspend fun getWeeklySummary(startDate: String, endDate: String): WeeklySummaryResult?

    @Query("SELECT SUM(totalPoints) FROM daily_stats")
    fun observeTotalPoints(): Flow<Int?>

    @Query("SELECT SUM(totalPoints) FROM daily_stats")
    suspend fun getTotalPoints(): Int?

    /**
     * Get star breakdown for a date range (week/month).
     */
    @Query("""
        SELECT
            SUM(goldStars) as goldStars,
            SUM(silverStars) as silverStars,
            SUM(bronzeStars) as bronzeStars,
            SUM(totalPoints) as totalPoints
        FROM daily_stats
        WHERE date BETWEEN :startDate AND :endDate
    """)
    suspend fun getPeriodStarBreakdown(startDate: String, endDate: String): StarBreakdownResult?

    /**
     * Get period stats (points, time, days active, stars) for a date range.
     */
    @Query("""
        SELECT
            SUM(totalPoints) as totalPoints,
            SUM(activeReadingTimeMs) as totalActiveTimeMs,
            COUNT(CASE WHEN totalPoints > 0 THEN 1 END) as daysWithActivity,
            SUM(goldStars) as goldStars,
            SUM(silverStars) as silverStars,
            SUM(bronzeStars) as bronzeStars
        FROM daily_stats
        WHERE date BETWEEN :startDate AND :endDate
    """)
    suspend fun getPeriodSummary(startDate: String, endDate: String): WeeklySummaryResult?

    /**
     * Count days where goal was met in a date range.
     * Uses goalMetFinal for finalized days, goalMet for today.
     */
    @Query("""
        SELECT COUNT(*) FROM daily_stats
        WHERE date BETWEEN :startDate AND :endDate
        AND (goalMetFinal = 1 OR (goalMetFinal IS NULL AND goalMet = 1))
    """)
    suspend fun countGoalMetDays(startDate: String, endDate: String): Int

    /**
     * Get the last 7 days of stats for the week calendar view.
     */
    @Query("""
        SELECT * FROM daily_stats
        WHERE date >= :startDate AND date <= :endDate
        ORDER BY date ASC
    """)
    suspend fun getLast7DaysStats(startDate: String, endDate: String): List<DailyStats>

    // ==================== STREAK STATE ====================

    @Query("SELECT * FROM streak_state WHERE streakType = :streakType")
    suspend fun getStreakState(streakType: String): StreakState?

    @Query("SELECT * FROM streak_state WHERE streakType = :streakType")
    fun observeStreakState(streakType: String): Flow<StreakState?>

    @Query("SELECT * FROM streak_state")
    suspend fun getAllStreakStates(): List<StreakState>

    @Query("SELECT * FROM streak_state")
    fun observeAllStreakStates(): Flow<List<StreakState>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreakState(streakState: StreakState)

    @Query("UPDATE streak_state SET currentStreak = :currentStreak, bestStreak = MAX(bestStreak, :currentStreak), lastActivityDate = :date, lastActivityTimestamp = :timestamp WHERE streakType = :streakType")
    suspend fun updateStreakState(streakType: String, currentStreak: Int, date: String, timestamp: Long)

    // ==================== PARENT SETTINGS ====================

    @Query("SELECT * FROM parent_settings WHERE id = 1")
    suspend fun getParentSettings(): ParentSettings?

    @Query("SELECT * FROM parent_settings WHERE id = 1")
    fun observeParentSettings(): Flow<ParentSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParentSettings(settings: ParentSettings)

    @Query("UPDATE parent_settings SET dailyPointsTarget = :target, lastModified = :timestamp WHERE id = 1")
    suspend fun updateDailyPointsTarget(target: Int, timestamp: Long = System.currentTimeMillis())

    // ==================== READING SESSIONS ====================

    @Insert
    suspend fun insertReadingSession(session: ReadingSession): Long

    @Query("SELECT * FROM reading_sessions WHERE id = :sessionId")
    suspend fun getReadingSession(sessionId: Long): ReadingSession?

    @Query("SELECT * FROM reading_sessions WHERE isActive = 1 ORDER BY startTimestamp DESC LIMIT 1")
    suspend fun getActiveSession(): ReadingSession?

    @Query("SELECT * FROM reading_sessions WHERE date = :date ORDER BY startTimestamp DESC")
    suspend fun getSessionsForDate(date: String): List<ReadingSession>

    @Query("SELECT * FROM reading_sessions WHERE date BETWEEN :startDate AND :endDate ORDER BY startTimestamp DESC")
    suspend fun getSessionsForRange(startDate: String, endDate: String): List<ReadingSession>

    @Update
    suspend fun updateReadingSession(session: ReadingSession)

    @Query("""
        UPDATE reading_sessions
        SET endTimestamp = :endTimestamp,
            durationMs = :durationMs,
            isActive = 0,
            goldStars = :goldStars,
            silverStars = :silverStars,
            bronzeStars = :bronzeStars,
            pointsEarned = :pointsEarned,
            pagesRead = :pagesRead,
            sentencesRead = :sentencesRead
        WHERE id = :sessionId
    """)
    suspend fun endReadingSession(
        sessionId: Long,
        endTimestamp: Long,
        durationMs: Long,
        goldStars: Int,
        silverStars: Int,
        bronzeStars: Int,
        pointsEarned: Int,
        pagesRead: Int,
        sentencesRead: Int
    )

    @Query("SELECT SUM(durationMs) FROM reading_sessions WHERE date = :date")
    suspend fun getTotalReadingTimeForDate(date: String): Long?

    @Query("SELECT SUM(durationMs) FROM reading_sessions")
    suspend fun getTotalReadingTimeAllTime(): Long?

    // ==================== COMPLETED SENTENCES ====================

    /**
     * Check if a sentence has already been completed (awarded stars).
     * Returns true if the sentence exists in completed_sentences table.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM completed_sentences
            WHERE bookId = :bookId AND pageId = :pageId AND sentenceIndex = :sentenceIndex
        )
    """)
    suspend fun isSentenceCompleted(bookId: Long, pageId: Long, sentenceIndex: Int): Boolean

    /**
     * Mark a sentence as completed. Uses IGNORE to prevent duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompletedSentence(sentence: CompletedSentence)

    /**
     * Get all completed sentences for a page (for UI indicators).
     */
    @Query("SELECT * FROM completed_sentences WHERE bookId = :bookId AND pageId = :pageId")
    suspend fun getCompletedSentencesForPage(bookId: Long, pageId: Long): List<CompletedSentence>

    /**
     * Count total unique sentences completed (for lifetime stats).
     */
    @Query("SELECT COUNT(*) FROM completed_sentences")
    suspend fun getTotalCompletedSentences(): Int

    /**
     * Observe total completed sentences count.
     */
    @Query("SELECT COUNT(*) FROM completed_sentences")
    fun observeTotalCompletedSentences(): Flow<Int>
}

/**
 * Result class for aggregated lifetime stats query.
 */
data class LifetimeStatsResult(
    val totalStars: Int?,
    val totalPerfect: Int?,
    val totalSentences: Int?,
    val totalPages: Int?,
    val bestStreak: Int?
)

/**
 * Result class for star breakdown query.
 */
data class StarBreakdownResult(
    val goldStars: Int?,
    val silverStars: Int?,
    val bronzeStars: Int?,
    val totalPoints: Int?
)

/**
 * Result class for weekly summary query.
 */
data class WeeklySummaryResult(
    val totalPoints: Int?,
    val totalActiveTimeMs: Long?,
    val daysWithActivity: Int?,
    val goldStars: Int?,
    val silverStars: Int?,
    val bronzeStars: Int?
)
