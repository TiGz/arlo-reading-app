package com.example.arlo.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

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

    /**
     * Queue sentences for background TTS caching.
     * Processes sentences SEQUENTIALLY (one at a time) to avoid overwhelming the server.
     * Only runs if current voice is Kokoro (network-based).
     */
    fun queueSentencesForCaching(sentences: List<String>, pageId: Long) {
        // Only cache for Kokoro voices
        if (!ttsPreferences.isKokoroVoice()) {
            Log.d(TAG, "Skipping TTS pre-cache - using on-device voice")
            return
        }

        val voice = ttsPreferences.getKokoroVoice()

        scope.launch {
            Log.d(TAG, "Starting TTS pre-cache for page $pageId: ${sentences.size} sentences with voice $voice")

            sentences.forEachIndexed { index, sentence ->
                if (sentence.isBlank()) return@forEachIndexed

                val cacheFile = getCacheFile(sentence, voice)
                if (cacheFile.exists()) {
                    Log.d(TAG, "Sentence ${index + 1}/${sentences.size} already cached")
                    return@forEachIndexed
                }

                try {
                    val result = ttsService.synthesizeKokoroForCache(sentence, voice)
                    cacheFile.writeBytes(result.audioBytes)
                    // Save timestamps as JSON sidecar file
                    val timestampsFile = File(cacheFile.absolutePath + ".json")
                    timestampsFile.writeText(result.timestampsJson)
                    Log.d(TAG, "Cached sentence ${index + 1}/${sentences.size}: ${sentence.take(40)}...")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cache sentence ${index + 1}: ${e.message}")
                    // Continue with other sentences - don't let one failure stop all
                }
            }

            Log.d(TAG, "TTS pre-cache complete for page $pageId")
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

        return try {
            val array = org.json.JSONArray(cached.timestampsJson)
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
            // This handles cases like "Mirror Cliffs" being a single timestamp "Mirror Cliffs"
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

            // Third pass: estimate position if timestamps are truncated
            // Kokoro sometimes returns incomplete timestamps for sentences with hyphens
            // Estimate based on word position in sentence
            val sentenceWords = sentence.split(Regex("\\s+"))
            val targetIndex = sentenceWords.indexOfFirst { word ->
                word.lowercase().replace(Regex("[^a-z']"), "") == targetNormalized
            }

            if (targetIndex >= 0 && array.length() > 0) {
                // Get the last timestamp and estimate from there
                val lastObj = array.getJSONObject(array.length() - 1)
                val lastEndTime = lastObj.getDouble("end_time")
                val timestampedWordCount = array.length()

                // Estimate remaining words' duration
                val remainingWords = sentenceWords.size - timestampedWordCount
                if (remainingWords > 0 && targetIndex >= timestampedWordCount) {
                    // Estimate average word duration from existing timestamps
                    val avgDuration = lastEndTime / timestampedWordCount
                    val wordsUntilTarget = targetIndex - timestampedWordCount
                    val estimatedStart = lastEndTime + (avgDuration * wordsUntilTarget)
                    Log.d(TAG, "Estimated position for '$targetWord': ${estimatedStart}s (word $targetIndex of ${sentenceWords.size})")
                    return (estimatedStart * 1000).toLong()
                }
            }

            Log.d(TAG, "Word '$targetWord' not found in timestamps for: ${sentence.take(40)}...")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing timestamps: ${e.message}")
            null
        }
    }

    private fun getCacheFile(sentence: String, voice: String): File {
        val hash = md5(sentence.trim())
        return File(cacheDir, "${hash}_${voice}.mp3")
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

    companion object {
        private const val TAG = "TTSCacheManager"
    }
}
