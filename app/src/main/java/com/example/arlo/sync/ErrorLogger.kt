package com.example.arlo.sync

import android.util.Log

/**
 * Centralized error logging utility.
 * Logs to both Android logcat and queues for cloud sync.
 */
object ErrorLogger {
    private var syncManager: CloudSyncManager? = null
    private var currentScreen: String? = null

    /**
     * Initialize with CloudSyncManager reference.
     * Call from ArloApplication.onCreate()
     */
    fun init(manager: CloudSyncManager) {
        syncManager = manager
    }

    /**
     * Set the current screen name for error context.
     * Call from Fragment.onResume() or Activity lifecycle.
     */
    fun setCurrentScreen(screen: String) {
        currentScreen = screen
    }

    /**
     * Log an OCR error.
     */
    fun logOCRError(
        message: String,
        exception: Throwable? = null,
        context: Map<String, String>? = null
    ) {
        val fullMessage = if (exception != null) "$message: ${exception.message}" else message
        Log.e("OCR", fullMessage, exception)

        syncManager?.logError(
            type = "ocr_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = context,
            screen = currentScreen
        )
    }

    /**
     * Log a TTS error.
     */
    fun logTTSError(
        message: String,
        exception: Throwable? = null,
        context: Map<String, String>? = null
    ) {
        val fullMessage = if (exception != null) "$message: ${exception.message}" else message
        Log.e("TTS", fullMessage, exception)

        syncManager?.logError(
            type = "tts_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = context,
            screen = currentScreen
        )
    }

    /**
     * Log an API error (Claude, Kokoro, etc.).
     */
    fun logAPIError(
        service: String,
        message: String,
        statusCode: Int? = null,
        exception: Throwable? = null
    ) {
        val fullMessage = if (statusCode != null) "[$statusCode] $message" else message
        Log.e("API:$service", fullMessage, exception)

        val errorContext = mutableMapOf("service" to service)
        statusCode?.let { errorContext["status_code"] = it.toString() }

        syncManager?.logError(
            type = "api_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = errorContext,
            screen = currentScreen
        )
    }

    /**
     * Log a speech recognition error.
     */
    fun logSpeechError(
        message: String,
        errorCode: Int? = null,
        exception: Throwable? = null
    ) {
        val fullMessage = if (errorCode != null) "[Code $errorCode] $message" else message
        Log.e("Speech", fullMessage, exception)

        val errorContext = mutableMapOf<String, String>()
        errorCode?.let { errorContext["error_code"] = it.toString() }

        syncManager?.logError(
            type = "speech_error",
            message = fullMessage,
            stackTrace = exception?.stackTraceToString(),
            errorContext = errorContext,
            screen = currentScreen
        )
    }

    /**
     * Log an unexpected error.
     */
    fun logUnexpectedError(
        tag: String,
        message: String,
        exception: Throwable? = null,
        context: Map<String, String>? = null
    ) {
        val fullMessage = if (exception != null) "$message: ${exception.message}" else message
        Log.e(tag, fullMessage, exception)

        syncManager?.logError(
            type = "unexpected",
            message = "[$tag] $fullMessage",
            stackTrace = exception?.stackTraceToString(),
            errorContext = context,
            screen = currentScreen
        )
    }

    /**
     * Log a fatal crash.
     */
    fun logCrash(exception: Throwable) {
        Log.e("CRASH", "Fatal crash", exception)

        syncManager?.logError(
            type = "crash",
            severity = "fatal",
            message = exception.message ?: "Unknown crash",
            stackTrace = exception.stackTraceToString(),
            screen = currentScreen
        )
    }
}
