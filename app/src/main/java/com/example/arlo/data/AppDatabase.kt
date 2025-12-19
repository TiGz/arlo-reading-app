package com.example.arlo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Book::class, Page::class], version = 7, exportSchema = false)
@TypeConverters(SentenceListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arlo_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
