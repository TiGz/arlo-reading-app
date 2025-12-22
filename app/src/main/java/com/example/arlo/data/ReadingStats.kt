package com.example.arlo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Star types for the gamification system.
 * Gold = 1st try, Silver = 2nd/3rd try before TTS, Bronze = after TTS reads word
 */
enum class StarType(val points: Int, val displayName: String) {
    GOLD(5, "Gold"),
    SILVER(3, "Silver"),
    BRONZE(1, "Bronze"),
    NONE(0, "None");

    companion object {
        /**
         * Determines star type based on attempt context.
         * @param attemptNumber Which attempt (1, 2, 3, etc.)
         * @param isCorrect Whether the answer was correct
         * @param ttsPronouncedWord Whether TTS read the word before this attempt
         */
        fun determine(attemptNumber: Int, isCorrect: Boolean, ttsPronouncedWord: Boolean): StarType {
            if (!isCorrect) return NONE
            return when {
                !ttsPronouncedWord && attemptNumber == 1 -> GOLD
                !ttsPronouncedWord && attemptNumber in 2..3 -> SILVER
                ttsPronouncedWord -> BRONZE
                else -> NONE
            }
        }

        /**
         * Calculate streak bonus multiplier.
         * 3+ streak = +25%, 5+ streak = +50%, 10+ streak = +100%
         */
        fun getStreakMultiplier(sessionStreak: Int): Float {
            return when {
                sessionStreak >= 10 -> 2.0f    // +100%
                sessionStreak >= 5 -> 1.5f     // +50%
                sessionStreak >= 3 -> 1.25f    // +25%
                else -> 1.0f                    // No bonus
            }
        }

        /**
         * Calculate total points with streak bonus.
         */
        fun calculatePoints(starType: StarType, sessionStreak: Int): Int {
            val basePoints = starType.points
            val multiplier = getStreakMultiplier(sessionStreak)
            return (basePoints * multiplier).toInt()
        }
    }
}

/**
 * Tracks daily reading statistics for gamification.
 * Each day gets one row per user session summary.
 */
@Entity(tableName = "daily_stats")
data class DailyStats(
    @PrimaryKey val date: String,  // Format: "2025-12-19"
    val sentencesRead: Int = 0,
    val pagesCompleted: Int = 0,
    val booksCompleted: Int = 0,

    // Legacy field - kept for migration compatibility
    val starsEarned: Int = 0,

    // New star type breakdown
    val goldStars: Int = 0,              // Correct on 1st try
    val silverStars: Int = 0,            // Correct on 2nd/3rd try before TTS
    val bronzeStars: Int = 0,            // Correct after TTS reads word
    val totalPoints: Int = 0,            // All points including streak bonuses

    // Daily goal tracking
    val dailyPointsTarget: Int = 100,    // Configurable by parent (snapshot of target for this day)
    val goalMet: Boolean = false,        // True when totalPoints >= dailyPointsTarget
    val goalMetFinal: Boolean? = null,   // Null = day in progress, true/false = permanently locked at day end

    val perfectWords: Int = 0,           // First-try correct in collaborative mode
    val totalCollaborativeAttempts: Int = 0,
    val successfulCollaborativeAttempts: Int = 0,
    val longestStreak: Int = 0,          // Best consecutive perfect words that day

    // Time tracking
    val totalReadingTimeMs: Long = 0,    // Legacy - total time
    val activeReadingTimeMs: Long = 0,   // Time actually reading (book open)
    val totalAppTimeMs: Long = 0,        // Total app open time
    val sessionCount: Int = 0,           // Number of reading sessions

    // Game rewards tracking
    val racesEarned: Int = 0,            // Races earned today (1-3 based on performance)
    val racesUsed: Int = 0,              // Races consumed today
    val gameRewardClaimed: Boolean = false,  // Has claimed today's reward
    val lastGamePlayedAt: Long? = null,  // Timestamp of last game session

    // Milestone race source tracking for analytics
    val racesFromDailyTarget: Int = 0,
    val racesFromMultiples: Int = 0,
    val racesFromStreaks: Int = 0,
    val racesFromPages: Int = 0,
    val racesFromChapters: Int = 0,
    val racesFromBooks: Int = 0,
    val highestMultipleReached: Int = 0,
    val highestStreakMilestone: Int = 0,

    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Total stars of all types */
    val totalStars: Int
        get() = goldStars + silverStars + bronzeStars
}

