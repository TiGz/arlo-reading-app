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
    val pageNumber: Int,
    val sentencesJson: String? = null,       // JSON array of SentenceData
    val lastSentenceComplete: Boolean = true,  // For sentence continuation logic
    val detectedPageNumber: Int? = null,      // Page number extracted from OCR (if visible)
    // Queue management
    val processingStatus: String = "COMPLETED",  // PENDING, PROCESSING, COMPLETED, FAILED
    val errorMessage: String? = null,
    val retryCount: Int = 0
)
