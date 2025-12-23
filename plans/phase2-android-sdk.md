# Phase 2: Android SDK Implementation Plan

## Overview

This document provides a detailed implementation plan for integrating the Supabase cloud sync SDK into the Arlo Android app. Phase 1 (Supabase infrastructure) is complete - database schema, edge functions, and migrations are deployed.

**Goal:** Enable the Android app to sync reading statistics to Supabase, supporting remote progress monitoring and error logging.

---

## Prerequisites (Completed)

- [x] Supabase project created and linked (`qqhkximogdndkfiunewm`)
- [x] Database schema deployed (`supabase/migrations/20251223120000_initial_schema.sql`)
- [x] Edge functions deployed (`sync-stats`, `query-stats`)
- [x] RLS policies configured for service role access

---

## Implementation Tasks

### Task 1: Add Gradle Dependencies

**File:** `app/build.gradle.kts`

Add the Supabase Kotlin SDK and WorkManager for background sync:

```kotlin
// Add after existing dependencies (around line 127)

// Supabase Cloud Sync
implementation(platform("io.github.jan-tennert.supabase:bom:3.0.0"))
implementation("io.github.jan-tennert.supabase:functions-kt")
implementation("io.ktor:ktor-client-android:2.3.7")

// WorkManager for background sync
implementation("androidx.work:work-runtime-ktx:2.9.0")

// Kotlinx Serialization (required by Supabase SDK)
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
```

Also add the serialization plugin to the plugins block:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"  // Add this
}
```

Add BuildConfig fields for Supabase credentials:

```kotlin
// In defaultConfig block (after KOKORO_SERVER_URL)
val supabaseUrl = localProperties.getProperty("SUPABASE_URL")
    ?: System.getenv("SUPABASE_URL")
    ?: ""
buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")

val supabaseAnonKey = localProperties.getProperty("SUPABASE_ANON_KEY")
    ?: System.getenv("SUPABASE_ANON_KEY")
    ?: ""
buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
```

---

### Task 2: Update local.properties

Add Supabase credentials (these are already available from the Supabase dashboard):

```properties
# Supabase Cloud Sync
SUPABASE_URL=https://qqhkximogdndkfiunewm.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFxaGt4aW1vZ2RuZGtmaXVuZXdtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjY0OTE3MTEsImV4cCI6MjA4MjA2NzcxMX0.UmxARCF72W6zmsgi1zzayZxS1-0P6-luq8XQZHyolBc
```

---

### Task 3: Create Sync Package Structure

Create the new sync package directory:
```
app/src/main/java/com/example/arlo/sync/
├── SupabaseConfig.kt       # Supabase client initialization
├── SyncPayloads.kt         # Data classes for API payloads
├── CloudSyncManager.kt     # Main sync orchestration
├── SyncWorker.kt           # WorkManager background sync
└── ErrorLogger.kt          # Centralized error logging utility
```

---

### Task 4: Implement SupabaseConfig.kt

**Purpose:** Initialize Supabase client and provide device identification.

```kotlin
package com.example.arlo.sync

import android.content.Context
import android.provider.Settings
import com.example.arlo.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.*

object SupabaseConfig {

    private var isInitialized = false

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Functions) {
                // Configure timeout for slow connections
                httpRequestOverride = {
                    timeout {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 10_000
                    }
                }
            }
        }.also { isInitialized = true }
    }

    /**
     * Check if Supabase is properly configured.
     * Returns false if credentials are missing.
     */
    fun isConfigured(): Boolean {
        return BuildConfig.SUPABASE_URL.isNotBlank() &&
               BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    }

    /**
     * Get the Android device ID for identifying this tablet.
     * This ID persists across app reinstalls but not factory resets.
     */
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }
}
```

**Key considerations:**
- Lazy initialization prevents crashes if credentials are missing
- `isConfigured()` check allows graceful degradation
- Device ID is stable across reinstalls (good for single-tablet use case)

---

### Task 5: Implement SyncPayloads.kt

**Purpose:** Define serializable data classes matching the Edge Function API.

```kotlin
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
```

---

### Task 6: Implement CloudSyncManager.kt

**Purpose:** Core sync orchestration - gathers data from Room and sends to Supabase.

```kotlin
package com.example.arlo.sync