/**
 * Tracks words that the child struggles with in collaborative mode.
 * Used to build personalized practice sessions and show progress.
 */
@Entity(
    tableName = "difficult_words",
    indices = [Index(value = ["word"], unique = true)]
)
data class DifficultWord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val normalizedWord: String,          // Lowercase, no punctuation for matching
    val totalAttempts: Int = 0,
    val successfulAttempts: Int = 0,
    val consecutiveSuccesses: Int = 0,   // For mastery tracking (5 = mastered)
    val firstSeenDate: Long = System.currentTimeMillis(),
    val lastAttemptDate: Long = System.currentTimeMillis(),
    val lastSpokenAs: String? = null,    // What they said (for phonetic analysis)
    val contextSentence: String? = null, // Example sentence where it appeared
    val bookId: Long? = null,            // Which book it came from
    val masteryLevel: Int = 0            // 0-5 stars (5 = fully mastered)
) {
    val successRate: Float
        get() = if (totalAttempts > 0) successfulAttempts.toFloat() / totalAttempts else 0f

    val isMastered: Boolean
        get() = consecutiveSuccesses >= 5 || successRate >= 0.9f && totalAttempts >= 5
}

/**
 * Individual collaborative reading attempt for detailed tracking.
 * Enables analysis of patterns and progress over time.
 */
@Entity(
    tableName = "collaborative_attempts",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"]), Index(value = ["timestamp"])]
)
data class CollaborativeAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageId: Long,
    val targetWord: String,
    val spokenWord: String?,             // What speech recognition heard
    val isCorrect: Boolean,
    val attemptNumber: Int,              // 1, 2, or 3 for retry tracking
    val isFirstTrySuccess: Boolean,      // Special flag for streak bonus
    val timestamp: Long = System.currentTimeMillis(),

    // New fields for star/points tracking
    val starType: String? = null,        // "GOLD", "SILVER", "BRONZE", or null
    val pointsEarned: Int = 0,           // Points including streak bonus
    val ttsPronouncedWord: Boolean = false,  // True if TTS read word before success
    val sessionStreak: Int = 0,          // Streak count when attempt was made
    val streakBonus: Int = 0,            // Bonus points from streak multiplier
    val sentenceIndex: Int = 0           // Which sentence in the page (for deduplication)
)

/**
 * Tracks sentences that have been successfully completed (starred).
 * Prevents gaming by re-reading the same sentences to farm stars.
 * Uses composite primary key: bookId + pageId + sentenceIndex.
 */
@Entity(
    tableName = "completed_sentences",
    primaryKeys = ["bookId", "pageId", "sentenceIndex"],
    indices = [Index(value = ["bookId", "pageId"])]
)
data class CompletedSentence(
    val bookId: Long,
    val pageId: Long,
    val sentenceIndex: Int,
    val completedAt: Long = System.currentTimeMillis(),
    val starType: String  // Best star earned: "GOLD", "SILVER", "BRONZE"
)

/**
 * Achievement badges earned by the reader.
 * Unlocked based on specific accomplishments.
 */
@Entity(tableName = "achievements")
data class Achievement(
    @PrimaryKey val id: String,          // e.g., "first_book", "streak_7_days"
    val name: String,
    val description: String,
    val iconName: String,                // Drawable resource name
    val unlockedAt: Long? = null,        // null = not yet unlocked
    val progress: Int = 0,               // Current progress toward goal
    val goal: Int = 1                    // Target to unlock
) {
    val isUnlocked: Boolean
        get() = unlockedAt != null

    val progressPercent: Float
        get() = (progress.toFloat() / goal).coerceIn(0f, 1f)
}

/**
 * Weekly reading goal tracking.
 * Uses gentle streaks (5/7 days target) instead of punishing daily streaks.
 */
@Entity(tableName = "weekly_goals")
data class WeeklyGoal(
    @PrimaryKey val weekStart: String,   // Format: "2025-12-16" (Monday)
    val targetDays: Int = 5,             // Goal is 5 out of 7 days
    val completedDays: Int = 0,          // Days with reading activity
    val daysWithActivity: String = "",   // Comma-separated: "Mon,Tue,Wed"
    val graceDaysUsed: Int = 0,          // Max 2 grace days per week
    val weeklyStreakCount: Int = 0,      // Consecutive weeks hitting goal
    val bonusStarsEarned: Int = 0        // Bonus for hitting weekly goal
)

