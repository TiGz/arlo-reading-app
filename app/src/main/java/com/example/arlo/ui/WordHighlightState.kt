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

    /** User spoke correctly on 1st try (no TTS help) - gold celebration */
    SUCCESS_GOLD,

    /** User spoke correctly on 2nd/3rd try (no TTS help) - silver celebration */
    SUCCESS_SILVER,

    /** User spoke correctly after TTS help - bronze celebration */
    SUCCESS_BRONZE,

    /** User spoke incorrectly - error shake */
    ERROR
}