import android.content.Context
import android.util.Log
import com.example.arlo.BuildConfig
import com.example.arlo.data.BookRepository
import com.example.arlo.data.ReadingStatsRepository
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.*
import io.ktor.http.*
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
```

---

### Task 7: Add Missing Repository Methods

**File:** `app/src/main/java/com/example/arlo/data/BookRepository.kt`

Add the `getAllBooksSync` method:

```kotlin
/**
 * Get all books synchronously (for sync operations).
 */
suspend fun getAllBooksSync(): List<Book> {
    return bookDao.getAllBooksSync()
}
```

**File:** `app/src/main/java/com/example/arlo/data/BookDao.kt`

Add the DAO query:

```kotlin
@Query("SELECT * FROM books ORDER BY createdAt DESC")
suspend fun getAllBooksSync(): List<Book>
```

**File:** `app/src/main/java/com/example/arlo/data/ReadingStatsRepository.kt`

Add the `getSessionsForRange` method (already exists in DAO, add wrapper):

```kotlin
/**
 * Get reading sessions for a date range.
 */
suspend fun getSessionsForRange(startDate: String, endDate: String): List<ReadingSession> {
    return dao.getSessionsForRange(startDate, endDate)
}
```

---

### Task 8: Implement SyncWorker.kt

**Purpose:** Background periodic sync using WorkManager.

```kotlin
package com.example.arlo.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.arlo.ArloApplication
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tag = "SyncWorker"

        return try {
            val app = applicationContext as ArloApplication
            val syncManager = app.cloudSyncManager

            Log.d(tag, "Starting background sync")
            val result = syncManager.syncAll()

            if (result.success) {
                Log.d(tag, "Background sync completed successfully: ${result.synced}")
                Result.success()
            } else {
                Log.w(tag, "Sync failed: ${result.error}")
                // Retry on failure (with exponential backoff)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(tag, "Sync worker error", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "arlo_cloud_sync"
        private const val TAG = "SyncWorker"

        /**
         * Schedule periodic background sync (every 15 minutes when connected).
         */
        fun schedulePeriodicSync(context: Context) {
            // Skip if Supabase not configured
            if (!SupabaseConfig.isConfigured()) {
                Log.d(TAG, "Supabase not configured, skipping periodic sync schedule")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES,  // Repeat interval
                5, TimeUnit.MINUTES    // Flex interval (can run 5 min early)
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // Don't replace if already scheduled
                syncRequest
            )

            Log.d(TAG, "Scheduled periodic sync (every 15 minutes)")
        }

        /**
         * Trigger an immediate one-time sync.
         */
        fun syncNow(context: Context) {
            if (!SupabaseConfig.isConfigured()) {
                Log.d(TAG, "Supabase not configured, skipping immediate sync")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag("${WORK_NAME}_immediate")
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
            Log.d(TAG, "Queued immediate sync")
        }

        /**
         * Cancel all scheduled syncs.
         */
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic sync")
        }
    }
}
```

---

### Task 9: Implement ErrorLogger.kt

**Purpose:** Centralized error logging utility for app-wide use.

```kotlin
package com.example.arlo.sync

import android.content.Context
import android.util.Log

/**
 * Centralized error logging utility.
 * Logs to both Android logcat and queues for cloud sync.
 */
object ErrorLogger {
    private var syncManager: CloudSyncManager? = null
    private var currentScreen: String? = null

    /**
     * Initialize with CloudSyncManager reference.
     * Call from ArloApplication.onCreate()
     */
    fun init(manager: CloudSyncManager) {
        syncManager = manager
    }

    /**
     * Set the current screen name for error context.
     * Call from Fragment.onResume() or Activity lifecycle.
     */
    fun setCurrentScreen(screen: String) {
        currentScreen = screen
    }

