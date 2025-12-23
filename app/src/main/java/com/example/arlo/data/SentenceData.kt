package com.example.arlo.data

/**
 * Style types for sentence display and behavior.
 * Affects both visual presentation and collaborative reading eligibility.
 */
enum class TextStyle {
    NORMAL,    // Regular body text - full collaborative reading
    HEADING,   // Chapter headings, section titles - bold, skip collaborative
    SCENE      // Scene-setting text (locations, timestamps) - styled, skip collaborative
}

/**
 * Data class representing a sentence extracted from OCR.
 * Not a Room entity - stored as JSON in Page.sentencesJson
 */
data class SentenceData(
    val text: String,
    val isComplete: Boolean = true,
    val style: TextStyle = TextStyle.NORMAL
)
