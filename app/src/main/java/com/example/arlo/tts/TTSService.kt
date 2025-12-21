package com.example.arlo.tts

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.arlo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    // Kokoro TTS configuration - parse URL and extract credentials if present
    private val kokoroServerUrl: String?
    private val kokoroHttpClient: OkHttpClient

    init {
        val rawUrl = BuildConfig.KOKORO_SERVER_URL.ifEmpty { null }
        if (rawUrl != null) {
            val uri = java.net.URI(rawUrl)
            val userInfo = uri.userInfo
            if (userInfo != null && userInfo.contains(":")) {
                // Extract credentials and build clean URL
                val (username, password) = userInfo.split(":", limit = 2)
                kokoroServerUrl = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${uri.path}"
                kokoroHttpClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Authorization", okhttp3.Credentials.basic(username, password))
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            } else {
                // No credentials in URL
                kokoroServerUrl = rawUrl
                kokoroHttpClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            }
        } else {
            kokoroServerUrl = null
            kokoroHttpClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        // Start TTS engine initialization
        Log.d(TAG, "TTSService init - starting engine initialization")
        tryNextEngine()
    }

    private var exoPlayer: ExoPlayer? = null
    private var kokoroTempFile: File? = null
    private val highlightHandler = Handler(Looper.getMainLooper())
    private var kokoroVoice: String = "bf_emma"  // British female
    @Volatile private var isStopped: Boolean = false  // Flag to prevent playback after stop

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

    // Callback for when Kokoro fails and we fall back to Android TTS
    private var onKokoroFallback: (() -> Unit)? = null

    /**
     * Set a callback that fires when Kokoro is unavailable and we fall back to Android TTS.
     * Use this to show a toast notification to the user.
     */
    fun setOnKokoroFallbackListener(listener: (() -> Unit)?) {
        onKokoroFallback = listener
    }

    /**
     * Speak with Kokoro TTS (preferred) or Android TTS (fallback).
     * Kokoro provides high-quality voices with word-level timestamps for highlighting.
     * If cacheManager is provided, checks cache before making network request.
     */
    suspend fun speakWithKokoro(
        text: String,
        cacheManager: TTSCacheManager? = null,
        onComplete: () -> Unit
    ) {
        isStopped = false  // Clear stop flag when starting new speech

        // Try cached audio first (only for Kokoro voices)
        val cached = cacheManager?.getCachedAudio(text)
        if (cached != null) {
            Log.d(TAG, "Playing from TTS cache: ${text.take(50)}...")
            currentPlaybackText = text
            val timestamps = parseTimestampsFromJson(cached.timestampsJson)
            playWithTimestamps(cached.audioBytes, timestamps, onComplete)
            return
        }

        // Try Kokoro synthesis if configured
        if (kokoroServerUrl != null) {
            try {
                Log.d(TAG, "Attempting Kokoro TTS for: ${text.take(50)}...")
                currentPlaybackText = text  // Store for word position lookup
                val result = synthesizeKokoro(text)

                // Check if stopped while synthesizing (network request takes time)
                if (isStopped) {
                    Log.d(TAG, "Playback cancelled - stopped during synthesis")
                    return
                }

                // Save to cache for next time (opportunistic caching)
                cacheManager?.saveToCache(text, result.audioBytes, result.timestampsJson)

                playWithTimestamps(result.audioBytes, result.timestamps, onComplete)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Kokoro failed: ${e.message}, falling back to Android TTS")
                currentPlaybackText = null
                // Notify listener about fallback
                onKokoroFallback?.invoke()
            }
        }

        // Fallback to existing Android TTS
        speak(text, onComplete)
    }

    /**
     * Speak with Kokoro, stopping at a specific timestamp.
     * Used for collaborative mode to read all but the target word(s).
     * Falls back to speaking the partial text if cache misses.
     */
    suspend fun speakWithKokoroUntil(
        fullText: String,
        cacheManager: TTSCacheManager?,
        stopAtMs: Long,
        onComplete: () -> Unit
    ) {
        isStopped = false

        // Try cached audio first
        val cached = cacheManager?.getCachedAudio(fullText)
        if (cached != null) {
            Log.d(TAG, "Playing from cache until ${stopAtMs}ms: ${fullText.take(50)}...")
            currentPlaybackText = fullText
            val timestamps = parseTimestampsFromJson(cached.timestampsJson)
            playWithTimestamps(cached.audioBytes, timestamps, onComplete, startMs = 0, endMs = stopAtMs)
            return
        }

        // Cache miss - fetch full sentence (which caches it), then play partial
        if (kokoroServerUrl != null) {
            try {
                Log.d(TAG, "Fetching full sentence for partial play: ${fullText.take(50)}...")
                currentPlaybackText = fullText
                val result = synthesizeKokoro(fullText)

                if (isStopped) {
                    Log.d(TAG, "Playback cancelled - stopped during synthesis")
                    return
                }

                // Save full sentence to cache
                cacheManager?.saveToCache(fullText, result.audioBytes, result.timestampsJson)

                // Play only up to stopAtMs
                playWithTimestamps(result.audioBytes, result.timestamps, onComplete, startMs = 0, endMs = stopAtMs)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Kokoro failed: ${e.message}, falling back to Android TTS")
                currentPlaybackText = null
                onKokoroFallback?.invoke()
            }
        }

        // Fallback: extract partial text and speak with Android TTS
        // This won't be as clean but it's better than nothing
        speak(fullText, onComplete)
    }

    /**
     * Play cached audio starting from a specific timestamp.
     * Used to read just the target word(s) after failed speech recognition attempts.
     * Returns false if not cached (caller should fall back to speaking the word).
     */
    suspend fun speakCachedFrom(
        fullText: String,
        cacheManager: TTSCacheManager,
        startFromMs: Long,
        onComplete: () -> Unit
    ): Boolean {
        isStopped = false

        val cached = cacheManager.getCachedAudio(fullText)
        if (cached == null) {
            Log.d(TAG, "Cache miss for speakCachedFrom: ${fullText.take(50)}...")
            return false
        }

        Log.d(TAG, "Playing from cache starting at ${startFromMs}ms: ${fullText.take(50)}...")
        currentPlaybackText = fullText
        val timestamps = parseTimestampsFromJson(cached.timestampsJson)
        playWithTimestamps(cached.audioBytes, timestamps, onComplete, startMs = startFromMs, endMs = null)
        return true
    }

    /**
     * Parse timestamps from JSON string (used for cached audio).
     */
    private fun parseTimestampsFromJson(json: String): List<WordTimestamp> {
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                WordTimestamp(
                    word = obj.getString("word"),
                    startMs = (obj.getDouble("start_time") * 1000).toLong(),
                    endMs = (obj.getDouble("end_time") * 1000).toLong()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse timestamps JSON: ${e.message}")
            emptyList()
        }
    }

    /**
     * Preprocess text for Kokoro TTS.
     * Kokoro has a bug where hyphens surrounded by spaces (" - ") cause timestamp truncation.
     * Replace with commas for cleaner synthesis and complete timestamps.
     */
    private fun preprocessForKokoro(text: String): String {
        return text.replace(" - ", ", ")
    }

    private suspend fun synthesizeKokoro(text: String): KokoroResult = withContext(Dispatchers.IO) {
        val processedText = preprocessForKokoro(text)
        val json = JSONObject().apply {
            put("input", processedText)
            put("voice", kokoroVoice)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$kokoroServerUrl/dev/captioned_speech")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = kokoroHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Kokoro error: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        val responseJson = JSONObject(responseBody)
        val audioBase64 = responseJson.getString("audio")
        val timestampsArray = responseJson.getJSONArray("timestamps")

        KokoroResult(
            audioBytes = Base64.decode(audioBase64, Base64.DEFAULT),
            timestamps = (0 until timestampsArray.length()).map { i ->
                val obj = timestampsArray.getJSONObject(i)
                WordTimestamp(
                    word = obj.getString("word"),
                    startMs = (obj.getDouble("start_time") * 1000).toLong(),
                    endMs = (obj.getDouble("end_time") * 1000).toLong()
                )
            },
            timestampsJson = timestampsArray.toString()
        )
    }

    /**
     * Synthesize Kokoro TTS for caching purposes.
     * Returns raw audio bytes and timestamps JSON without playing.
     * Throws IOException if Kokoro server is unavailable.
     */
    suspend fun synthesizeKokoroForCache(text: String, voice: String): KokoroCacheResult = withContext(Dispatchers.IO) {
        if (kokoroServerUrl == null) {
            throw IOException("Kokoro server URL not configured")
        }

        val processedText = preprocessForKokoro(text)
        val json = JSONObject().apply {
            put("input", processedText)
            put("voice", voice)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$kokoroServerUrl/dev/captioned_speech")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = kokoroHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Kokoro error: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        val responseJson = JSONObject(responseBody)
        val audioBase64 = responseJson.getString("audio")
        val timestampsArray = responseJson.getJSONArray("timestamps")

        KokoroCacheResult(
            audioBytes = Base64.decode(audioBase64, Base64.DEFAULT),
            timestampsJson = timestampsArray.toString()
        )
    }

    /**
     * Result of Kokoro synthesis for caching (audio + timestamps as JSON string).
     */
    data class KokoroCacheResult(
        val audioBytes: ByteArray,
        val timestampsJson: String
    )

    private var currentPlaybackText: String? = null

    /**
     * Play audio with word timestamps and optional clipping.
     * @param startMs Start position in ms (0 = beginning)
     * @param endMs End position in ms (null = play to end)
     * @param playbackSpeed Playback speed multiplier (default uses speechRate)
     */
    private fun playWithTimestamps(
        audioBytes: ByteArray,
        timestamps: List<WordTimestamp>,
        onComplete: () -> Unit,
        startMs: Long = 0,
        endMs: Long? = null,
        playbackSpeed: Float? = null
    ) {
        // Clean up any previous playback
        stopKokoroPlayback()

        // Save to temp file (ExoPlayer needs a URI)
        val tempFile = File(context.cacheDir, "kokoro_${System.currentTimeMillis()}.mp3")
        tempFile.writeBytes(audioBytes)
        kokoroTempFile = tempFile

        // Build MediaItem with optional clipping
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.fromFile(tempFile))

        if (startMs > 0 || endMs != null) {
            val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
            if (endMs != null) {
                clippingConfig.setEndPositionMs(endMs)
            }
            mediaItemBuilder.setClippingConfiguration(clippingConfig.build())
        }

        val mediaItem = mediaItemBuilder.build()

        // Setup ExoPlayer
        val speed = playbackSpeed ?: speechRate
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            setMediaItem(mediaItem)
            playbackParameters = PlaybackParameters(speed)

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        cleanupKokoroPlayback()
                        onComplete()
                    }
                }
            })

            prepare()
            play()
        }

        // Schedule word highlights by finding actual positions in original text
        // Filter out punctuation-only timestamps (Kokoro returns commas, periods as separate words)
        // Only highlight words within the play range, adjusting timing for startMs offset and playback speed
        val originalText = currentPlaybackText ?: ""
        var searchStart = 0
        timestamps
            .filter { it.word.any { c -> c.isLetterOrDigit() } }  // Skip punctuation-only entries
            .filter { it.startMs >= startMs && (endMs == null || it.startMs < endMs) }  // Within range
            .forEach { wordTs ->
                // Adjust timing to account for clipping offset and playback speed
                val adjustedDelayMs = ((wordTs.startMs - startMs) / speed).toLong()
                highlightHandler.postDelayed({
                    // Find this word in the original text starting from searchStart
                    val wordStart = originalText.indexOf(wordTs.word, searchStart, ignoreCase = true)
                    if (wordStart >= 0) {
                        val wordEnd = wordStart + wordTs.word.length
                        onRangeStart?.invoke(wordStart, wordEnd)
                        searchStart = wordEnd  // Continue searching after this word
                    }
                }, adjustedDelayMs)
            }

        val rangeDesc = when {
            startMs > 0 && endMs != null -> "from ${startMs}ms to ${endMs}ms"
            startMs > 0 -> "from ${startMs}ms to end"
            endMs != null -> "until ${endMs}ms"
            else -> "full"
        }
        Log.d(TAG, "Kokoro playback started ($rangeDesc) with ${timestamps.size} word timestamps")
    }

    private fun stopKokoroPlayback() {
        isStopped = true  // Prevent any pending playback from starting
        highlightHandler.removeCallbacksAndMessages(null)
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        Log.d(TAG, "stopKokoroPlayback called, isMainThread=$isMainThread, exoPlayer=${exoPlayer != null}")

        // ExoPlayer requires main thread for all operations
        if (isMainThread) {
            val wasPlaying = exoPlayer?.isPlaying == true
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            Log.d(TAG, "ExoPlayer stopped synchronously, wasPlaying=$wasPlaying")
        } else {
            highlightHandler.post {
                val wasPlaying = exoPlayer?.isPlaying == true
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                Log.d(TAG, "ExoPlayer stopped via handler post, wasPlaying=$wasPlaying")
            }
        }
    }

    private fun cleanupKokoroPlayback() {
        stopKokoroPlayback()
        kokoroTempFile?.delete()
        kokoroTempFile = null
    }

    // Kokoro data classes
    private data class KokoroResult(
        val audioBytes: ByteArray,
        val timestamps: List<WordTimestamp>,
        val timestampsJson: String  // Raw JSON for caching
    )

    private data class WordTimestamp(
        val word: String,
        val startMs: Long,
        val endMs: Long
    )

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
        // Stop Kokoro playback if active
        stopKokoroPlayback()

        // Stop Android TTS if active
        if (isInitialized && tts != null) {
            tts?.stop()
            // Clear pending callbacks since we're stopping
            utteranceCallbacks.clear()
        }
    }

    fun isSpeaking(): Boolean {
        return exoPlayer?.isPlaying == true || tts?.isSpeaking == true
    }

    fun isReady(): Boolean {
        // Ready if Android TTS initialized OR if Kokoro server is configured
        return isInitialized || kokoroServerUrl != null
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
     * Set the Kokoro voice to use.
     */
    fun setKokoroVoice(voice: String) {
        kokoroVoice = voice
        Log.d(TAG, "Kokoro voice set to: $voice")
    }

    /**
     * Get the current Kokoro voice.
     */
    fun getKokoroVoice(): String = kokoroVoice

    /**
     * Fetch available Kokoro voices from the server.
     */
    suspend fun getKokoroVoices(): List<String> = withContext(Dispatchers.IO) {
        if (kokoroServerUrl == null) return@withContext emptyList()

        try {
            val request = Request.Builder()
                .url("$kokoroServerUrl/v1/audio/voices")
                .get()
                .build()

            val response = kokoroHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch Kokoro voices: ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(responseBody)
            val voicesArray = json.getJSONArray("voices")
            // Filter to British voices only (bf_ = British female, bm_ = British male)
            (0 until voicesArray.length())
                .map { voicesArray.getString(it) }
                .filter { it.startsWith("bf_") || it.startsWith("bm_") }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Kokoro voices: ${e.message}")
            emptyList()
        }
    }

    /**
     * Preview a Kokoro voice with sample text.
     * @param speed Optional playback speed (defaults to 1.0 for voice preview)
     */
    suspend fun speakKokoroPreview(text: String, voice: String, speed: Float = 1.0f) {
        if (kokoroServerUrl == null) return

        try {
            val json = JSONObject().apply {
                put("input", text)
                put("voice", voice)
                put("stream", false)
            }

            val request = Request.Builder()
                .url("$kokoroServerUrl/dev/captioned_speech")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // Fetch audio on IO thread
            val audioBytes = withContext(Dispatchers.IO) {
                val response = kokoroHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Kokoro preview failed: ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: return@withContext null
                val responseJson = JSONObject(responseBody)
                val audioBase64 = responseJson.getString("audio")
                Base64.decode(audioBase64, Base64.DEFAULT)
            } ?: return

            // Play on main thread (ExoPlayer requires it)
            withContext(Dispatchers.Main) {
                playWithTimestamps(audioBytes, emptyList(), onComplete = {}, playbackSpeed = speed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kokoro preview error: ${e.message}", e)
        }
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
        // Clean up Kokoro
        cleanupKokoroPlayback()

        // Clean up Android TTS
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "TTSService"
    }
}