/**
 * Per-book reading statistics.
 */
@Entity(
    tableName = "book_stats",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"], unique = true)]
)
data class BookStats(
    @PrimaryKey val bookId: Long,
    val totalSentencesRead: Int = 0,
    val totalPagesRead: Int = 0,
    val perfectWordsCount: Int = 0,
    val totalCollaborativeWords: Int = 0,
    val longestStreak: Int = 0,
    val totalStarsEarned: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val totalReadingTimeMs: Long = 0
)

/**
 * Current session state for real-time streak tracking.
 * Reset at end of each reading session.
 */
data class SessionStats(
    val currentStreak: Int = 0,          // Consecutive correct words this session
    val sessionPerfectWords: Int = 0,    // First-try successes (gold stars)
    val sessionGoldStars: Int = 0,
    val sessionSilverStars: Int = 0,
    val sessionBronzeStars: Int = 0,
    val sessionPoints: Int = 0,          // Total points this session
    val sessionStars: Int = 0,           // Legacy - total stars
    val sessionStartTime: Long = System.currentTimeMillis()
) {
    val totalSessionStars: Int
        get() = sessionGoldStars + sessionSilverStars + sessionBronzeStars
}

// ==================== NEW ENTITIES FOR GAMIFICATION V2 ====================

/**
 * Tracks streak state at different levels (session, day, week, month, all-time).
 * One row per streak type.
 */
@Entity(tableName = "streak_state")
data class StreakState(
    @PrimaryKey val streakType: String,  // "session", "day", "week", "month", "allTime"
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val lastActivityDate: String = "",   // ISO date of last activity
    val lastActivityWeek: String = "",   // ISO week (for week streaks)
    val lastActivityMonth: String = "",  // YYYY-MM (for month streaks)
    val lastActivityTimestamp: Long = 0
) {
    companion object {
        const val TYPE_SESSION = "session"
        const val TYPE_DAY = "day"
        const val TYPE_WEEK = "week"
        const val TYPE_MONTH = "month"
        const val TYPE_ALL_TIME = "allTime"

        fun allTypes() = listOf(TYPE_SESSION, TYPE_DAY, TYPE_WEEK, TYPE_MONTH, TYPE_ALL_TIME)
    }
}

/**
 * Parent-configurable settings for the gamification system.
 * Singleton pattern - only one row with id=1.
 */
@Entity(tableName = "parent_settings")
data class ParentSettings(
    @PrimaryKey val id: Int = 1,         // Singleton - always 1
    val dailyPointsTarget: Int = 100,    // Daily goal (default 100 points)
    val weeklyDaysTarget: Int = 5,       // Weekly goal (5 of 7 days)
    val enableStreakBonuses: Boolean = true,
    val maxStreakMultiplier: Float = 2.0f,  // Cap at 2x
    val kidModeEnabled: Boolean = true,
    val pinCode: String? = null,         // Future: PIN lock for parent settings
    // Game reward settings
    val gameRewardsEnabled: Boolean = true,   // Enable/disable game rewards
    val maxRacesPerDay: Int = 3,              // 1-5 races per day

    // Milestone rewards (0-5 races each)
    val racesForDailyTarget: Int = 1,         // Races when daily target hit
    val racesPerMultiple: Int = 1,            // Races per 100% multiple (2x, 3x...)
    val streakThreshold: Int = 5,             // N value for streak milestones
    val racesPerStreakAchievement: Int = 1,   // Races per N-streak
    val racesPerPageCompletion: Int = 0,      // Races per page
    val racesPerChapterCompletion: Int = 1,   // Races per chapter
    val racesPerBookCompletion: Int = 2,      // Races per book
    val completionThreshold: Float = 0.8f,    // 80% minimum for completion rewards

    val lastModified: Long = System.currentTimeMillis()
)

/**
 * Tracks individual reading sessions for time tracking.
 * Created when reader opens, closed when reader closes.
 */
@Entity(
    tableName = "reading_sessions",
    indices = [Index(value = ["date"]), Index(value = ["bookId"])]
)
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,                    // ISO date
    val startTimestamp: Long,
    val endTimestamp: Long? = null,      // null if session in progress
    val durationMs: Long = 0,
    val bookId: Long? = null,
    val pagesRead: Int = 0,
    val sentencesRead: Int = 0,
    val goldStars: Int = 0,
    val silverStars: Int = 0,
    val bronzeStars: Int = 0,
    val pointsEarned: Int = 0,
    val isActive: Boolean = true         // false when session ends
) {
    val totalStars: Int
        get() = goldStars + silverStars + bronzeStars
}

