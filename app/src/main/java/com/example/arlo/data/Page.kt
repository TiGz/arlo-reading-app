package com.example.arlo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"])]
)
data class Page(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val text: String = "",
    val imagePath: String,
    val pageNumber: Int,                       // Sequence order in book (1, 2, 3...)
    val sentencesJson: String? = null,         // JSON array of SentenceData
    val lastSentenceComplete: Boolean = true,  // For sentence continuation logic
    val detectedPageLabel: String? = null,     // Page label as printed (e.g., "xi", "42", "Prologue")
    val chapterTitle: String? = null,          // Chapter title if present on page (raw OCR)
    val resolvedChapter: String? = null,       // Inferred chapter from this or previous pages
    val confidence: Float = 1.0f,              // OCR confidence score (0.0-1.0)
    // Queue management
    val processingStatus: String = "COMPLETED",  // PENDING, PROCESSING, COMPLETED, FAILED
    val errorMessage: String? = null,
    val retryCount: Int = 0
)
