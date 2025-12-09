package com.example.arlo

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.arlo.data.AppDatabase
import com.example.arlo.data.BookRepository
import com.example.arlo.ml.ClaudeOCRService
import com.example.arlo.ocr.OCRQueueManager
import com.example.arlo.tts.TTSService

class ArloApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { BookRepository(database.bookDao()) }

    // TTS is initialized eagerly so it's ready when needed
    lateinit var ttsService: TTSService
        private set

    // OCR Queue manager for background processing
    val ocrQueueManager by lazy {
        OCRQueueManager(
            this,
            database.bookDao(),
            ClaudeOCRService(this),
            ApiKeyManager(this)
        )
    }

    // Speech recognition diagnostics - accessible for UI display
    var speechRecognitionDiagnostics: String = ""
        private set
    var isSpeechRecognitionAvailable: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d("ArloApplication", "Application onCreate - initializing TTS")
        // Initialize TTS early so it has time to be ready
        ttsService = TTSService(this)

        // Initialize OCR queue to process any pending pages from previous session
        ocrQueueManager.startProcessingIfNeeded()

        // Run speech recognition diagnostics
        checkSpeechRecognitionAvailability()
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsService.shutdown()
    }

    private fun checkSpeechRecognitionAvailability() {
        val diagnostics = StringBuilder()
        val tag = "SpeechRecognition"

        Log.d(tag, "========== SPEECH RECOGNITION DIAGNOSTICS ==========")

        // Check 1: Basic availability via SpeechRecognizer API
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        Log.d(tag, "SpeechRecognizer.isRecognitionAvailable(): $isAvailable")
        diagnostics.appendLine("isRecognitionAvailable: $isAvailable")

        // Check 2: Query for activities that can handle speech recognition
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val activities = packageManager.queryIntentActivities(recognizerIntent, PackageManager.MATCH_DEFAULT_ONLY)
        Log.d(tag, "Activities handling ACTION_RECOGNIZE_SPEECH: ${activities.size}")
        diagnostics.appendLine("Speech activities found: ${activities.size}")
        activities.forEach { resolveInfo ->
            val activityName = resolveInfo.activityInfo.name
            val packageName = resolveInfo.activityInfo.packageName
            Log.d(tag, "  - $packageName / $activityName")
            diagnostics.appendLine("  - $packageName")
        }

        // Check 3: Look for Google Speech Services specifically
        val googleSpeechPackages = listOf(
            "com.google.android.googlequicksearchbox",  // Google app (includes speech)
            "com.google.android.tts",                    // Google TTS (sometimes includes ASR)
            "com.google.android.voicesearch"             // Legacy voice search
        )
        Log.d(tag, "Checking for Google Speech packages:")
        diagnostics.appendLine("Google Speech packages:")
        for (pkg in googleSpeechPackages) {
            val installed = try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            Log.d(tag, "  $pkg: ${if (installed) "INSTALLED" else "NOT FOUND"}")
            diagnostics.appendLine("  $pkg: ${if (installed) "YES" else "NO"}")
        }

        // Check 4: Check for Amazon-specific speech recognition (Fire tablets)
        val amazonSpeechPackages = listOf(
            "com.amazon.dee.app",                        // Alexa app
            "com.amazon.avod",                           // Amazon Video (has voice)
            "com.amazon.venezia"                         // Amazon Appstore
        )
        Log.d(tag, "Checking for Amazon Speech packages:")
        diagnostics.appendLine("Amazon packages:")
        for (pkg in amazonSpeechPackages) {
            val installed = try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            Log.d(tag, "  $pkg: ${if (installed) "INSTALLED" else "NOT FOUND"}")
            diagnostics.appendLine("  $pkg: ${if (installed) "YES" else "NO"}")
        }

        // Check 5: Device info
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        val sdkVersion = android.os.Build.VERSION.SDK_INT
        Log.d(tag, "Device: $manufacturer $model (SDK $sdkVersion)")
        diagnostics.appendLine("Device: $manufacturer $model")
        diagnostics.appendLine("SDK: $sdkVersion")

        // Check 6: Try to create a SpeechRecognizer and see if it works
        Log.d(tag, "Attempting to create SpeechRecognizer...")
        var recognizerCreated = false
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            if (recognizer != null) {
                Log.d(tag, "SpeechRecognizer created successfully")
                diagnostics.appendLine("SpeechRecognizer creation: SUCCESS")
                recognizerCreated = true
                recognizer.destroy()
            } else {
                Log.d(tag, "SpeechRecognizer.createSpeechRecognizer returned null")
                diagnostics.appendLine("SpeechRecognizer creation: NULL")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to create SpeechRecognizer: ${e.message}")
            diagnostics.appendLine("SpeechRecognizer creation: FAILED - ${e.message}")
        }

        Log.d(tag, "========== END DIAGNOSTICS ==========")

        // Also check if we can query the activity directly (Fire OS may block package queries)
        val hasGoogleSpeechActivity = try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.setPackage("com.google.android.googlequicksearchbox")
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
        Log.d(tag, "Direct Google speech activity query: $hasGoogleSpeechActivity")
        diagnostics.appendLine("Google speech activity (direct): $hasGoogleSpeechActivity")

        // Store results - use the most permissive check for Fire tablets
        isSpeechRecognitionAvailable = isAvailable || hasGoogleSpeechActivity || recognizerCreated
        speechRecognitionDiagnostics = diagnostics.toString()

        Log.d(tag, "Final availability decision: $isSpeechRecognitionAvailable")

        // Recommendation for Fire tablets
        if (!isAvailable && manufacturer.equals("Amazon", ignoreCase = true)) {
            Log.w(tag, "RECOMMENDATION: Fire tablets need Google Speech Services.")
            Log.w(tag, "Install Google Play Store or use an alternative speech recognition library.")
        }
    }
}
