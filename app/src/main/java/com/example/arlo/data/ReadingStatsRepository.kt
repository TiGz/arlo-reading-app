package com.example.arlo.data

import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Repository for managing reading stats and gamification data.
 * Provides high-level methods for tracking progress, streaks, and achievements.
 */
class ReadingStatsRepository(private val dao: ReadingStatsDao) {

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun todayString(): String = DATE_FORMAT.format(Date())

        fun weekStartString(): String {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            return DATE_FORMAT.format(cal.time)
        }

        private fun dayOfWeekName(): String {
            return Calendar.getInstance().getDisplayName(
                Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US
            ) ?: "Mon"
        }
    }

    // ==================== DAILY STATS ====================

    /**
     * Get or create today's stats record.
     */
    suspend fun getTodayStats(): DailyStats {
        val today = todayString()
        return dao.getDailyStats(today) ?: DailyStats(date = today)
    }

    /**
     * Observe today's stats as a Flow.
     */
    fun observeTodayStats(): Flow<DailyStats?> = dao.observeDailyStats(todayString())

    /**
     * Observe total stars across all time.
     */
    fun observeTotalStars(): Flow<Int?> = dao.observeTotalStars()

    /**
     * Get total stars synchronously.
     */
    suspend fun getTotalStars(): Int = dao.getTotalStars() ?: 0

    /**
     * Record a sentence was read (for progress tracking).
     */
    suspend fun recordSentenceRead() {
        val today = getTodayStats()
        dao.upsertDailyStats(today.copy(
            sentencesRead = today.sentencesRead + 1,
            updatedAt = System.currentTimeMillis()
        ))
        recordDayActivity()
    }

    /**
     * Record a page was completed.
     */
    suspend fun recordPageCompleted() {
        val today = getTodayStats()
        dao.upsertDailyStats(today.copy(
            pagesCompleted = today.pagesCompleted + 1,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /**
     * Record a book was completed.
     */
    suspend fun recordBookCompleted() {
        val today = getTodayStats()
        dao.upsertDailyStats(today.copy(
            booksCompleted = today.booksCompleted + 1,
            updatedAt = System.currentTimeMillis()
        ))
    }

    // ==================== COLLABORATIVE ATTEMPTS ====================

    /**
     * Record a collaborative reading attempt.
     * Returns the number of stars earned (1 for first-try, 0 otherwise).
     */
    suspend fun recordCollaborativeAttempt(
        bookId: Long,
        pageId: Long,
        targetWord: String,
        spokenWord: String?,
        isCorrect: Boolean,
        attemptNumber: Int,
        currentStreak: Int
    ): Int {
        val isFirstTry = attemptNumber == 1 && isCorrect

        // Record the attempt
        dao.insertCollaborativeAttempt(CollaborativeAttempt(
            bookId = bookId,
            pageId = pageId,
            targetWord = targetWord,
            spokenWord = spokenWord,
            isCorrect = isCorrect,
            attemptNumber = attemptNumber,
            isFirstTrySuccess = isFirstTry
        ))

        // Update daily stats
        val today = getTodayStats()
        val newPerfectWords = if (isFirstTry) today.perfectWords + 1 else today.perfectWords
        val newStreak = if (isCorrect) currentStreak + 1 else 0
        val bestStreak = maxOf(today.longestStreak, newStreak)

        // Calculate stars earned
        var starsEarned = 0
        if (isFirstTry) {
            starsEarned = 1  // Base star for first-try success

            // Streak bonuses
            when {
                newStreak >= 10 -> starsEarned += 3  // 10+ streak bonus
                newStreak >= 5 -> starsEarned += 2   // 5+ streak bonus
                newStreak >= 3 -> starsEarned += 1   // 3+ streak bonus
            }
        }

        dao.upsertDailyStats(today.copy(
            perfectWords = newPerfectWords,
            totalCollaborativeAttempts = today.totalCollaborativeAttempts + 1,
            successfulCollaborativeAttempts = if (isCorrect) today.successfulCollaborativeAttempts + 1 else today.successfulCollaborativeAttempts,
            longestStreak = bestStreak,
            starsEarned = today.starsEarned + starsEarned,
            updatedAt = System.currentTimeMillis()
        ))

        // Update difficult word tracking
        updateDifficultWord(targetWord, spokenWord, isCorrect, bookId)

        // Update book stats
        updateBookStatsForAttempt(bookId, isFirstTry, isCorrect, newStreak)

        return starsEarned
    }

    // ==================== DIFFICULT WORDS ====================

    /**
     * Update tracking for a word based on collaborative attempt result.
     */
    private suspend fun updateDifficultWord(
        word: String,
        spokenAs: String?,
        isCorrect: Boolean,
        bookId: Long?
    ) {
        val normalized = word.lowercase().replace(Regex("[^a-z]"), "")
        var existing = dao.getDifficultWord(normalized)

        if (existing == null) {
            // First time seeing this word
            existing = DifficultWord(
                word = word,
                normalizedWord = normalized,
                totalAttempts = 1,
                successfulAttempts = if (isCorrect) 1 else 0,
                consecutiveSuccesses = if (isCorrect) 1 else 0,
                lastSpokenAs = spokenAs,
                bookId = bookId,
                masteryLevel = if (isCorrect) 1 else 0
            )
        } else {
            // Update existing word stats
            val newConsecutive = if (isCorrect) existing.consecutiveSuccesses + 1 else 0
            val newSuccessful = if (isCorrect) existing.successfulAttempts + 1 else existing.successfulAttempts

            // Mastery level increases with consecutive successes (max 5)
            val newMastery = when {
                newConsecutive >= 5 -> 5  // Fully mastered
                isCorrect && existing.masteryLevel < 5 -> minOf(existing.masteryLevel + 1, 5)
                !isCorrect && existing.masteryLevel > 0 -> maxOf(existing.masteryLevel - 1, 0)
                else -> existing.masteryLevel
            }

            existing = existing.copy(
                totalAttempts = existing.totalAttempts + 1,
                successfulAttempts = newSuccessful,
                consecutiveSuccesses = newConsecutive,
                lastAttemptDate = System.currentTimeMillis(),
                lastSpokenAs = if (!isCorrect) spokenAs else existing.lastSpokenAs,
                masteryLevel = newMastery
            )
        }

        dao.upsertDifficultWord(existing)
    }

    /**
     * Get words that need more practice.
     */
    suspend fun getPracticeWords(limit: Int = 10): List<DifficultWord> {
        return dao.getMostDifficultWords(limit)
    }

    /**
     * Observe count of mastered vs. practicing words.
     */
    fun observeMasteredWordCount(): Flow<Int> = dao.observeMasteredWordCount()
    fun observePracticeWordCount(): Flow<Int> = dao.observePracticeWordCount()

    // ==================== WEEKLY GOALS ====================

    /**
     * Record that reading activity happened today.
     * Updates the weekly goal progress.
     */
    private suspend fun recordDayActivity() {
        val weekStart = weekStartString()
        val dayName = dayOfWeekName()

        var goal = dao.getWeeklyGoal(weekStart) ?: WeeklyGoal(weekStart = weekStart)

        // Check if this day already counted
        if (dayName in goal.daysWithActivity) {
            return  // Already counted this day
        }

        // Add this day to activity list
        val newDays = if (goal.daysWithActivity.isEmpty()) {
            dayName
        } else {
            "${goal.daysWithActivity},$dayName"
        }
        val newCompletedDays = goal.completedDays + 1

        // Check for weekly goal completion bonus
        var bonusStars = goal.bonusStarsEarned
        if (newCompletedDays >= goal.targetDays && goal.completedDays < goal.targetDays) {
            // Just hit the goal!
            bonusStars += 10  // Bonus stars for hitting weekly goal

            // Increment weekly streak
            goal = goal.copy(weeklyStreakCount = goal.weeklyStreakCount + 1)
        }

        dao.upsertWeeklyGoal(goal.copy(
            completedDays = newCompletedDays,
            daysWithActivity = newDays,
            bonusStarsEarned = bonusStars
        ))
    }

    /**
     * Observe current week's goal progress.
     */
    fun observeCurrentWeekGoal(): Flow<WeeklyGoal?> = dao.observeWeeklyGoal(weekStartString())

    // ==================== BOOK STATS ====================

    /**
     * Get or create stats for a book.
     */
    suspend fun getOrCreateBookStats(bookId: Long): BookStats {
        return dao.getBookStats(bookId) ?: BookStats(bookId = bookId).also {
            dao.upsertBookStats(it)
        }
    }

    /**
     * Observe stats for a specific book.
     */
    fun observeBookStats(bookId: Long): Flow<BookStats?> = dao.observeBookStats(bookId)

    /**
     * Update book stats after a collaborative attempt.
     */
    private suspend fun updateBookStatsForAttempt(
        bookId: Long,
        isFirstTry: Boolean,
        isCorrect: Boolean,
        currentStreak: Int
    ) {
        val stats = getOrCreateBookStats(bookId)

        var starsEarned = 0
        if (isFirstTry) {
            starsEarned = 1
            when {
                currentStreak >= 10 -> starsEarned += 3
                currentStreak >= 5 -> starsEarned += 2
                currentStreak >= 3 -> starsEarned += 1
            }
        }

        dao.upsertBookStats(stats.copy(
            totalCollaborativeWords = stats.totalCollaborativeWords + 1,
            perfectWordsCount = if (isFirstTry) stats.perfectWordsCount + 1 else stats.perfectWordsCount,
            longestStreak = maxOf(stats.longestStreak, currentStreak),
            totalStarsEarned = stats.totalStarsEarned + starsEarned
        ))
    }

    /**
     * Record that a sentence was read in a book.
     */
    suspend fun recordBookSentenceRead(bookId: Long) {
        val stats = getOrCreateBookStats(bookId)
        dao.upsertBookStats(stats.copy(
            totalSentencesRead = stats.totalSentencesRead + 1
        ))
    }

    /**
     * Record that a page was completed in a book.
     */
    suspend fun recordBookPageRead(bookId: Long) {
        val stats = getOrCreateBookStats(bookId)
        dao.upsertBookStats(stats.copy(
            totalPagesRead = stats.totalPagesRead + 1
        ))
    }

    /**
     * Mark a book as completed.
     */
    suspend fun markBookCompleted(bookId: Long) {
        val stats = getOrCreateBookStats(bookId)
        if (stats.completedAt == null) {
            dao.upsertBookStats(stats.copy(completedAt = System.currentTimeMillis()))
            recordBookCompleted()
        }
    }

    // ==================== ACHIEVEMENTS ====================

    /**
     * Initialize default achievements if they don't exist.
     */
    suspend fun initializeAchievements() {
        val achievements = listOf(
            Achievement("first_word", "First Word!", "Read your first word", "ic_star", goal = 1),
            Achievement("streak_3", "On a Roll", "Get 3 words right in a row", "ic_fire", goal = 3),
            Achievement("streak_5", "Hot Streak", "Get 5 words right in a row", "ic_fire", goal = 5),
            Achievement("streak_10", "Unstoppable", "Get 10 words right in a row", "ic_fire", goal = 10),
            Achievement("stars_10", "Star Collector", "Earn 10 stars", "ic_star", goal = 10),
            Achievement("stars_50", "Star Champion", "Earn 50 stars", "ic_star", goal = 50),
            Achievement("stars_100", "Superstar", "Earn 100 stars", "ic_star", goal = 100),
            Achievement("words_mastered_5", "Word Master", "Master 5 words", "ic_stats", goal = 5),
            Achievement("words_mastered_20", "Vocabulary Hero", "Master 20 words", "ic_stats", goal = 20),
            Achievement("weekly_goal", "Week Winner", "Hit your weekly reading goal", "ic_star", goal = 1),
            Achievement("first_book", "Bookworm", "Finish your first book", "ic_stats", goal = 1),
            Achievement("pages_10", "Page Turner", "Read 10 pages", "ic_stats", goal = 10),
            Achievement("pages_50", "Dedicated Reader", "Read 50 pages", "ic_stats", goal = 50)
        )

        achievements.forEach { dao.insertAchievementIfNotExists(it) }
    }

    /**
     * Check and update achievement progress.
     * Returns list of newly unlocked achievements.
     */
    suspend fun checkAchievements(
        totalStars: Int,
        longestStreak: Int,
        masteredWords: Int,
        totalPages: Int,
        booksCompleted: Int,
        weeklyGoalMet: Boolean
    ): List<Achievement> {
        val newlyUnlocked = mutableListOf<Achievement>()

        // Check each achievement
        suspend fun checkAndUnlock(id: String, currentProgress: Int) {
            val achievement = dao.getAchievement(id) ?: return
            if (achievement.isUnlocked) return

            dao.updateAchievementProgress(id, currentProgress)

            if (currentProgress >= achievement.goal) {
                dao.unlockAchievement(id)
                newlyUnlocked.add(achievement.copy(unlockedAt = System.currentTimeMillis()))
            }
        }

        // Star achievements
        checkAndUnlock("stars_10", totalStars)
        checkAndUnlock("stars_50", totalStars)
        checkAndUnlock("stars_100", totalStars)

        // Streak achievements
        checkAndUnlock("streak_3", longestStreak)
        checkAndUnlock("streak_5", longestStreak)
        checkAndUnlock("streak_10", longestStreak)

        // Word mastery achievements
        checkAndUnlock("words_mastered_5", masteredWords)
        checkAndUnlock("words_mastered_20", masteredWords)

        // Reading achievements
        checkAndUnlock("pages_10", totalPages)
        checkAndUnlock("pages_50", totalPages)
        checkAndUnlock("first_book", booksCompleted)

        // Weekly goal
        if (weeklyGoalMet) {
            checkAndUnlock("weekly_goal", 1)
        }

        // First word (triggers on any star earned)
        if (totalStars > 0) {
            checkAndUnlock("first_word", 1)
        }

        return newlyUnlocked
    }

    /**
     * Observe all achievements for display.
     */
    fun observeAllAchievements(): Flow<List<Achievement>> = dao.observeAllAchievements()

    /**
     * Observe only unlocked achievements.
     */
    fun observeUnlockedAchievements(): Flow<List<Achievement>> = dao.observeUnlockedAchievements()

    // ==================== AGGREGATE STATS ====================

    /**
     * Get lifetime stats summary.
     */
    suspend fun getLifetimeStats(): LifetimeStatsResult {
        return dao.getLifetimeStats() ?: LifetimeStatsResult(
            totalStars = 0,
            totalPerfect = 0,
            totalSentences = 0,
            totalPages = 0,
            bestStreak = 0
        )
    }

    /**
     * Get stats for a date range.
     */
    suspend fun getPeriodStats(startDate: String, endDate: String): LifetimeStatsResult {
        return dao.getPeriodStats(startDate, endDate) ?: LifetimeStatsResult(
            totalStars = 0,
            totalPerfect = 0,
            totalSentences = 0,
            totalPages = 0,
            bestStreak = 0
        )
    }

    /**
     * Get recent daily stats for charts.
     */
    suspend fun getRecentDailyStats(days: Int = 7): List<DailyStats> {
        return dao.getRecentDailyStats(days)
    }
}
