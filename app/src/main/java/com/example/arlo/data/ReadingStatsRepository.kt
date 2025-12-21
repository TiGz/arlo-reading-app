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
        private val MONTH_FORMAT = SimpleDateFormat("yyyy-MM", Locale.US)

        fun todayString(): String = DATE_FORMAT.format(Date())

        fun getYesterdayString(): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            return DATE_FORMAT.format(cal.time)
        }

        fun weekStartString(): String {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            return DATE_FORMAT.format(cal.time)
        }

        fun getLastWeekStartString(): String {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.add(Calendar.WEEK_OF_YEAR, -1)
            return DATE_FORMAT.format(cal.time)
        }

        fun weekEndString(): String {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            return DATE_FORMAT.format(cal.time)
        }

        fun getCurrentMonthString(): String = MONTH_FORMAT.format(Date())

        fun getLastMonthString(): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, -1)
            return MONTH_FORMAT.format(cal.time)
        }

        private fun dayOfWeekName(): String {
            return Calendar.getInstance().getDisplayName(
                Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US
            ) ?: "Mon"
        }

        /**
         * Get the week start date for a given offset from current week.
         * offset = 0 means current week, -1 means last week, etc.
         */
        fun getWeekStartForOffset(offset: Int): String {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.add(Calendar.WEEK_OF_YEAR, offset)
            return DATE_FORMAT.format(cal.time)
        }

        /**
         * Get the week end date for a given offset from current week.
         */
        fun getWeekEndForOffset(offset: Int): String {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            cal.add(Calendar.WEEK_OF_YEAR, offset)
            return DATE_FORMAT.format(cal.time)
        }
    }

    // ==================== DAILY STATS ====================

    /**
     * Get or create today's stats record.
     * Also finalizes any unfinalzed past days.
     */
    suspend fun getTodayStats(): DailyStats {
        val today = todayString()
        // Finalize any past days that weren't finalized
        finalizePastDays(today)
        return dao.getDailyStats(today) ?: DailyStats(date = today)
    }

    /**
     * Finalize all past days that haven't been finalized yet.
     * This locks the goalMetFinal status permanently based on the day's own target.
     */
    private suspend fun finalizePastDays(todayDate: String) {
        dao.finalizePastDays(todayDate)
    }

    /**
     * Get stats for a specific date.
     */
    suspend fun getStatsForDate(date: String): DailyStats? {
        return dao.getDailyStats(date)
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
     * Result of recording a collaborative attempt with the new star/points system.
     */
    data class AttemptResult(
        val starType: StarType,
        val basePoints: Int,
        val streakBonus: Int,
        val totalPoints: Int,
        val newStreak: Int
    )

    /**
     * Record a collaborative reading attempt with star type tracking.
     * Returns AttemptResult with star type and points earned.
     *
     * Stars are only awarded for sentences that haven't been completed before.
     * This prevents gaming by re-reading the same sentences to farm stars.
     *
     * @param bookId The book being read
     * @param pageId The current page
     * @param sentenceIndex Which sentence in the page (for deduplication)
     * @param targetWord The word the child was supposed to say
     * @param spokenWord What speech recognition heard
     * @param isCorrect Whether the answer matched
     * @param attemptNumber Which attempt this is (1, 2, 3, etc.)
     * @param currentStreak Current session streak before this attempt
     * @param ttsPronouncedWord Whether TTS read the word aloud before this attempt
     */
    suspend fun recordCollaborativeAttempt(
        bookId: Long,
        pageId: Long,
        sentenceIndex: Int,
        targetWord: String,
        spokenWord: String?,
        isCorrect: Boolean,
        attemptNumber: Int,
        currentStreak: Int,
        ttsPronouncedWord: Boolean = false
    ): AttemptResult {
        val isFirstTry = attemptNumber == 1 && isCorrect

        // Check if this sentence has already been completed (starred)
        // If so, we still track the attempt but award no stars
        val alreadyCompleted = dao.isSentenceCompleted(bookId, pageId, sentenceIndex)

        // Determine star type - NONE if re-reading a completed sentence
        val starType = if (alreadyCompleted && isCorrect) {
            StarType.NONE  // No stars for re-reads
        } else {
            StarType.determine(attemptNumber, isCorrect, ttsPronouncedWord)
        }

        val newStreak = if (isCorrect) currentStreak + 1 else 0

        // Calculate points with streak bonus (will be 0 if starType is NONE)
        val basePoints = starType.points
        val totalPoints = if (isCorrect) StarType.calculatePoints(starType, newStreak) else 0
        val streakBonus = totalPoints - basePoints

        // Record the attempt with new fields (always, for history/analytics)
        dao.insertCollaborativeAttempt(CollaborativeAttempt(
            bookId = bookId,
            pageId = pageId,
            targetWord = targetWord,
            spokenWord = spokenWord,
            isCorrect = isCorrect,
            attemptNumber = attemptNumber,
            isFirstTrySuccess = isFirstTry && !alreadyCompleted,  // Only true for genuinely new sentences
            starType = if (isCorrect && !alreadyCompleted) starType.name else null,
            pointsEarned = totalPoints,
            ttsPronouncedWord = ttsPronouncedWord,
            sessionStreak = newStreak,
            streakBonus = streakBonus,
            sentenceIndex = sentenceIndex
        ))

        // Mark sentence as completed if this was a successful first completion
        if (isCorrect && !alreadyCompleted && starType != StarType.NONE) {
            dao.insertCompletedSentence(CompletedSentence(
                bookId = bookId,
                pageId = pageId,
                sentenceIndex = sentenceIndex,
                starType = starType.name
            ))
        }

        // Only update daily stats if points were actually earned
        if (totalPoints > 0) {
            val today = getTodayStats()
            val bestStreak = maxOf(today.longestStreak, newStreak)

            // Get parent settings for daily target
            val parentSettings = dao.getParentSettings() ?: ParentSettings()
            val newTotalPoints = today.totalPoints + totalPoints
            val goalMet = newTotalPoints >= parentSettings.dailyPointsTarget

            dao.upsertDailyStats(today.copy(
                perfectWords = if (isFirstTry) today.perfectWords + 1 else today.perfectWords,
                totalCollaborativeAttempts = today.totalCollaborativeAttempts + 1,
                successfulCollaborativeAttempts = if (isCorrect) today.successfulCollaborativeAttempts + 1 else today.successfulCollaborativeAttempts,
                longestStreak = bestStreak,
                // Legacy field for backwards compatibility
                starsEarned = today.starsEarned + (if (starType != StarType.NONE) 1 else 0),
                // New star type breakdown
                goldStars = today.goldStars + (if (starType == StarType.GOLD) 1 else 0),
                silverStars = today.silverStars + (if (starType == StarType.SILVER) 1 else 0),
                bronzeStars = today.bronzeStars + (if (starType == StarType.BRONZE) 1 else 0),
                totalPoints = newTotalPoints,
                dailyPointsTarget = parentSettings.dailyPointsTarget,
                goalMet = goalMet,
                updatedAt = System.currentTimeMillis()
            ))
        } else if (isCorrect) {
            // Still update attempt counts even for re-reads (but no stars/points)
            val today = getTodayStats()
            dao.upsertDailyStats(today.copy(
                totalCollaborativeAttempts = today.totalCollaborativeAttempts + 1,
                successfulCollaborativeAttempts = today.successfulCollaborativeAttempts + 1,
                updatedAt = System.currentTimeMillis()
            ))
        }

        // Update difficult word tracking (still track for mastery even on re-reads)
        updateDifficultWord(targetWord, spokenWord, isCorrect, bookId)

        // Update book stats (only if points earned)
        if (totalPoints > 0) {
            updateBookStatsForAttempt(bookId, starType, isCorrect, newStreak, totalPoints)
        }

        // Update streak states (streaks still continue even on re-reads)
        if (isCorrect) {
            updateStreakStates(newStreak)
        }

        return AttemptResult(
            starType = starType,
            basePoints = basePoints,
            streakBonus = streakBonus,
            totalPoints = totalPoints,
            newStreak = newStreak
        )
    }

    /**
     * Legacy method for backwards compatibility.
     * Calls the new method with sentenceIndex = 0 and ttsPronouncedWord = false.
     */
    @Deprecated("Use recordCollaborativeAttempt with sentenceIndex parameter")
    suspend fun recordCollaborativeAttemptLegacy(
        bookId: Long,
        pageId: Long,
        targetWord: String,
        spokenWord: String?,
        isCorrect: Boolean,
        attemptNumber: Int,
        currentStreak: Int
    ): Int {
        val result = recordCollaborativeAttempt(
            bookId, pageId, 0, targetWord, spokenWord, isCorrect, attemptNumber, currentStreak, false
        )
        return result.totalPoints
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
        starType: StarType,
        isCorrect: Boolean,
        currentStreak: Int,
        pointsEarned: Int
    ) {
        val stats = getOrCreateBookStats(bookId)
        val isFirstTry = starType == StarType.GOLD

        dao.upsertBookStats(stats.copy(
            totalCollaborativeWords = stats.totalCollaborativeWords + 1,
            perfectWordsCount = if (isFirstTry) stats.perfectWordsCount + 1 else stats.perfectWordsCount,
            longestStreak = maxOf(stats.longestStreak, currentStreak),
            totalStarsEarned = stats.totalStarsEarned + (if (starType != StarType.NONE) 1 else 0)
        ))
    }

    // ==================== STREAK MANAGEMENT ====================

    /**
     * Update streak states at all levels after a correct answer.
     */
    private suspend fun updateStreakStates(newSessionStreak: Int) {
        val today = todayString()
        val now = System.currentTimeMillis()

        // Update session streak (best of session)
        val sessionState = dao.getStreakState(StreakState.TYPE_SESSION)
            ?: StreakState(StreakState.TYPE_SESSION)
        if (newSessionStreak > sessionState.currentStreak) {
            dao.upsertStreakState(sessionState.copy(
                currentStreak = newSessionStreak,
                bestStreak = maxOf(sessionState.bestStreak, newSessionStreak),
                lastActivityDate = today,
                lastActivityTimestamp = now
            ))
        }

        // Update all-time best streak
        val allTimeState = dao.getStreakState(StreakState.TYPE_ALL_TIME)
            ?: StreakState(StreakState.TYPE_ALL_TIME)
        if (newSessionStreak > allTimeState.bestStreak) {
            dao.upsertStreakState(allTimeState.copy(
                bestStreak = newSessionStreak,
                lastActivityDate = today,
                lastActivityTimestamp = now
            ))
        }

        // Update day streak (consecutive days with activity)
        updateDayStreak(today)

        // Update week streak (consecutive weeks with activity)
        updateWeekStreak()

        // Update month streak (consecutive months with activity)
        updateMonthStreak()
    }

    /**
     * Update day-level streak (consecutive days with reading activity).
     */
    private suspend fun updateDayStreak(today: String) {
        val dayState = dao.getStreakState(StreakState.TYPE_DAY)
            ?: StreakState(StreakState.TYPE_DAY)

        if (dayState.lastActivityDate == today) {
            return  // Already counted today
        }

        val yesterday = getYesterdayString()
        val newDayStreak = if (dayState.lastActivityDate == yesterday) {
            dayState.currentStreak + 1  // Continuing streak
        } else if (dayState.lastActivityDate.isEmpty()) {
            1  // First day
        } else {
            1  // Streak broken, start fresh
        }

        dao.upsertStreakState(dayState.copy(
            currentStreak = newDayStreak,
            bestStreak = maxOf(dayState.bestStreak, newDayStreak),
            lastActivityDate = today,
            lastActivityTimestamp = System.currentTimeMillis()
        ))
    }

    /**
     * Update week-level streak (consecutive weeks with reading activity).
     */
    private suspend fun updateWeekStreak() {
        val weekStart = weekStartString()
        val weekState = dao.getStreakState(StreakState.TYPE_WEEK)
            ?: StreakState(StreakState.TYPE_WEEK)

        if (weekState.lastActivityWeek == weekStart) {
            return  // Already counted this week
        }

        val lastWeekStart = getLastWeekStartString()
        val newWeekStreak = if (weekState.lastActivityWeek == lastWeekStart) {
            weekState.currentStreak + 1  // Continuing streak
        } else if (weekState.lastActivityWeek.isEmpty()) {
            1  // First week
        } else {
            1  // Streak broken
        }

        dao.upsertStreakState(weekState.copy(
            currentStreak = newWeekStreak,
            bestStreak = maxOf(weekState.bestStreak, newWeekStreak),
            lastActivityWeek = weekStart,
            lastActivityTimestamp = System.currentTimeMillis()
        ))
    }

    /**
     * Update month-level streak (consecutive months with reading activity).
     */
    private suspend fun updateMonthStreak() {
        val currentMonth = getCurrentMonthString()
        val monthState = dao.getStreakState(StreakState.TYPE_MONTH)
            ?: StreakState(StreakState.TYPE_MONTH)

        if (monthState.lastActivityMonth == currentMonth) {
            return  // Already counted this month
        }

        val lastMonth = getLastMonthString()
        val newMonthStreak = if (monthState.lastActivityMonth == lastMonth) {
            monthState.currentStreak + 1
        } else if (monthState.lastActivityMonth.isEmpty()) {
            1
        } else {
            1
        }

        dao.upsertStreakState(monthState.copy(
            currentStreak = newMonthStreak,
            bestStreak = maxOf(monthState.bestStreak, newMonthStreak),
            lastActivityMonth = currentMonth,
            lastActivityTimestamp = System.currentTimeMillis()
        ))
    }

    /**
     * Reset session streak (called when an incorrect answer is given or session ends).
     */
    suspend fun resetSessionStreak() {
        val sessionState = dao.getStreakState(StreakState.TYPE_SESSION)
            ?: return
        dao.upsertStreakState(sessionState.copy(currentStreak = 0))
    }

    /**
     * Get all streak states for display.
     */
    suspend fun getAllStreakStates(): List<StreakState> = dao.getAllStreakStates()

    /**
     * Observe all streak states for real-time display.
     */
    fun observeAllStreakStates(): Flow<List<StreakState>> = dao.observeAllStreakStates()

    /**
     * Get the best streak ever recorded (all-time).
     */
    suspend fun getBestStreakAllTime(): Int {
        return dao.getStreakState(StreakState.TYPE_ALL_TIME)?.bestStreak ?: 0
    }

    // ==================== PARENT SETTINGS ====================

    /**
     * Get parent settings (or defaults if not set).
     */
    suspend fun getParentSettings(): ParentSettings {
        return dao.getParentSettings() ?: ParentSettings().also {
            dao.upsertParentSettings(it)
        }
    }

    /**
     * Observe parent settings for real-time updates.
     */
    fun observeParentSettings(): Flow<ParentSettings?> = dao.observeParentSettings()

    /**
     * Update the daily points target.
     */
    suspend fun updateDailyPointsTarget(target: Int) {
        dao.updateDailyPointsTarget(target)
    }

    /**
     * Update all parent settings.
     */
    suspend fun updateParentSettings(settings: ParentSettings) {
        dao.upsertParentSettings(settings.copy(lastModified = System.currentTimeMillis()))
    }

    // ==================== READING SESSIONS (Time Tracking) ====================

    /**
     * Start a new reading session.
     * Returns the session ID.
     */
    suspend fun startReadingSession(bookId: Long? = null): Long {
        val today = todayString()
        val session = ReadingSession(
            date = today,
            startTimestamp = System.currentTimeMillis(),
            bookId = bookId,
            isActive = true
        )
        return dao.insertReadingSession(session)
    }

    /**
     * End an active reading session.
     */
    suspend fun endReadingSession(
        sessionId: Long,
        goldStars: Int = 0,
        silverStars: Int = 0,
        bronzeStars: Int = 0,
        pointsEarned: Int = 0,
        pagesRead: Int = 0,
        sentencesRead: Int = 0
    ) {
        val session = dao.getReadingSession(sessionId) ?: return
        val endTime = System.currentTimeMillis()
        val duration = endTime - session.startTimestamp

        dao.endReadingSession(
            sessionId = sessionId,
            endTimestamp = endTime,
            durationMs = duration,
            goldStars = goldStars,
            silverStars = silverStars,
            bronzeStars = bronzeStars,
            pointsEarned = pointsEarned,
            pagesRead = pagesRead,
            sentencesRead = sentencesRead
        )

        // Update daily active reading time
        val today = getTodayStats()
        dao.upsertDailyStats(today.copy(
            activeReadingTimeMs = today.activeReadingTimeMs + duration,
            sessionCount = today.sessionCount + 1,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /**
     * Get the currently active session (if any).
     */
    suspend fun getActiveSession(): ReadingSession? = dao.getActiveSession()

    /**
     * Get total reading time for today.
     */
    suspend fun getTodayReadingTimeMs(): Long = dao.getTotalReadingTimeForDate(todayString()) ?: 0L

    /**
     * Get total reading time across all days.
     */
    suspend fun getTotalReadingTimeMs(): Long = dao.getTotalReadingTimeAllTime() ?: 0L

    /**
     * Get total session count from today's stats.
     */
    suspend fun getTodaySessionCount(): Int = getTodayStats().sessionCount

    // ==================== DAILY RECORDS FOR DASHBOARD ====================

    /**
     * Get daily stats for a week range (for dashboard display).
     */
    suspend fun getDailyStatsForWeek(weekStartDate: String, weekEndDate: String): List<DailyStats> {
        return dao.getDailyStatsRange(weekStartDate, weekEndDate)
    }

    /**
     * Get weekly summary statistics.
     */
    suspend fun getWeeklySummary(startDate: String, endDate: String): WeeklySummaryResult {
        return dao.getWeeklySummary(startDate, endDate) ?: WeeklySummaryResult(
            totalPoints = 0,
            totalActiveTimeMs = 0,
            daysWithActivity = 0,
            goldStars = 0,
            silverStars = 0,
            bronzeStars = 0
        )
    }

    /**
     * Get today's star breakdown for display.
     */
    suspend fun getTodayStarBreakdown(): StarBreakdownResult {
        return dao.getDayStarBreakdown(todayString()) ?: StarBreakdownResult(
            goldStars = 0,
            silverStars = 0,
            bronzeStars = 0,
            totalPoints = 0
        )
    }

    /**
     * Get lifetime star breakdown (all time totals).
     */
    suspend fun getLifetimeStarBreakdown(): StarBreakdownResult {
        return dao.getLifetimeStarBreakdown() ?: StarBreakdownResult(
            goldStars = 0,
            silverStars = 0,
            bronzeStars = 0,
            totalPoints = 0
        )
    }

    /**
     * Observe total points across all time.
     */
    fun observeTotalPoints(): Flow<Int?> = dao.observeTotalPoints()

    /**
     * Get total points synchronously.
     */
    suspend fun getTotalPoints(): Int = dao.getTotalPoints() ?: 0

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

    // ==================== PERIOD-BASED STATS FOR DASHBOARD ====================

    /**
     * Time periods for stats dashboard display.
     */
    enum class StatsPeriod {
        TODAY, LAST_7_DAYS, LAST_30_DAYS, ALL_TIME
    }

    /**
     * Container for all stats relevant to the dashboard for a given period.
     */
    data class PeriodStatsBundle(
        val period: StatsPeriod,
        val startDate: String,
        val endDate: String,
        val starBreakdown: StarBreakdownResult,
        val totalReadingTimeMs: Long,
        val daysWithActivity: Int,
        val daysGoalMet: Int,
        val bestStreak: Int,
        val perfectWords: Int,
        val sentencesRead: Int,
        val pagesCompleted: Int
    ) {
        val totalStars: Int
            get() = (starBreakdown.goldStars ?: 0) + (starBreakdown.silverStars ?: 0) + (starBreakdown.bronzeStars ?: 0)
    }

    /**
     * Get the date range for a given stats period.
     */
    fun getDateRangeForPeriod(period: StatsPeriod): Pair<String, String> {
        val today = todayString()
        return when (period) {
            StatsPeriod.TODAY -> today to today
            StatsPeriod.LAST_7_DAYS -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -6)  // Today + 6 previous days = 7 days
                DATE_FORMAT.format(cal.time) to today
            }
            StatsPeriod.LAST_30_DAYS -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -29)  // Today + 29 previous days = 30 days
                DATE_FORMAT.format(cal.time) to today
            }
            StatsPeriod.ALL_TIME -> "2000-01-01" to today  // Effectively all time
        }
    }

    /**
     * Get comprehensive stats for a given time period.
     */
    suspend fun getStatsForPeriod(period: StatsPeriod): PeriodStatsBundle {
        val (startDate, endDate) = getDateRangeForPeriod(period)

        val starBreakdown: StarBreakdownResult = when (period) {
            StatsPeriod.TODAY -> dao.getDayStarBreakdown(startDate)
            StatsPeriod.ALL_TIME -> dao.getLifetimeStarBreakdown()
            else -> dao.getPeriodStarBreakdown(startDate, endDate)
        } ?: StarBreakdownResult(0, 0, 0, 0)

        val summary = if (period == StatsPeriod.ALL_TIME) {
            dao.getWeeklySummary("2000-01-01", endDate)
        } else {
            dao.getPeriodSummary(startDate, endDate)
        } ?: WeeklySummaryResult(0, 0, 0, 0, 0, 0)

        val periodStats = dao.getPeriodStats(startDate, endDate) ?: LifetimeStatsResult(0, 0, 0, 0, 0)

        val daysGoalMet = dao.countGoalMetDays(startDate, endDate)

        return PeriodStatsBundle(
            period = period,
            startDate = startDate,
            endDate = endDate,
            starBreakdown = starBreakdown,
            totalReadingTimeMs = summary.totalActiveTimeMs ?: 0L,
            daysWithActivity = summary.daysWithActivity ?: 0,
            daysGoalMet = daysGoalMet,
            bestStreak = periodStats.bestStreak ?: 0,
            perfectWords = periodStats.totalPerfect ?: 0,
            sentencesRead = periodStats.totalSentences ?: 0,
            pagesCompleted = periodStats.totalPages ?: 0
        )
    }

    /**
     * Get the last 7 days of stats for the calendar view.
     * Returns a list of 7 DailyRecordDisplay items (one per day, empty if no activity).
     */
    suspend fun getLast7DaysForCalendar(): List<DailyRecordDisplay> {
        val today = todayString()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        val startDate = DATE_FORMAT.format(cal.time)

        // Get actual data from DB
        val dbStats = dao.getLast7DaysStats(startDate, today).associateBy { it.date }

        // Build list of all 7 days
        val result = mutableListOf<DailyRecordDisplay>()
        cal.add(Calendar.DAY_OF_YEAR, -1)  // Reset to one day before start

        for (i in 0 until 7) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dateStr = DATE_FORMAT.format(cal.time)
            val stats = dbStats[dateStr]

            val dayOfWeek = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)?.uppercase() ?: "?"
            val dayNumber = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US)?.uppercase() ?: "?"

            result.add(DailyRecordDisplay(
                date = dateStr,
                dayOfWeek = dayOfWeek,
                dayNumber = dayNumber,
                month = month,
                goldStars = stats?.goldStars ?: 0,
                silverStars = stats?.silverStars ?: 0,
                bronzeStars = stats?.bronzeStars ?: 0,
                totalPoints = stats?.totalPoints ?: 0,
                readingTimeMinutes = ((stats?.activeReadingTimeMs ?: 0L) / 60000).toInt(),
                goalMet = stats?.let {
                    // Use finalized status if available, otherwise live status
                    it.goalMetFinal ?: it.goalMet
                } ?: false,
                dailyPointsTarget = stats?.dailyPointsTarget ?: 100
            ))
        }

        return result
    }
}
