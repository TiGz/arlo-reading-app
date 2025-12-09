package com.example.arlo.data

/**
 * Data class representing a sentence extracted from OCR.
 * Not a Room entity - stored as JSON in Page.sentencesJson
 */
data class SentenceData(
    val text: String,
    val isComplete: Boolean = true
)
