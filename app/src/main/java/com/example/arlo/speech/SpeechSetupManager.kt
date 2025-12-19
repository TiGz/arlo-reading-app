package com.example.arlo.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

        // Google components needed for Play Store (install in this order)
        const val GOOGLE_ACCOUNT_MANAGER_PACKAGE = "com.google.android.gsf.login"
        const val GOOGLE_SERVICES_FRAMEWORK_PACKAGE = "com.google.android.gsf"
        const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
        const val PLAY_STORE_PACKAGE = "com.android.vending"

        // Google app package that provides speech recognition
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
        const val GOOGLE_SPEECH_SERVICE = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"

        // APKMirror download pages for each component
        const val URL_ACCOUNT_MANAGER = "https://www.apkmirror.com/apk/google-inc/google-account-manager/google-account-manager-7-1-2-release/"
        // Services Framework varies by Fire OS version
        const val URL_SERVICES_FRAMEWORK_V9 = "https://www.apkmirror.com/apk/google-inc/google-services-framework/google-services-framework-9-6957767-release/"
        const val URL_SERVICES_FRAMEWORK_V10 = "https://www.apkmirror.com/apk/google-inc/google-services-framework/google-services-framework-10-6494331-release/"
        const val URL_PLAY_SERVICES = "https://www.apkmirror.com/apk/google-inc/google-play-services/"
        const val URL_PLAY_STORE = "https://www.apkmirror.com/apk/google-inc/google-play-store/"
        const val URL_SETUP_GUIDE = "https://www.androidpolice.com/install-play-store-amazon-fire-tablet/"
    }

    /**
     * Device info for determining correct APK versions.
     */
    data class DeviceInfo(
        val model: String,
        val androidVersion: Int,
        val cpuAbi: String,
        val isFireOS8: Boolean,
        val is64Bit: Boolean
    ) {
        companion object {
            fun detect(): DeviceInfo {
                val model = Build.MODEL ?: "Unknown"
                val androidVersion = Build.VERSION.SDK_INT
                val cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                val is64Bit = cpuAbi.contains("64")
                // Fire OS 8 is based on Android 11 (SDK 30)
                val isFireOS8 = androidVersion >= 30

                return DeviceInfo(
                    model = model,
                    androidVersion = androidVersion,
                    cpuAbi = cpuAbi,
                    isFireOS8 = isFireOS8,
                    is64Bit = is64Bit
                )
            }
        }

        /** Get the correct Services Framework URL based on Fire OS version */
        fun getServicesFrameworkUrl(): String {
            return if (isFireOS8) URL_SERVICES_FRAMEWORK_V10 else URL_SERVICES_FRAMEWORK_V9
        }

        /** Get human-readable description of what APK to look for */
        fun getPlayServicesHint(): String {
            // Fire tablets need the dual-architecture build, NOT universal
            val androidMin = if (isFireOS8) "Android 10+ or 11+" else "Android 9+"
            return "Choose: arm64-v8a + armeabi-v7a, nodpi, $androidMin (NOT universal!)"
        }

        fun getPlayStoreHint(): String {
            val androidMin = if (isFireOS8) "Android 10+ or 11+" else "Android 9+"
            return "Choose: arm64-v8a + armeabi-v7a, nodpi, $androidMin (APK not bundle!)"
        }

        fun getFireOSVersion(): String {
            return if (isFireOS8) "Fire OS 8 (Android 11)" else "Fire OS 7 (Android 9)"
        }
    }

    data class SetupState(
        // Play Store components (must be installed in order)
        val isAccountManagerInstalled: Boolean = false,
        val isServicesFrameworkInstalled: Boolean = false,
        val isPlayServicesInstalled: Boolean = false,
        val isPlayStoreInstalled: Boolean = false,
        // Google app for speech recognition
        val isGoogleAppInstalled: Boolean = false,
        val isGoogleAppHasPermission: Boolean = false,
        val isAppHasRecordPermission: Boolean = false,
        val isSpeechRecognizerAvailable: Boolean = false,
        val isSpeechTestPassed: Boolean = false,
        val isTestingInProgress: Boolean = false,
        val testError: String? = null,
        val allChecksPassed: Boolean = false
    ) {
        /** Returns the next Play Store component that needs to be installed, or null if all are installed */
        fun getNextPlayStoreComponent(): PlayStoreComponent? {
            return when {
                !isAccountManagerInstalled -> PlayStoreComponent.ACCOUNT_MANAGER
                !isServicesFrameworkInstalled -> PlayStoreComponent.SERVICES_FRAMEWORK
                !isPlayServicesInstalled -> PlayStoreComponent.PLAY_SERVICES
                !isPlayStoreInstalled -> PlayStoreComponent.PLAY_STORE
                else -> null
            }
        }

        /** Returns count of Play Store components installed */
        fun playStoreComponentsInstalled(): Int {
            var count = 0
            if (isAccountManagerInstalled) count++
            if (isServicesFrameworkInstalled) count++
            if (isPlayServicesInstalled) count++
            if (isPlayStoreInstalled) count++
            return count
        }
    }

    enum class PlayStoreComponent(
        val displayName: String,
        val packageName: String,
        val installOrder: Int
    ) {
        ACCOUNT_MANAGER(
            "Google Account Manager",
            GOOGLE_ACCOUNT_MANAGER_PACKAGE,
            1
        ),
        SERVICES_FRAMEWORK(
            "Google Services Framework",
            GOOGLE_SERVICES_FRAMEWORK_PACKAGE,
            2
        ),
        PLAY_SERVICES(
            "Google Play Services",
            GOOGLE_PLAY_SERVICES_PACKAGE,
            3
        ),
        PLAY_STORE(
            "Google Play Store",
            PLAY_STORE_PACKAGE,
            4
        )
    }

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var speechRecognizer: SpeechRecognizer? = null

    /** Device info for APK recommendations */
    val deviceInfo: DeviceInfo = DeviceInfo.detect()

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

        // Check Play Store components
        val isAccountManagerInstalled = checkPackageInstalled(GOOGLE_ACCOUNT_MANAGER_PACKAGE)
        val isServicesFrameworkInstalled = checkPackageInstalled(GOOGLE_SERVICES_FRAMEWORK_PACKAGE)
        val isPlayServicesInstalled = checkPackageInstalled(GOOGLE_PLAY_SERVICES_PACKAGE)
        val isPlayStoreInstalled = checkPackageInstalled(PLAY_STORE_PACKAGE)

        // Check Google app and permissions
        val isGoogleInstalled = checkGoogleAppInstalled()
        val isGoogleHasPermission = checkGoogleAppHasRecordPermission()
        val isAppHasPermission = checkAppHasRecordPermission()
        val isSpeechAvailable = SpeechRecognizer.isRecognitionAvailable(context)

        val basicChecksPassed = isGoogleInstalled && isGoogleHasPermission &&
                                isAppHasPermission && isSpeechAvailable

        _state.value = _state.value.copy(
            isAccountManagerInstalled = isAccountManagerInstalled,
            isServicesFrameworkInstalled = isServicesFrameworkInstalled,
            isPlayServicesInstalled = isPlayServicesInstalled,
            isPlayStoreInstalled = isPlayStoreInstalled,
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

    private fun checkPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            Log.d(TAG, "$packageName is installed")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "$packageName is NOT installed")
            false
        }
    }

    private fun checkGoogleAppInstalled(): Boolean {
        return checkPackageInstalled(GOOGLE_APP_PACKAGE)
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
     * Shows each Play Store component separately so users know exactly what's installed.
     * Uses device info to provide accurate APK recommendations.
     */
    fun getSetupInstructions(): List<SetupInstruction> {
        return listOf(
            // Pre-requisite: Enable sideloading
            SetupInstruction(
                id = "enable_sideload",
                title = "0. Enable APK Installation",
                description = "Settings → Security → Install unknown apps → Silk Browser → Allow",
                helpUrl = "https://www.howtogeek.com/178357/how-to-sideload-apps-onto-your-kindle-fire/",
                checkState = { true } // Can't detect this, always show as info
            ),
            // Play Store components (must be installed in order)
            SetupInstruction(
                id = "account_manager",
                title = "1. Google Account Manager",
                description = "Tap Download, then choose version 7.1.2",
                helpUrl = URL_ACCOUNT_MANAGER,
                checkState = { _state.value.isAccountManagerInstalled }
            ),
            SetupInstruction(
                id = "services_framework",
                title = "2. Google Services Framework",
                description = if (deviceInfo.isFireOS8) "Tap Download, choose version 10" else "Tap Download, choose version 9",
                helpUrl = deviceInfo.getServicesFrameworkUrl(),
                checkState = { _state.value.isServicesFrameworkInstalled }
            ),
            SetupInstruction(
                id = "play_services",
                title = "3. Google Play Services",
                description = deviceInfo.getPlayServicesHint(),
                helpUrl = URL_PLAY_SERVICES,
                checkState = { _state.value.isPlayServicesInstalled }
            ),
            SetupInstruction(
                id = "play_store",
                title = "4. Google Play Store",
                description = deviceInfo.getPlayStoreHint(),
                helpUrl = URL_PLAY_STORE,
                checkState = { _state.value.isPlayStoreInstalled }
            ),
            // Google app for speech recognition
            SetupInstruction(
                id = "google_app",
                title = "5. Install Google App",
                description = "Open Play Store and install the Google app.",
                helpUrl = null,
                checkState = { _state.value.isGoogleAppInstalled }
            ),
            SetupInstruction(
                id = "google_permission",
                title = "6. Grant Microphone to Google",
                description = "Settings > Apps > Google > Permissions > Microphone",
                helpUrl = null,
                adbCommand = "adb shell pm grant com.google.android.googlequicksearchbox android.permission.RECORD_AUDIO",
                checkState = { _state.value.isGoogleAppHasPermission && _state.value.isSpeechTestPassed }
            ),
            SetupInstruction(
                id = "app_permission",
                title = "7. Grant Microphone to Arlo",
                description = "Allow Arlo to access the microphone when prompted.",
                helpUrl = null,
                checkState = { _state.value.isAppHasRecordPermission }
            ),
            SetupInstruction(
                id = "speech_test",
                title = "8. Speech Recognition Test",
                description = "Tap 'Run Test' below to verify speech recognition works.",
                helpUrl = null,
                checkState = { _state.value.isSpeechTestPassed }
            )
        )
    }

    /**
     * Get a summary of Play Store installation progress.
     */
    fun getPlayStoreProgress(): String {
        val installed = _state.value.playStoreComponentsInstalled()
        return when {
            installed == 0 -> "Device: ${deviceInfo.model} • ${deviceInfo.getFireOSVersion()}"
            installed < 4 -> "$installed of 4 components installed"
            else -> "All Play Store components installed!"
        }
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
