package com.example.arlo.sync

import android.content.Context
import android.util.Log
import com.example.arlo.BuildConfig
import com.example.arlo.data.BookRepository
import com.example.arlo.data.ReadingStatsRepository
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class CloudSyncManager(
    private val context: Context,
    private val statsRepository: ReadingStatsRepository,
    private val bookRepository: BookRepository
) {
    private val tag = "CloudSync"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()  // Prevent concurrent syncs

    // Thread-safe pending errors list
    private val pendingErrors = CopyOnWriteArrayList<ErrorPayload>()

    /**
     * Perform a full sync of all changed data.
     * Thread-safe - only one sync can run at a time.
     */
    suspend fun syncAll(): SyncResponse = withContext(Dispatchers.IO) {
        // Skip if not configured
        if (!SupabaseConfig.isConfigured()) {
            Log.d(tag, "Supabase not configured, skipping sync")
            return@withContext SyncResponse(success = false, error = "Not configured")
        }

        syncMutex.withLock {
            try {
                val deviceId = SupabaseConfig.getDeviceId(context)
                val lastSync = prefs.getLong("last_sync", 0L)

                Log.d(tag, "Starting sync. Device: $deviceId, Last sync: ${Date(lastSync)}")

                // Gather data to sync (last 7 days to catch any missed updates)
                val dailyStats = gatherDailyStats()
                val books = gatherBooks()
                val sessions = gatherSessions()
                val words = gatherDifficultWords()
                val errors = pendingErrors.toList()

                // Build payload
                val payload = SyncPayload(
                    deviceId = deviceId,
                    deviceName = "Arlo's Tablet",
                    appVersion = BuildConfig.VERSION_NAME,
                    dailyStats = dailyStats.takeIf { it.isNotEmpty() },
                    books = books.takeIf { it.isNotEmpty() },
                    sessions = sessions.takeIf { it.isNotEmpty() },
                    difficultWords = words.takeIf { it.isNotEmpty() },
                    errors = errors.takeIf { it.isNotEmpty() }
                )

                Log.d(tag, "Syncing: ${dailyStats.size} stats, ${books.size} books, " +
                        "${sessions.size} sessions, ${words.size} words, ${errors.size} errors")

                // Call Edge Function
                val response = SupabaseConfig.client.functions.invoke(
                    function = "sync-stats",
                    body = payload
                )

                val result: SyncResponse = response.body()

                if (result.success) {
                    // Clear synced errors
                    pendingErrors.clear()
                    // Update last sync time
                    prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                    Log.d(tag, "Sync successful: ${result.synced}")
                } else {
                    Log.e(tag, "Sync failed: ${result.error}")
                }

                result

            } catch (e: Exception) {
                Log.e(tag, "Sync error", e)
                SyncResponse(success = false, error = e.message)
            }
        }
    }

    /**
     * Queue an error for cloud sync.
     * Errors are batched and sent on next sync.
     */
    fun logError(
        type: String,
        message: String,
        severity: String = "error",
        stackTrace: String? = null,
        errorContext: Map<String, String>? = null,
        screen: String? = null
    ) {
        val error = ErrorPayload(
            type = type,
            severity = severity,
            message = message,
            stackTrace = stackTrace,
            context = errorContext,
            screen = screen,
            occurredAt = isoFormat.format(Date())
        )
        pendingErrors.add(error)

        // Cap pending errors at 100 (keep most recent)
        while (pendingErrors.size > 100) {
            pendingErrors.removeAt(0)
        }

        Log.d(tag, "Queued error for sync: $type - $message")
    }

    /**
     * Check if there are pending errors waiting to be synced.
     */
    fun hasPendingErrors(): Boolean = pendingErrors.isNotEmpty()

    /**
     * Get count of pending errors.
     */
    fun getPendingErrorCount(): Int = pendingErrors.size

    /**
     * Get the last sync timestamp.
     */
    fun getLastSyncTime(): Long = prefs.getLong("last_sync", 0L)

    private suspend fun gatherDailyStats(): List<DailyStatsPayload> {
        val stats = statsRepository.getRecentDailyStats(7)
        return stats.map { s ->
            DailyStatsPayload(
                date = s.date,
                goldStars = s.goldStars,
                silverStars = s.silverStars,
                bronzeStars = s.bronzeStars,
                totalPoints = s.totalPoints,
                dailyPointsTarget = s.dailyPointsTarget,
                goalMet = s.goalMet,
                sentencesRead = s.sentencesRead,
                pagesCompleted = s.pagesCompleted,
                booksCompleted = s.booksCompleted,
                perfectWords = s.perfectWords,
                totalCollaborativeAttempts = s.totalCollaborativeAttempts,
                successfulCollaborativeAttempts = s.successfulCollaborativeAttempts,
                longestStreak = s.longestStreak,
                activeReadingTimeMs = s.activeReadingTimeMs,
                totalAppTimeMs = s.totalAppTimeMs,
                sessionCount = s.sessionCount,
                racesEarned = s.racesEarned,
                racesUsed = s.racesUsed,
                gameRewardClaimed = s.gameRewardClaimed,
                updatedAt = isoFormat.format(Date(s.updatedAt))
            )
        }
    }

    private suspend fun gatherBooks(): List<BookPayload> {
        val books = bookRepository.getAllBooksSync()
        return books.map { book ->
            val pageCount = bookRepository.getPageCount(book.id)
            val stats = statsRepository.getOrCreateBookStats(book.id)
            BookPayload(
                localId = book.id,
                title = book.title,
                totalPages = pageCount,
                pagesRead = book.lastReadPageNumber,
                currentPage = book.lastReadPageNumber,
                currentSentence = book.lastReadSentenceIndex,
                completedAt = stats.completedAt?.let { isoFormat.format(Date(it)) },
                totalStarsEarned = stats.totalStarsEarned,
                totalReadingTimeMs = stats.totalReadingTimeMs
            )
        }
    }

    private suspend fun gatherSessions(): List<SessionPayload> {
        val today = dateFormat.format(Date())
        val weekAgo = dateFormat.format(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000))
        val sessions = statsRepository.getSessionsForRange(weekAgo, today)

        return sessions.filter { !it.isActive }.map { s ->
            SessionPayload(
                localId = s.id,
                date = s.date,
                startedAt = isoFormat.format(Date(s.startTimestamp)),
                endedAt = s.endTimestamp?.let { isoFormat.format(Date(it)) },
                durationMs = s.durationMs,
                pagesRead = s.pagesRead,
                sentencesRead = s.sentencesRead,
                goldStars = s.goldStars,
                silverStars = s.silverStars,
                bronzeStars = s.bronzeStars,
                pointsEarned = s.pointsEarned
            )
        }
    }

    private suspend fun gatherDifficultWords(): List<WordPayload> {
        val words = statsRepository.getPracticeWords(50)
        return words.map { w ->
            WordPayload(
                word = w.word,
                normalizedWord = w.normalizedWord,
                totalAttempts = w.totalAttempts,
                successfulAttempts = w.successfulAttempts,
                consecutiveSuccesses = w.consecutiveSuccesses,
                masteryLevel = w.masteryLevel,
                lastAttemptAt = isoFormat.format(Date(w.lastAttemptDate))
            )
        }
    }
}
