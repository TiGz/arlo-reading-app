package com.example.arlo.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncPayload(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String = "Arlo's Tablet",
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("daily_stats") val dailyStats: List<DailyStatsPayload>? = null,
    @SerialName("books") val books: List<BookPayload>? = null,
    @SerialName("sessions") val sessions: List<SessionPayload>? = null,
    @SerialName("difficult_words") val difficultWords: List<WordPayload>? = null,
    @SerialName("errors") val errors: List<ErrorPayload>? = null
)

@Serializable
data class DailyStatsPayload(
    val date: String,
    @SerialName("gold_stars") val goldStars: Int,
    @SerialName("silver_stars") val silverStars: Int,
    @SerialName("bronze_stars") val bronzeStars: Int,
    @SerialName("total_points") val totalPoints: Int,
    @SerialName("daily_points_target") val dailyPointsTarget: Int,
    @SerialName("goal_met") val goalMet: Boolean,
    @SerialName("sentences_read") val sentencesRead: Int,
    @SerialName("pages_completed") val pagesCompleted: Int,
    @SerialName("books_completed") val booksCompleted: Int,
    @SerialName("perfect_words") val perfectWords: Int,
    @SerialName("total_collaborative_attempts") val totalCollaborativeAttempts: Int,
    @SerialName("successful_collaborative_attempts") val successfulCollaborativeAttempts: Int,
    @SerialName("longest_streak") val longestStreak: Int,
    @SerialName("active_reading_time_ms") val activeReadingTimeMs: Long,
    @SerialName("total_app_time_ms") val totalAppTimeMs: Long,
    @SerialName("session_count") val sessionCount: Int,
    @SerialName("races_earned") val racesEarned: Int,
    @SerialName("races_used") val racesUsed: Int,
    @SerialName("game_reward_claimed") val gameRewardClaimed: Boolean,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class BookPayload(
    @SerialName("local_id") val localId: Long,
    val title: String,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("total_sentences") val totalSentences: Int,
    @SerialName("pages_read") val pagesRead: Int,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("current_sentence") val currentSentence: Int,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("total_stars_earned") val totalStarsEarned: Int,
    @SerialName("total_reading_time_ms") val totalReadingTimeMs: Long
)

@Serializable
data class SessionPayload(
    @SerialName("local_id") val localId: Long,
    val date: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String?,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("pages_read") val pagesRead: Int,
    @SerialName("sentences_read") val sentencesRead: Int,
    @SerialName("gold_stars") val goldStars: Int,
    @SerialName("silver_stars") val silverStars: Int,
    @SerialName("bronze_stars") val bronzeStars: Int,
    @SerialName("points_earned") val pointsEarned: Int
)

@Serializable
data class WordPayload(
    val word: String,
    @SerialName("normalized_word") val normalizedWord: String,
    @SerialName("total_attempts") val totalAttempts: Int,
    @SerialName("successful_attempts") val successfulAttempts: Int,
    @SerialName("consecutive_successes") val consecutiveSuccesses: Int,
    @SerialName("mastery_level") val masteryLevel: Int,
    @SerialName("last_attempt_at") val lastAttemptAt: String
)

@Serializable
data class ErrorPayload(
    val type: String,
    val severity: String,
    val message: String,
    @SerialName("stack_trace") val stackTrace: String? = null,
    val context: Map<String, String>? = null,
    val screen: String? = null,
    @SerialName("occurred_at") val occurredAt: String
)

@Serializable
data class SyncResponse(
    val success: Boolean,
    val synced: SyncedCounts? = null,
    val error: String? = null
)

@Serializable
data class SyncedCounts(
    val device: String,
    @SerialName("daily_stats") val dailyStats: Int,
    val books: Int,
    val sessions: Int,
    val words: Int,
    val errors: Int
)
