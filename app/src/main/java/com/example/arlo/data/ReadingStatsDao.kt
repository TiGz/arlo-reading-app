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