    /**
     * Log an OCR error.
     */
    fun logOCRError(
        message: String,
        exception: Throwable? = null,
        context: Map<String, String>? = null
    ) {
        val fullMessage = if (exception != null) "$message: ${exception.message}" else message
        Log.e("OCR", fullMessage, exception)

        syncManager?.logError(
            type = "ocr_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = context,
            screen = currentScreen
        )
    }

    /**
     * Log a TTS error.
     */
    fun logTTSError(
        message: String,
        exception: Throwable? = null,
        context: Map<String, String>? = null
    ) {
        val fullMessage = if (exception != null) "$message: ${exception.message}" else message
        Log.e("TTS", fullMessage, exception)

        syncManager?.logError(
            type = "tts_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = context,
            screen = currentScreen
        )
    }

    /**
     * Log an API error (Claude, Kokoro, etc.).
     */
    fun logAPIError(
        service: String,
        message: String,
        statusCode: Int? = null,
        exception: Throwable? = null
    ) {
        val fullMessage = if (statusCode != null) "[$statusCode] $message" else message
        Log.e("API:$service", fullMessage, exception)

        val errorContext = mutableMapOf("service" to service)
        statusCode?.let { errorContext["status_code"] = it.toString() }

        syncManager?.logError(
            type = "api_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = errorContext,
            screen = currentScreen
        )
    }

    /**
     * Log a speech recognition error.
     */
    fun logSpeechError(
        message: String,
        errorCode: Int? = null,
        exception: Throwable? = null
    ) {
        val fullMessage = if (errorCode != null) "[Code $errorCode] $message" else message
        Log.e("Speech", fullMessage, exception)

        val errorContext = mutableMapOf<String, String>()
        errorCode?.let { errorContext["error_code"] = it.toString() }

        syncManager?.logError(
            type = "speech_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = errorContext,
            screen = currentScreen
        )
    }

    /**
     * Log an unexpected error.
     */
    fun logUnexpectedError(
        tag: String,
        message: String,
        exception: Throwable? = null,
        context: Map<String, String>? = null
    ) {
        val fullMessage = if (exception != null) "$message: ${exception.message}" else message
        Log.e(tag, fullMessage, exception)

        syncManager?.logError(
            type = "unexpected",
            message = "[$tag] $fullMessage",
            stackTrace = exception?.stackTraceToString(),
            errorContext = context,
            screen = currentScreen
        )
    }

    /**
     * Log a fatal crash.
     */
    fun logCrash(exception: Throwable) {
        Log.e("CRASH", "Fatal crash", exception)

        syncManager?.logError(
            type = "crash",
            severity = "fatal",
            message = exception.message ?: "Unknown crash",
            stackTrace = exception.stackTraceToString(),
            screen = currentScreen
        )
    }
}
```

---

### Task 10: Update ArloApplication.kt

**Purpose:** Initialize CloudSyncManager and schedule background sync.

Add to `ArloApplication.kt`:

```kotlin
// Add import at top
import com.example.arlo.sync.CloudSyncManager
import com.example.arlo.sync.ErrorLogger
import com.example.arlo.sync.SupabaseConfig
import com.example.arlo.sync.SyncWorker

// Add property after gameRewardsManager
val cloudSyncManager: CloudSyncManager by lazy {
    CloudSyncManager(
        context = this,
        statsRepository = statsRepository,
        bookRepository = repository
    )
}

// In onCreate(), add after existing initialization (after preCacheVoicePreviews())
// Initialize cloud sync
if (SupabaseConfig.isConfigured()) {
    Log.d("ArloApplication", "Supabase configured, initializing cloud sync")
    ErrorLogger.init(cloudSyncManager)
    SyncWorker.schedulePeriodicSync(this)

    // Sync immediately on app start
    applicationScope.launch {
        cloudSyncManager.syncAll()
    }
} else {
    Log.d("ArloApplication", "Supabase not configured, cloud sync disabled")
}
```

---

### Task 11: Add Lifecycle Sync Triggers

**Purpose:** Sync when app goes to background (in case of crash/kill).

**File:** Create `app/src/main/java/com/example/arlo/sync/AppLifecycleObserver.kt`

```kotlin
package com.example.arlo.sync

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Observes app lifecycle to trigger sync when app goes to background.
 */
