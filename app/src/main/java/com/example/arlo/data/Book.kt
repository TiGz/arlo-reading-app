package com.example.arlo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastReadPageId: Long? = null,
    val coverImagePath: String? = null,
    val lastReadPageNumber: Int = 1,
    val lastReadSentenceIndex: Int = 0  // Index within the page for sentence mode
)
