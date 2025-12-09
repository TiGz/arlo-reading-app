package com.example.arlo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Book::class, Page::class], version = 4, exportSchema = false)
@TypeConverters(SentenceListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE books ADD COLUMN coverImagePath TEXT")
                database.execSQL("ALTER TABLE books ADD COLUMN lastReadPageNumber INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add sentence-related columns to pages
                database.execSQL("ALTER TABLE pages ADD COLUMN sentencesJson TEXT")
                database.execSQL("ALTER TABLE pages ADD COLUMN lastSentenceComplete INTEGER NOT NULL DEFAULT 1")
                // Add sentence index tracking to books
                database.execSQL("ALTER TABLE books ADD COLUMN lastReadSentenceIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add OCR queue management columns to pages
                database.execSQL("ALTER TABLE pages ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'COMPLETED'")
                database.execSQL("ALTER TABLE pages ADD COLUMN errorMessage TEXT")
                database.execSQL("ALTER TABLE pages ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arlo_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
