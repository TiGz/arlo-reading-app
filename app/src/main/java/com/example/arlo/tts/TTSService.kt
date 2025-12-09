package com.example.arlo.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TTSService(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onRangeStart: ((Int, Int) -> Unit)? = null
    private var onSpeechDone: (() -> Unit)? = null
    private var pendingText: String? = null
    private var currentEngineIndex = 0
    private var initializationAttempts = 0
    private var currentVoiceId: String? = null
    private var speechRate: Float = 1.0f
    private var initializedEngineName: String = "Unknown"

    // Map utterance IDs to their completion callbacks
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    // TTS engines to try, in order of preference
    private val ttsEngines = listOf(
        "com.google.android.tts",      // Google TTS (best quality)
        "com.svox.pico",               // SVOX Pico (reliable fallback)
        null                           // System default (last resort)
    )

    init {
        Log.d(TAG, "TTSService init - starting engine initialization")
        tryNextEngine()
    }

    private fun tryNextEngine() {
        if (currentEngineIndex >= ttsEngines.size) {
            Log.e(TAG, "All TTS engines failed to initialize!")
            isInitialized = false
            return
        }

        val enginePackage = ttsEngines[currentEngineIndex]
        Log.d(TAG, "Trying TTS engine ${currentEngineIndex + 1}/${ttsEngines.size}: ${enginePackage ?: "system default"}")

        // Clean up previous attempt
        tts?.shutdown()

        tts = if (enginePackage != null) {
            TextToSpeech(context.applicationContext, this, enginePackage)
        } else {
            TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        val enginePackage = ttsEngines.getOrNull(currentEngineIndex) ?: "system default"
        Log.d(TAG, "onInit called for engine '$enginePackage' with status: $status")

        if (status == TextToSpeech.SUCCESS) {
            // Log available engines and voices for debugging
            tts?.engines?.forEach { engine ->
                Log.d(TAG, "Available engine: ${engine.name} (${engine.label})")
            }

            // Try to set language
            var result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "US English not supported, trying UK English")
                result = tts?.setLanguage(Locale.UK)
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "UK English not supported, trying default locale")
                result = tts?.setLanguage(Locale.getDefault())
            }

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "No supported language found for engine '$enginePackage', trying next engine")
                currentEngineIndex++
                tryNextEngine()
            } else {
                Log.d(TAG, "TTS initialized successfully with engine: $enginePackage")
                isInitialized = true
                setupUtteranceProgressListener()

                // Store the engine name for display
                initializedEngineName = getEngineFriendlyName(enginePackage)
                Log.d(TAG, "Engine friendly name: $initializedEngineName")

                // Apply saved speech rate
                tts?.setSpeechRate(speechRate)

                // If there was pending text, speak it now
                pendingText?.let { text ->
                    pendingText = null
                    speak(text)
                }
            }
        } else {
            Log.w(TAG, "TTS engine '$enginePackage' failed with status: $status, trying next engine")
            currentEngineIndex++
            tryNextEngine()
        }
    }

    private fun setupUtteranceProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Speech started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "Speech done: $utteranceId")
                // First check for utterance-specific callback
                utteranceId?.let { id ->
                    utteranceCallbacks.remove(id)?.invoke()
                } ?: run {
                    // Fallback to global listener
                    onSpeechDone?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Speech error: $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                super.onError(utteranceId, errorCode)
                Log.e(TAG, "Speech error: $utteranceId, code: $errorCode")
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                Log.d(TAG, "onRangeStart: start=$start, end=$end, frame=$frame")
                onRangeStart?.invoke(start, end)
            }
        })
    }

    fun setOnRangeStartListener(listener: (Int, Int) -> Unit) {
        onRangeStart = listener
    }

    fun setOnSpeechDoneListener(listener: () -> Unit) {
        onSpeechDone = listener
    }

    fun speak(text: String) {
        if (isInitialized && tts != null) {
            val utteranceId = UUID.randomUUID().toString()
            Log.d(TAG, "Speaking text (${text.take(50)}...), utteranceId: $utteranceId")
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            Log.d(TAG, "speak() returned: $result")
        } else {
            Log.w(TAG, "TTS not initialized yet, queuing text")
            pendingText = text
        }
    }

    /**
     * Speak text with a one-time completion callback.
     * Uses utterance-based callbacks to properly support recursive/chained calls.
     */
    fun speak(text: String, onComplete: () -> Unit) {
        if (isInitialized && tts != null) {
            val utteranceId = UUID.randomUUID().toString()
            Log.d(TAG, "Speaking with callback (${text.take(50)}...), utteranceId: $utteranceId")
            utteranceCallbacks[utteranceId] = onComplete
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            Log.d(TAG, "speak() with callback returned: $result")
        } else {
            Log.w(TAG, "TTS not initialized yet for callback speak")
            pendingText = text
        }
    }

    /**
     * Speak text at full speed for voice preview purposes.
     * Temporarily sets speech rate to 1.0, speaks, then restores original rate.
     */
    fun speakPreview(text: String) {
        if (isInitialized && tts != null) {
            val originalRate = speechRate
            tts?.setSpeechRate(1.0f)
            val utteranceId = UUID.randomUUID().toString()
            Log.d(TAG, "Speaking preview at full speed: $text")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            // Restore rate after a delay (speech is async)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tts?.setSpeechRate(originalRate)
            }, 2000)
        }
    }

    fun stop() {
        if (isInitialized && tts != null) {
            tts?.stop()
            // Clear pending callbacks since we're stopping
            utteranceCallbacks.clear()
        }
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun isReady(): Boolean {
        return isInitialized
    }

    /**
     * Get available voices - UK English only with friendly names.
     */
    fun getAvailableVoices(): List<VoiceInfo> {
        if (!isInitialized || tts == null) return emptyList()

        val engineName = getCurrentEngineName()

        return try {
            tts?.voices
                ?.filter { voice ->
                    // Filter to UK English voices only
                    voice.locale.language == "en" &&
                    voice.locale.country == "GB" &&
                    !voice.isNetworkConnectionRequired
                }
                ?.map { voice ->
                    VoiceInfo(
                        id = voice.name,
                        name = formatVoiceName(voice.name, engineName),
                        locale = voice.locale.displayName,
                        quality = voice.quality
                    )
                }
                ?.sortedByDescending { it.quality }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting voices", e)
            emptyList()
        }
    }

    /**
     * Get a friendly name for the current TTS engine.
     */
    private fun getCurrentEngineName(): String {
        return initializedEngineName
    }

    /**
     * Convert engine package name to friendly display name.
     */
    private fun getEngineFriendlyName(enginePackage: String): String {
        return when {
            enginePackage.contains("google") -> "Google"
            enginePackage.contains("amazon") || enginePackage.contains("ivona") -> "Amazon"
            enginePackage.contains("samsung") -> "Samsung"
            enginePackage.contains("pico") || enginePackage.contains("svox") -> "Pico"
            enginePackage.contains("acapela") -> "Acapela"
            enginePackage.contains("cereproc") -> "CereProc"
            enginePackage == "system default" -> "System"
            else -> enginePackage.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Set the voice by ID.
     */
    fun setVoice(voiceId: String): Boolean {
        if (!isInitialized || tts == null) return false

        return try {
            val voice = tts?.voices?.find { it.name == voiceId }
            if (voice != null) {
                tts?.voice = voice
                currentVoiceId = voiceId
                Log.d(TAG, "Voice set to: ${voice.name}")
                true
            } else {
                Log.w(TAG, "Voice not found: $voiceId")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting voice", e)
            false
        }
    }

    /**
     * Get the current voice ID.
     */
    fun getCurrentVoiceId(): String? {
        return currentVoiceId ?: tts?.voice?.name
    }

    /**
     * Set the speech rate (0.25 to 1.0).
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.25f, 1.0f)
        tts?.setSpeechRate(speechRate)
        Log.d(TAG, "Speech rate set to: $speechRate")
    }

    /**
     * Get the current speech rate.
     */
    fun getSpeechRate(): Float = speechRate

    private fun formatVoiceName(rawName: String, engineName: String): String {
        // Convert names like "en-gb-x-rjs#female_1-local" to friendly names
        val lowerName = rawName.lowercase()

        // Determine quality prefix
        val quality = when {
            lowerName.contains("wavenet") -> "Premium "
            lowerName.contains("network") -> "HD "
            else -> ""
        }

        // Extract voice variant for uniqueness (e.g., "rjs", "gba", etc.)
        val variantMatch = Regex("en-gb-x-([a-z]+)").find(lowerName)
        val variant = variantMatch?.groupValues?.get(1)?.uppercase() ?: ""

        val voiceType = when {
            lowerName.contains("female") -> {
                if (variant.isNotEmpty()) "${quality}British Female ($variant)"
                else "${quality}British Female"
            }
            lowerName.contains("male") -> {
                if (variant.isNotEmpty()) "${quality}British Male ($variant)"
                else "${quality}British Male"
            }
            else -> {
                // For voices without gender markers, create a friendly name
                if (variant.isNotEmpty()) "British Voice ($variant)"
                else "British Voice"
            }
        }

        return "$voiceType - $engineName"
    }

    data class VoiceInfo(
        val id: String,
        val name: String,
        val locale: String,
        val quality: Int
    )

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "TTSService"
    }
}
