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
        ReadingSession::class
    ],
    version = 9,
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arlo_database"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
