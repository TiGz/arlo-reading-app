package com.example.arlo.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages pre-caching of TTS audio for sentences.
 *
 * Only caches audio for Kokoro voices (network-based). On-device Android TTS
 * voices are instant and don't benefit from pre-caching.
 *
 * Cache strategy:
 * - Store in context.filesDir/tts_cache/ (persistent storage)
 * - Filename: {md5_hash}_{voice}.mp3 with .json sidecar for timestamps
 * - File existence = cache hit (no database tracking)
 * - Single request queue: if a request for the same cache key is in-flight,
 *   we wait for it instead of making a duplicate request
 */
class TTSCacheManager(
    private val context: Context,
    private val ttsService: TTSService,
    private val ttsPreferences: TTSPreferences
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cacheDir: File by lazy {
        File(context.filesDir, "tts_cache").also { it.mkdirs() }
    }

    companion object {
        private const val TAG = "TTSCacheManager"
        private const val LOOKAHEAD_COUNT = 10  // Cache next 10 sentences
    }

    /**
     * In-flight requests: maps cache key to a deferred result.
     * If a request for a cache key is already in-flight, new requests
     * will await the same deferred instead of making duplicate API calls.
     */
    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<SynthesisResult?>>()

    /**
     * Mutex to ensure only one synthesis request runs at a time.
     * This prevents overwhelming the Kokoro server with parallel requests.
     */
    private val synthesisMutex = Mutex()

    /**
     * Result of a synthesis request, either from cache or fresh synthesis.
     */
    data class SynthesisResult(
        val audioBytes: ByteArray,
        val timestampsJson: String
    )

    /**
     * Get or synthesize audio for a sentence.
     * - If cached on disk, returns immediately
     * - If another request for this sentence is in-flight, waits for it
     * - Otherwise, queues a new synthesis request
     *
     * This is the primary method for getting TTS audio - handles all deduplication.
     */
    suspend fun getOrSynthesize(sentence: String): SynthesisResult? {
        if (!ttsPreferences.isKokoroVoice()) return null
        if (sentence.isBlank()) return null

        val voice = ttsPreferences.getKokoroVoice()
        val cacheKey = getCacheKey(sentence, voice)

        // 1. Check disk cache first
        val cached = getCachedAudio(sentence)
        if (cached != null) {
            Log.d(TAG, "Cache hit: ${sentence.take(40)}...")
            return SynthesisResult(cached.audioBytes, cached.timestampsJson)
        }

        // 2. Check if this request is already in-flight
        val existingRequest = inFlightRequests[cacheKey]
        if (existingRequest != null) {
            Log.d(TAG, "Waiting for in-flight request: ${sentence.take(40)}...")
            return existingRequest.await()
        }

        // 3. Create a new deferred for this request
        val deferred = CompletableDeferred<SynthesisResult?>()
        val previousDeferred = inFlightRequests.putIfAbsent(cacheKey, deferred)

        // Another thread beat us to it - wait for their result
        if (previousDeferred != null) {
            Log.d(TAG, "Another thread started request, waiting: ${sentence.take(40)}...")
            return previousDeferred.await()
        }

        // 4. We own this request - synthesize with mutex to serialize requests
        try {
            val result = synthesisMutex.withLock {
                // Double-check cache (another request might have completed while we waited)
                val rechecked = getCachedAudio(sentence)
                if (rechecked != null) {
                    Log.d(TAG, "Cache hit after mutex wait: ${sentence.take(40)}...")
                    return@withLock SynthesisResult(rechecked.audioBytes, rechecked.timestampsJson)
                }

                // Actually synthesize
                Log.d(TAG, "Synthesizing: ${sentence.take(40)}...")
                try {
                    val apiResult = ttsService.synthesizeKokoroForCache(sentence, voice)

                    // Save to disk cache
                    val cacheFile = getCacheFile(sentence, voice)
                    cacheFile.writeBytes(apiResult.audioBytes)
                    val timestampsFile = File(cacheFile.absolutePath + ".json")
                    timestampsFile.writeText(apiResult.timestampsJson)

                    Log.d(TAG, "Synthesized and cached: ${sentence.take(40)}...")
                    SynthesisResult(apiResult.audioBytes, apiResult.timestampsJson)
                } catch (e: Exception) {
                    Log.w(TAG, "Synthesis failed: ${e.message}")
                    null
                }
            }
            deferred.complete(result)
            return result
        } finally {
            // Remove from in-flight map
            inFlightRequests.remove(cacheKey)
        }
    }

    /**
     * Ensure the next N sentences are cached, starting from the current position.
     * Called when TTS playback starts to pre-cache upcoming sentences.
     * Uses the shared request queue, so duplicate requests are automatically deduplicated.
     */
    fun ensureLookaheadCached(sentences: List<String>, currentIndex: Int) {
        if (!ttsPreferences.isKokoroVoice()) {
            Log.d(TAG, "Skipping lookahead cache - using on-device voice")
            return
        }

        val voice = ttsPreferences.getKokoroVoice()

        // Get the next LOOKAHEAD_COUNT sentences that need caching
        val sentencesToCache = sentences
            .drop(currentIndex + 1)  // Skip current (already playing)
            .take(LOOKAHEAD_COUNT)
            .filter { it.isNotBlank() }
            .filter { sentence ->
                val cacheFile = getCacheFile(sentence, voice)
                !cacheFile.exists()
            }

        if (sentencesToCache.isEmpty()) {
            Log.d(TAG, "Lookahead: all next $LOOKAHEAD_COUNT sentences already cached")
            return
        }

        Log.d(TAG, "Lookahead: queueing ${sentencesToCache.size} sentences for caching")

        // Queue each sentence - getOrSynthesize handles deduplication
        sentencesToCache.forEach { sentence ->
            scope.launch {
                getOrSynthesize(sentence)
            }
        }
    }

    /**
     * Queue sentences for background TTS caching.
     * Only runs if current voice is Kokoro (network-based).
     */
    fun queueSentencesForCaching(sentences: List<String>, pageId: Long) {
        if (!ttsPreferences.isKokoroVoice()) {
            Log.d(TAG, "Skipping TTS pre-cache - using on-device voice")
            return
        }

        Log.d(TAG, "Queueing ${sentences.size} sentences for page $pageId")

        sentences.filter { it.isNotBlank() }.forEach { sentence ->
            scope.launch {
                getOrSynthesize(sentence)
            }
        }
    }

    /**
     * Get cached audio for a sentence, if available.
     * Returns null if not cached or if current voice is not Kokoro.
     */
    fun getCachedAudio(sentence: String): CachedAudio? {
        if (!ttsPreferences.isKokoroVoice()) return null

        val voice = ttsPreferences.getKokoroVoice()
        val cacheFile = getCacheFile(sentence, voice)
        val timestampsFile = File(cacheFile.absolutePath + ".json")

        if (!cacheFile.exists()) return null

        return try {
            CachedAudio(
                audioBytes = cacheFile.readBytes(),
                timestampsJson = if (timestampsFile.exists()) timestampsFile.readText() else "[]"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error reading cached audio: ${e.message}")
            null
        }
    }

    /**
     * Check if audio is cached for a sentence with the current voice.
     */
    fun isCached(sentence: String): Boolean {
        if (!ttsPreferences.isKokoroVoice()) return false
        val voice = ttsPreferences.getKokoroVoice()
        return getCacheFile(sentence, voice).exists()
    }

    /**
     * Check if a request for this sentence is currently in-flight.
     */
    fun isInFlight(sentence: String): Boolean {
        if (!ttsPreferences.isKokoroVoice()) return false
        val voice = ttsPreferences.getKokoroVoice()
        val cacheKey = getCacheKey(sentence, voice)
        return inFlightRequests.containsKey(cacheKey)
    }

    /**
     * Save synthesized audio to cache for future playback.
     * Called after successful network synthesis to populate cache opportunistically.
     */
    fun saveToCache(sentence: String, audioBytes: ByteArray, timestampsJson: String) {
        if (!ttsPreferences.isKokoroVoice()) return
        val voice = ttsPreferences.getKokoroVoice()
        scope.launch {
            try {
                val cacheFile = getCacheFile(sentence, voice)
                if (!cacheFile.exists()) {
                    cacheFile.writeBytes(audioBytes)
                    val timestampsFile = File(cacheFile.absolutePath + ".json")
                    timestampsFile.writeText(timestampsJson)
                    Log.d(TAG, "Saved to cache: ${sentence.take(40)}...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save to cache: ${e.message}")
            }
        }
    }

    /**
     * Find the start timestamp of a target word in cached audio.
     * Used for collaborative mode to know where to stop/start playback.
     * Returns null if not cached or word not found.
     *
     * Matching strategy:
     * 1. Exact match (normalized)
     * 2. Target word is contained in a timestamp word (e.g., "Mirror" in "Mirror-Cliffs")
     * 3. Timestamp word starts with target (e.g., "Mirror" matches "Mirror,")
     */
    fun findWordTimestamp(sentence: String, targetWord: String, findLast: Boolean = true): Long? {
        val cached = getCachedAudio(sentence) ?: return null
        return findWordTimestampInJson(cached.timestampsJson, targetWord, findLast)
    }

    private fun getCacheKey(sentence: String, voice: String): String {
        return "${md5(sentence.trim())}_${voice}"
    }

    private fun getCacheFile(sentence: String, voice: String): File {
        return File(cacheDir, "${getCacheKey(sentence, voice)}.mp3")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class CachedAudio(
        val audioBytes: ByteArray,
        val timestampsJson: String
    )

    /**
     * Find the start timestamp of a target word in JSON timestamps string.
     * Used when we have the timestamps in memory (e.g., from fresh synthesis)
     * without needing them to be cached first.
     */
    fun findWordTimestampInJson(timestampsJson: String, targetWord: String, findLast: Boolean = true): Long? {
        return try {
            val array = org.json.JSONArray(timestampsJson)
            val targetNormalized = targetWord.lowercase().replace(Regex("[^a-z']"), "")

            // Log all available timestamps for debugging
            val availableWords = mutableListOf<String>()
            for (i in 0 until array.length()) {
                availableWords.add(array.getJSONObject(i).getString("word"))
            }
            Log.d(TAG, "Looking for '$targetWord' (findLast=$findLast) in timestamps: $availableWords")

            // First pass: exact match - find last occurrence if findLast=true
            var lastMatchTime: Double? = null
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val word = obj.getString("word")
                val wordNormalized = word.lowercase().replace(Regex("[^a-z']"), "")

                if (wordNormalized == targetNormalized) {
                    val startTime = obj.getDouble("start_time")
                    if (findLast) {
                        lastMatchTime = startTime  // Keep updating to get last match
                    } else {
                        Log.d(TAG, "Found exact match for '$targetWord' at ${startTime}s")
                        return (startTime * 1000).toLong()
                    }
                }
            }

            if (lastMatchTime != null) {
                Log.d(TAG, "Found last exact match for '$targetWord' at ${lastMatchTime}s")
                return (lastMatchTime * 1000).toLong()
            }

            // Second pass: target word contained in timestamp word
            var lastPartialMatchTime: Double? = null
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val word = obj.getString("word")
                val wordNormalized = word.lowercase().replace(Regex("[^a-z']"), "")

                if (wordNormalized.contains(targetNormalized) && targetNormalized.length >= 3) {
                    val startTime = obj.getDouble("start_time")
                    if (findLast) {
                        lastPartialMatchTime = startTime
                    } else {
                        Log.d(TAG, "Found partial match: '$targetWord' in '$word' at ${startTime}s")
                        return (startTime * 1000).toLong()
                    }
                }
            }

            if (lastPartialMatchTime != null) {
                Log.d(TAG, "Found last partial match for '$targetWord' at ${lastPartialMatchTime}s")
                return (lastPartialMatchTime * 1000).toLong()
            }

            Log.d(TAG, "Word '$targetWord' not found in provided timestamps")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing timestamps: ${e.message}")
            null
        }
    }

}
