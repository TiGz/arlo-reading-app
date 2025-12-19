package com.example.arlo.ui

/**
 * Animation states for individual words in the sentence display.
 * Each state maps to a specific visual treatment and animation behavior.
 */
enum class WordHighlightState {
    /** Normal state - no special highlighting or animation */
    IDLE,

    /** TTS is currently speaking this word - grows then shrinks */
    TTS_SPEAKING,

    /** This is the target word the user needs to read - gentle pulse */
    USER_TURN,

    /** Actively listening for user to speak this word - pulse with mic indicator */
    LISTENING,

    /** User spoke the word correctly - celebratory bounce */
    SUCCESS,

    /** User spoke incorrectly - error shake */
    ERROR
}
