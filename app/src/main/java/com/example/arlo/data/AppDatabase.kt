package com.example.arlo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Book::class,
        Page::class,
        DailyStats::class,
        DifficultWord::class,
        CollaborativeAttempt::class,
        Achievement::class,
        WeeklyGoal::class,
        BookStats::class,
        // New entities for gamification v2
        StreakState::class,
        ParentSettings::class,
        ReadingSession::class,
        CompletedSentence::class,
        GameSessionRecord::class,
        // New entities for milestone rewards system
        MilestoneClaimRecord::class,
        SentenceCompletionState::class
    ],
    version = 14,
    exportSchema = false
)
@TypeConverters(SentenceListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingStatsDao(): ReadingStatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverImagePath TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN lastReadPageNumber INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add sentence-related columns to pages
                db.execSQL("ALTER TABLE pages ADD COLUMN sentencesJson TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN lastSentenceComplete INTEGER NOT NULL DEFAULT 1")
                // Add sentence index tracking to books
                db.execSQL("ALTER TABLE books ADD COLUMN lastReadSentenceIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add OCR queue management columns to pages
                db.execSQL("ALTER TABLE pages ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'COMPLETED'")
                db.execSQL("ALTER TABLE pages ADD COLUMN errorMessage TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add detected page number column for OCR-extracted page numbers
                db.execSQL("ALTER TABLE pages ADD COLUMN detectedPageNumber INTEGER")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add confidence score column for OCR quality
                db.execSQL("ALTER TABLE pages ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Replace detectedPageNumber (Int) with detectedPageLabel (String) for roman numerals
                // Add chapterTitle for chapter headings
                db.execSQL("ALTER TABLE pages ADD COLUMN detectedPageLabel TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN chapterTitle TEXT")
                // Copy existing detectedPageNumber values to detectedPageLabel as strings
                db.execSQL("UPDATE pages SET detectedPageLabel = CAST(detectedPageNumber AS TEXT) WHERE detectedPageNumber IS NOT NULL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create gamification tables
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_stats (
                        date TEXT PRIMARY KEY NOT NULL,
                        sentencesRead INTEGER NOT NULL DEFAULT 0,
                        pagesCompleted INTEGER NOT NULL DEFAULT 0,
                        booksCompleted INTEGER NOT NULL DEFAULT 0,
                        starsEarned INTEGER NOT NULL DEFAULT 0,
                        perfectWords INTEGER NOT NULL DEFAULT 0,
                        totalCollaborativeAttempts INTEGER NOT NULL DEFAULT 0,
                        successfulCollaborativeAttempts INTEGER NOT NULL DEFAULT 0,
                        longestStreak INTEGER NOT NULL DEFAULT 0,
                        totalReadingTimeMs INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS difficult_words (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word TEXT NOT NULL,
                        normalizedWord TEXT NOT NULL,
                        totalAttempts INTEGER NOT NULL DEFAULT 0,
                        successfulAttempts INTEGER NOT NULL DEFAULT 0,
                        consecutiveSuccesses INTEGER NOT NULL DEFAULT 0,
                        firstSeenDate INTEGER NOT NULL DEFAULT 0,
                        lastAttemptDate INTEGER NOT NULL DEFAULT 0,
                        lastSpokenAs TEXT,
                        contextSentence TEXT,
                        bookId INTEGER,
                        masteryLevel INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_difficult_words_word ON difficult_words(word)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS collaborative_attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        pageId INTEGER NOT NULL,
                        targetWord TEXT NOT NULL,
                        spokenWord TEXT,
                        isCorrect INTEGER NOT NULL DEFAULT 0,
                        attemptNumber INTEGER NOT NULL DEFAULT 1,
                        isFirstTrySuccess INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collaborative_attempts_bookId ON collaborative_attempts(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collaborative_attempts_timestamp ON collaborative_attempts(timestamp)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS achievements (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        iconName TEXT NOT NULL,
                        unlockedAt INTEGER,
                        progress INTEGER NOT NULL DEFAULT 0,
                        goal INTEGER NOT NULL DEFAULT 1
                    )
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS weekly_goals (
                        weekStart TEXT PRIMARY KEY NOT NULL,
                        targetDays INTEGER NOT NULL DEFAULT 5,
                        completedDays INTEGER NOT NULL DEFAULT 0,
                        daysWithActivity TEXT NOT NULL DEFAULT '',
                        graceDaysUsed INTEGER NOT NULL DEFAULT 0,
                        weeklyStreakCount INTEGER NOT NULL DEFAULT 0,
                        bonusStarsEarned INTEGER NOT NULL DEFAULT 0
                    )
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS book_stats (
                        bookId INTEGER PRIMARY KEY NOT NULL,
                        totalSentencesRead INTEGER NOT NULL DEFAULT 0,
                        totalPagesRead INTEGER NOT NULL DEFAULT 0,
                        perfectWordsCount INTEGER NOT NULL DEFAULT 0,
                        totalCollaborativeWords INTEGER NOT NULL DEFAULT 0,
                        longestStreak INTEGER NOT NULL DEFAULT 0,
                        totalStarsEarned INTEGER NOT NULL DEFAULT 0,
                        startedAt INTEGER NOT NULL DEFAULT 0,
                        completedAt INTEGER,
                        totalReadingTimeMs INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_book_stats_bookId ON book_stats(bookId)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ============ MODIFY daily_stats - add star types and points ============
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN goldStars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN silverStars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN bronzeStars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN totalPoints INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN dailyPointsTarget INTEGER NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN goalMet INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN activeReadingTimeMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN totalAppTimeMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN sessionCount INTEGER NOT NULL DEFAULT 0")

                // Migrate existing starsEarned to goldStars (they were all first-try)
                db.execSQL("UPDATE daily_stats SET goldStars = starsEarned, totalPoints = starsEarned * 5")

                // ============ MODIFY collaborative_attempts - add star tracking ============
                db.execSQL("ALTER TABLE collaborative_attempts ADD COLUMN starType TEXT")
                db.execSQL("ALTER TABLE collaborative_attempts ADD COLUMN pointsEarned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE collaborative_attempts ADD COLUMN ttsPronouncedWord INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE collaborative_attempts ADD COLUMN sessionStreak INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE collaborative_attempts ADD COLUMN streakBonus INTEGER NOT NULL DEFAULT 0")

                // Migrate existing first-try successes to GOLD star type
                db.execSQL("""
                    UPDATE collaborative_attempts
                    SET starType = 'GOLD', pointsEarned = 5
                    WHERE isFirstTrySuccess = 1
                """)

                // ============ CREATE streak_state table ============
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS streak_state (
                        streakType TEXT PRIMARY KEY NOT NULL,
                        currentStreak INTEGER NOT NULL DEFAULT 0,
                        bestStreak INTEGER NOT NULL DEFAULT 0,
                        lastActivityDate TEXT NOT NULL DEFAULT '',
                        lastActivityWeek TEXT NOT NULL DEFAULT '',
                        lastActivityMonth TEXT NOT NULL DEFAULT '',
                        lastActivityTimestamp INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // Initialize streak state rows
                val types = listOf("session", "day", "week", "month", "allTime")
                types.forEach { type ->
                    db.execSQL("INSERT OR IGNORE INTO streak_state (streakType) VALUES ('$type')")
                }

                // ============ CREATE parent_settings table ============
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS parent_settings (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        dailyPointsTarget INTEGER NOT NULL DEFAULT 100,
                        weeklyDaysTarget INTEGER NOT NULL DEFAULT 5,
                        enableStreakBonuses INTEGER NOT NULL DEFAULT 1,
                        maxStreakMultiplier REAL NOT NULL DEFAULT 2.0,
                        kidModeEnabled INTEGER NOT NULL DEFAULT 1,
                        pinCode TEXT,
                        lastModified INTEGER NOT NULL DEFAULT 0
                    )
                """)
                // Insert default settings
                db.execSQL("INSERT OR IGNORE INTO parent_settings (id) VALUES (1)")

                // ============ CREATE reading_sessions table ============
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        startTimestamp INTEGER NOT NULL,
                        endTimestamp INTEGER,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        bookId INTEGER,
                        pagesRead INTEGER NOT NULL DEFAULT 0,
                        sentencesRead INTEGER NOT NULL DEFAULT 0,
                        goldStars INTEGER NOT NULL DEFAULT 0,
                        silverStars INTEGER NOT NULL DEFAULT 0,
                        bronzeStars INTEGER NOT NULL DEFAULT 0,
                        pointsEarned INTEGER NOT NULL DEFAULT 0,
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_date ON reading_sessions(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_bookId ON reading_sessions(bookId)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add goalMetFinal column to daily_stats for immutable historical goal tracking
                // null = day in progress, true/false = permanently locked at day end
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN goalMetFinal INTEGER")

                // Finalize all past days (not today) - lock their goal status based on what was recorded
                // This uses the goalMet value that was calculated with the target at the time
                db.execSQL("""
                    UPDATE daily_stats
                    SET goalMetFinal = goalMet
                    WHERE date < date('now')
                """)
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add sentenceIndex to collaborative_attempts for tracking which sentence was attempted
                db.execSQL("ALTER TABLE collaborative_attempts ADD COLUMN sentenceIndex INTEGER NOT NULL DEFAULT 0")

                // Create completed_sentences table to track sentences that have been successfully starred
                // This prevents gaming by re-reading the same sentences to farm stars
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS completed_sentences (
                        bookId INTEGER NOT NULL,
                        pageId INTEGER NOT NULL,
                        sentenceIndex INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL DEFAULT 0,
                        starType TEXT NOT NULL,
                        PRIMARY KEY (bookId, pageId, sentenceIndex)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_completed_sentences_book_page ON completed_sentences(bookId, pageId)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add game reward fields to daily_stats
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesEarned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN gameRewardClaimed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN lastGamePlayedAt INTEGER")

                // Add game reward settings to parent_settings
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN gameRewardsEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN maxRacesPerDay INTEGER NOT NULL DEFAULT 3")

                // Create game_sessions table for history tracking
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS game_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        gameId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        racesPlayed INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        raceResults TEXT
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_game_sessions_date ON game_sessions(date)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Fix for incomplete 11→12 migration: add missing game reward columns to parent_settings
                // These columns may already exist if 11→12 ran with the fixed migration
                try {
                    db.execSQL("ALTER TABLE parent_settings ADD COLUMN gameRewardsEnabled INTEGER NOT NULL DEFAULT 1")
                } catch (e: Exception) {
                    // Column already exists, ignore
                }
                try {
                    db.execSQL("ALTER TABLE parent_settings ADD COLUMN maxRacesPerDay INTEGER NOT NULL DEFAULT 3")
                } catch (e: Exception) {
                    // Column already exists, ignore
                }
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ============ Add resolvedChapter column to pages ============
                db.execSQL("ALTER TABLE pages ADD COLUMN resolvedChapter TEXT")

                // ============ Add milestone settings to parent_settings ============
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN racesForDailyTarget INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN racesPerMultiple INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN streakThreshold INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN racesPerStreakAchievement INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN racesPerPageCompletion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN racesPerChapterCompletion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN racesPerBookCompletion INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE parent_settings ADD COLUMN completionThreshold REAL NOT NULL DEFAULT 0.8")

                // ============ Add milestone tracking columns to daily_stats ============
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesFromDailyTarget INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesFromMultiples INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesFromStreaks INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesFromPages INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesFromChapters INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN racesFromBooks INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN highestMultipleReached INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_stats ADD COLUMN highestStreakMilestone INTEGER NOT NULL DEFAULT 0")

                // ============ Create milestone_claims table ============
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS milestone_claims (
                        date TEXT NOT NULL,
                        milestoneType TEXT NOT NULL,
                        milestoneId TEXT NOT NULL,
                        racesAwarded INTEGER NOT NULL,
                        claimedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (date, milestoneType, milestoneId)
                    )
                """)

                // ============ Create sentence_completion_state table ============
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sentence_completion_state (
                        bookId INTEGER NOT NULL,
                        pageId INTEGER NOT NULL,
                        sentenceIndex INTEGER NOT NULL,
                        resolvedChapter TEXT,
                        hasCollaborativeOpportunity INTEGER NOT NULL DEFAULT 1,
                        wasAttempted INTEGER NOT NULL DEFAULT 0,
                        wasCompletedSuccessfully INTEGER NOT NULL DEFAULT 0,
                        wasSkippedByTTS INTEGER NOT NULL DEFAULT 0,
                        starType TEXT,
                        completedAt INTEGER,
                        PRIMARY KEY (bookId, pageId, sentenceIndex)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sentence_completion_state_book_page ON sentence_completion_state(bookId, pageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sentence_completion_state_book_chapter ON sentence_completion_state(bookId, resolvedChapter)")

                // ============ Backfill resolvedChapter for existing pages ============
                // Step 1: Set resolvedChapter = chapterTitle where chapterTitle exists
                db.execSQL("UPDATE pages SET resolvedChapter = chapterTitle WHERE chapterTitle IS NOT NULL")

                // Step 2: Backfill from previous pages (propagate chapter forward)
                backfillResolvedChapters(db)
            }

            private fun backfillResolvedChapters(db: SupportSQLiteDatabase) {
                // Get all books
                val booksCursor = db.query("SELECT DISTINCT bookId FROM pages")
                val bookIds = mutableListOf<Long>()
                while (booksCursor.moveToNext()) {
                    bookIds.add(booksCursor.getLong(0))
                }
                booksCursor.close()

                for (bookId in bookIds) {
                    // Get pages in order for this book
                    val pagesCursor = db.query(
                        "SELECT id, pageNumber, chapterTitle, resolvedChapter FROM pages WHERE bookId = ? ORDER BY pageNumber ASC",
                        arrayOf(bookId.toString())
                    )

                    var lastKnownChapter: String? = null
                    while (pagesCursor.moveToNext()) {
                        val pageId = pagesCursor.getLong(0)
                        val chapterTitle = if (pagesCursor.isNull(2)) null else pagesCursor.getString(2)
                        val resolvedChapter = if (pagesCursor.isNull(3)) null else pagesCursor.getString(3)

                        // If this page has a chapter title, update lastKnownChapter
                        if (chapterTitle != null) {
                            lastKnownChapter = chapterTitle
                        }

                        // If resolvedChapter is null but we have a lastKnownChapter, backfill
                        if (resolvedChapter == null && lastKnownChapter != null) {
                            db.execSQL(
                                "UPDATE pages SET resolvedChapter = ? WHERE id = ?",
                                arrayOf(lastKnownChapter, pageId)
                            )
                        }
                    }
                    pagesCursor.close()
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arlo_database"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