class AppLifecycleObserver(
    private val cloudSyncManager: CloudSyncManager,
    private val scope: CoroutineScope
) : DefaultLifecycleObserver {

    private val tag = "AppLifecycle"

    override fun onStop(owner: LifecycleOwner) {
        // App going to background - sync to ensure data is saved
        Log.d(tag, "App going to background, triggering sync")
        scope.launch {
            cloudSyncManager.syncAll()
        }
    }

    companion object {
        /**
         * Register the lifecycle observer with the process lifecycle.
         */
        fun register(manager: CloudSyncManager, scope: CoroutineScope) {
            if (!SupabaseConfig.isConfigured()) return

            ProcessLifecycleOwner.get().lifecycle.addObserver(
                AppLifecycleObserver(manager, scope)
            )
            Log.d("AppLifecycle", "Registered lifecycle observer for background sync")
        }
    }
}
```

Add to `ArloApplication.onCreate()` after `ErrorLogger.init()`:

```kotlin
AppLifecycleObserver.register(cloudSyncManager, applicationScope)
```

---

### Task 12: Add ProcessLifecycleOwner Dependency

**File:** `app/build.gradle.kts`

Add lifecycle process dependency:

```kotlin
// In dependencies block
implementation("androidx.lifecycle:lifecycle-process:2.7.0")
```

---

## Testing Plan

### Unit Tests

1. **SyncPayload serialization** - Verify JSON roundtrip
2. **CloudSyncManager.gatherDailyStats()** - Mock DAO, verify mapping
3. **ErrorLogger** - Verify error queuing and cap at 100

### Integration Tests

1. **Full sync cycle** - Create Room data, sync, verify via query-stats
2. **Error sync** - Queue errors, sync, verify in error_logs table
3. **Offline resilience** - Disable network, verify no crash

### Manual Testing Checklist

- [ ] Build succeeds with new dependencies
- [ ] App launches without crash (Supabase configured)
- [ ] App launches without crash (Supabase not configured)
- [ ] Check logcat for "Starting sync" on app start
- [ ] Verify sync completes: "Sync successful"
- [ ] Generate reading activity, wait 15 min, verify periodic sync
- [ ] Put app in background, verify sync triggers
- [ ] Query stats via curl: `curl "https://qqhkximogdndkfiunewm.supabase.co/functions/v1/query-stats?q=today&device=Test%20Device"`

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Supabase credentials leaked | Use local.properties (gitignored), BuildConfig fields |
| Network failures crash app | Wrap all sync in try-catch, graceful degradation |
| Background sync drains battery | WorkManager with network constraint, 15-min minimum |
| Sync conflicts | Server-side UPSERT handles duplicates idempotently |
| Data loss on sync failure | Local Room DB is source of truth; sync is supplementary |

---

## Implementation Order

1. **Task 1-2:** Gradle dependencies and local.properties (foundation)
2. **Task 3-5:** Sync package structure and config (basic setup)
3. **Task 6-7:** CloudSyncManager and repository methods (core sync)
4. **Task 8:** SyncWorker (background sync)
5. **Task 9-10:** ErrorLogger and ArloApplication integration
6. **Task 11-12:** Lifecycle observer for background sync

**Estimated effort:** 4-6 hours for implementation, 2 hours for testing.

---

## Success Criteria

1. App syncs reading stats to Supabase on:
   - App startup
   - Every 15 minutes while active
   - When app goes to background

2. Errors are logged to Supabase for remote debugging

3. Sync works with:
   - Good network (immediate success)
   - No network (no crash, retries later)
   - Partial network (exponential backoff)

4. Can query stats via curl/Claude Code skill:
   ```bash
   curl "https://qqhkximogdndkfiunewm.supabase.co/functions/v1/query-stats?q=today"
   ```
