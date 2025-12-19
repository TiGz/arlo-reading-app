package com.example.arlo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val starsEarned: Int = 0,
    val perfectWords: Int = 0,           // First-try correct in collaborative mode
    val totalCollaborativeAttempts: Int = 0,
    val successfulCollaborativeAttempts: Int = 0,
    val longestStreak: Int = 0,          // Best consecutive perfect words that day
    val totalReadingTimeMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

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
    val timestamp: Long = System.currentTimeMillis()
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
    val currentStreak: Int = 0,          // Consecutive perfect words this session
    val sessionPerfectWords: Int = 0,
    val sessionStars: Int = 0,
    val sessionStartTime: Long = System.currentTimeMillis()
)