/**
 * Summary data for displaying a day in the reading history.
 * Not a Room entity - computed from DailyStats.
 */
data class DailyRecordDisplay(
    val date: String,                    // "2025-12-20"
    val dayOfWeek: String,               // "FRI"
    val dayNumber: Int,                  // 20
    val month: String,                   // "DEC"
    val goldStars: Int,
    val silverStars: Int,
    val bronzeStars: Int,
    val totalPoints: Int,
    val readingTimeMinutes: Int,
    val goalMet: Boolean,
    val dailyPointsTarget: Int
) {
    val totalStars: Int
        get() = goldStars + silverStars + bronzeStars
}

/**
 * Records game sessions for history tracking.
 */
@Entity(
    tableName = "game_sessions",
    indices = [Index(value = ["date"])]
)
data class GameSessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,                  // "pixelwheels"
    val date: String,                    // ISO date
    val racesPlayed: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val raceResults: String? = null      // JSON of race positions (optional)
)

/**
 * Weekly summary for dashboard display.
 * Not a Room entity - computed from DailyStats.
 */
data class WeeklySummaryDisplay(
    val weekStartDate: String,
    val weekEndDate: String,
    val totalPoints: Int,
    val totalTimeMinutes: Int,
    val daysWithActivity: Int,
    val targetDays: Int,
    val goldStars: Int,
    val silverStars: Int,
    val bronzeStars: Int
) {
    val totalStars: Int
        get() = goldStars + silverStars + bronzeStars

    val weeklyGoalMet: Boolean
        get() = daysWithActivity >= targetDays
}

// ==================== MILESTONE REWARDS SYSTEM ====================

/**
 * Milestone types for the enhanced game rewards system.
 */
enum class MilestoneType {
    DAILY_TARGET,    // Reaching daily points goal
    MULTIPLE,        // Reaching 2x, 3x, etc. of daily target
    STREAK,          // Hitting streak threshold (5, 10, 15...)
    PAGE,            // Completing a page
    CHAPTER,         // Completing a chapter
    BOOK             // Completing a book
}

/**
 * Prevents double-claiming of milestones per day.
 * Composite primary key ensures each milestone can only be claimed once per day.
 */
@Entity(
    tableName = "milestone_claims",
    primaryKeys = ["date", "milestoneType", "milestoneId"]
)
data class MilestoneClaimRecord(
    val date: String,                 // "2025-12-22"
    val milestoneType: String,        // DAILY_TARGET, MULTIPLE, STREAK, PAGE, CHAPTER, BOOK
    val milestoneId: String,          // Unique identifier (e.g., "2X", "10", pageId, chapterTitle)
    val racesAwarded: Int,
    val claimedAt: Long = System.currentTimeMillis()
)

/**
 * Tracks possible vs achieved stars for completion percentage calculation.
 * Every sentence with a collaborative opportunity gets a record.
 */
@Entity(
    tableName = "sentence_completion_state",
    primaryKeys = ["bookId", "pageId", "sentenceIndex"],
    indices = [
        Index(value = ["bookId", "pageId"]),
        Index(value = ["bookId", "resolvedChapter"])
    ]
)
data class SentenceCompletionState(
    val bookId: Long,
    val pageId: Long,
    val sentenceIndex: Int,
    val resolvedChapter: String?,          // Inferred chapter (from Page.resolvedChapter)
    val hasCollaborativeOpportunity: Boolean = true,
    val wasAttempted: Boolean = false,
    val wasCompletedSuccessfully: Boolean = false,
    val wasSkippedByTTS: Boolean = false,  // Missed star - can retry
    val starType: String? = null,          // GOLD, SILVER, BRONZE, null
    val completedAt: Long? = null
)

/**
 * Result of checking milestones after an action.
 */
data class MilestoneCheckResult(
    val earnedMilestones: List<EarnedMilestone>,
    val totalRacesAwarded: Int
)

/**
 * A single milestone that was earned.
 */
data class EarnedMilestone(
    val type: MilestoneType,
    val milestoneId: String,
    val racesAwarded: Int,
    val displayMessage: String
)
