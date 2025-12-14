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
