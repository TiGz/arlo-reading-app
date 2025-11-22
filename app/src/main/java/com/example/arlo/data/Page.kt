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
    val text: String,
    val imagePath: String, // Path to the original image
    val pageNumber: Int
)
