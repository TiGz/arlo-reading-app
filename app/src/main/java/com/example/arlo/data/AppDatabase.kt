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
        BookStats::class
    ],
    version = 8,
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arlo_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
