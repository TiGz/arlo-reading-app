package com.example.arlo.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages speech recognition setup and diagnostics.
 * Provides a checklist of requirements and tests them in real-time.
 */
class SpeechSetupManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechSetup"
        private const val PREFS_NAME = "speech_setup_prefs"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_SETUP_SKIPPED = "setup_skipped"

        // Google app package that provides speech recognition
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
        const val GOOGLE_SPEECH_SERVICE = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    }

    data class SetupState(
        val isGoogleAppInstalled: Boolean = false,
        val isGoogleAppHasPermission: Boolean = false,
        val isAppHasRecordPermission: Boolean = false,
        val isSpeechRecognizerAvailable: Boolean = false,
        val isSpeechTestPassed: Boolean = false,
        val isTestingInProgress: Boolean = false,
        val testError: String? = null,
        val allChecksPassed: Boolean = false
    )

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * Check if setup has been completed successfully before.
     */
    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    }

    /**
     * Check if user has chosen to skip setup.
     */
    fun isSetupSkipped(): Boolean {
        return prefs.getBoolean(KEY_SETUP_SKIPPED, false)
    }

    /**
     * Mark setup as complete (all checks passed).
     */
    fun markSetupComplete() {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
        Log.d(TAG, "Setup marked as complete")
    }

    /**
     * Mark setup as skipped by user.
     */
    fun markSetupSkipped() {
        prefs.edit().putBoolean(KEY_SETUP_SKIPPED, true).apply()
        Log.d(TAG, "Setup marked as skipped")
    }

    /**
     * Reset setup state (for testing or re-running setup).
     */
    fun resetSetup() {
        prefs.edit()
            .putBoolean(KEY_SETUP_COMPLETE, false)
            .putBoolean(KEY_SETUP_SKIPPED, false)
            .apply()
        Log.d(TAG, "Setup reset")
    }

    /**
     * Run all diagnostic checks and update state.
     */
    fun runDiagnostics() {
        Log.d(TAG, "Running diagnostics...")

        val isGoogleInstalled = checkGoogleAppInstalled()
        val isGoogleHasPermission = checkGoogleAppHasRecordPermission()
        val isAppHasPermission = checkAppHasRecordPermission()
        val isSpeechAvailable = SpeechRecognizer.isRecognitionAvailable(context)

        val basicChecksPassed = isGoogleInstalled && isGoogleHasPermission &&
                                isAppHasPermission && isSpeechAvailable

        _state.value = _state.value.copy(
            isGoogleAppInstalled = isGoogleInstalled,
            isGoogleAppHasPermission = isGoogleHasPermission,
            isAppHasRecordPermission = isAppHasPermission,
            isSpeechRecognizerAvailable = isSpeechAvailable,
            // allChecksPassed requires the test to pass too
            allChecksPassed = basicChecksPassed && _state.value.isSpeechTestPassed
        )

        Log.d(TAG, "Diagnostics complete: ${_state.value}")
    }

    /**
     * Run a live speech recognition test.
     * This actually tries to start listening to verify everything works.
     */
    fun runSpeechTest(onComplete: (success: Boolean, error: String?) -> Unit) {
        Log.d(TAG, "Starting speech test...")

        _state.value = _state.value.copy(
            isTestingInProgress = true,
            isSpeechTestPassed = false,
            testError = null
        )

        try {
            // Create recognizer with explicit Google component
            val googleComponent = ComponentName(GOOGLE_APP_PACKAGE, GOOGLE_SPEECH_SERVICE)
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, googleComponent)

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Speech test: onReadyForSpeech - SUCCESS!")
                    // If we get here, speech recognition is working
                    _state.value = _state.value.copy(
                        isTestingInProgress = false,
                        isSpeechTestPassed = true,
                        testError = null,
                        allChecksPassed = true
                    )
                    // Stop listening - we just needed to verify it works
                    speechRecognizer?.stopListening()
                    onComplete(true, null)
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing permissions - grant RECORD_AUDIO to Google app"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected (this is OK)"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout (this is OK)"
                        else -> "Unknown error ($error)"
                    }
                    Log.e(TAG, "Speech test error: $errorMsg")

                    // ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT are actually success cases
                    // They mean the recognizer started but no speech was detected
                    val isActuallySuccess = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                           error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                    _state.value = _state.value.copy(
                        isTestingInProgress = false,
                        isSpeechTestPassed = isActuallySuccess,
                        testError = if (isActuallySuccess) null else errorMsg,
                        allChecksPassed = isActuallySuccess && _state.value.isGoogleAppInstalled &&
                                         _state.value.isGoogleAppHasPermission &&
                                         _state.value.isAppHasRecordPermission
                    )
                    onComplete(isActuallySuccess, if (isActuallySuccess) null else errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    Log.d(TAG, "Speech test: onResults")
                    _state.value = _state.value.copy(
                        isTestingInProgress = false,
                        isSpeechTestPassed = true,
                        testError = null,
                        allChecksPassed = true
                    )
                    onComplete(true, null)
                }

                // Other callbacks - not critical for setup test
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech test: onBeginningOfSpeech")
                }
                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech test: onEndOfSpeech")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // Start listening
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Short timeout for test
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Speech test exception: ${e.message}", e)
            _state.value = _state.value.copy(
                isTestingInProgress = false,
                isSpeechTestPassed = false,
                testError = e.message ?: "Unknown error"
            )
            onComplete(false, e.message)
        }
    }

    /**
     * Cancel any ongoing speech test.
     */
    fun cancelTest() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _state.value = _state.value.copy(isTestingInProgress = false)
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        cancelTest()
    }

    private fun checkGoogleAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(GOOGLE_APP_PACKAGE, 0)
            Log.d(TAG, "Google app is installed")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Google app is NOT installed")
            false
        }
    }

    private fun checkGoogleAppHasRecordPermission(): Boolean {
        // We can't directly check another app's runtime permissions,
        // but we can infer from the speech test result.
        // For now, assume true if Google app is installed.
        // The actual test will reveal if permission is missing.
        return checkGoogleAppInstalled()
    }

    private fun checkAppHasRecordPermission(): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "App has RECORD_AUDIO permission: $hasPermission")
        return hasPermission
    }

    /**
     * Get instructions for setting up speech recognition on Fire tablets.
     */
    fun getSetupInstructions(): List<SetupInstruction> {
        return listOf(
            SetupInstruction(
                id = "google_app",
                title = "Install Google App",
                description = "The Google app provides speech recognition on Fire tablets.",
                helpUrl = "https://www.apkmirror.com/apk/google-inc/google-search/",
                checkState = { _state.value.isGoogleAppInstalled }
            ),
            SetupInstruction(
                id = "google_permission",
                title = "Grant Microphone to Google App",
                description = "Open Settings > Apps > Google > Permissions and enable Microphone.",
                helpUrl = null,
                adbCommand = "adb shell pm grant com.google.android.googlequicksearchbox android.permission.RECORD_AUDIO",
                checkState = { _state.value.isGoogleAppHasPermission && _state.value.isSpeechTestPassed }
            ),
            SetupInstruction(
                id = "app_permission",
                title = "Grant Microphone to Arlo",
                description = "Allow Arlo to access the microphone when prompted.",
                helpUrl = null,
                checkState = { _state.value.isAppHasRecordPermission }
            ),
            SetupInstruction(
                id = "speech_test",
                title = "Speech Recognition Test",
                description = "Verify that speech recognition is working.",
                helpUrl = null,
                checkState = { _state.value.isSpeechTestPassed }
            )
        )
    }

    data class SetupInstruction(
        val id: String,
        val title: String,
        val description: String,
        val helpUrl: String? = null,
        val adbCommand: String? = null,
        val checkState: () -> Boolean
    )
}
