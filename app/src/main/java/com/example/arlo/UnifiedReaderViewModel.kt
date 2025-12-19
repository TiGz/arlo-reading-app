package com.example.arlo

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arlo.data.Book
import com.example.arlo.data.Page
import com.example.arlo.data.SentenceData
import com.example.arlo.tts.TTSPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.codec.language.DoubleMetaphone
import java.util.Locale

/**
 * ViewModel for the sentence-by-sentence reader.
 * Uses sentencesJson as the data source for all reading.
 */
class UnifiedReaderViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SpeechRecognition"
        // Delay between TTS completion and starting speech recognition
        // Reduced from 300ms - modern devices handle audio switching quickly
        private const val RECOGNITION_START_DELAY_MS = 100L
    }

    private val repository = (application as ArloApplication).repository
    val ttsService = (application as ArloApplication).ttsService
    private val ttsCacheManager = (application as ArloApplication).ttsCacheManager
    private val ttsPreferences = TTSPreferences(application)

    // Speech recognition (inline per reviewer feedback - no separate service)
    // Pre-warmed recognizer - created once and reused to reduce startup latency
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerWarm = false
    private val isSpeechAvailable: Boolean
    val speechDiagnostics: String

    // Audio feedback (inline per reviewer feedback - no separate service)
    private var soundPool: SoundPool? = null
    private var successSoundId: Int = 0
    private var errorSoundId: Int = 0

    init {
        // Load and apply saved speech rate and Kokoro voice
        val savedRate = ttsPreferences.getSpeechRate()
        ttsService.setSpeechRate(savedRate)
        val savedKokoroVoice = ttsPreferences.getKokoroVoice()
        ttsService.setKokoroVoice(savedKokoroVoice)

        // Get speech recognition availability from Application-level diagnostics
        val arloApp = application as ArloApplication
        isSpeechAvailable = arloApp.isSpeechRecognitionAvailable
        speechDiagnostics = arloApp.speechRecognitionDiagnostics

        // Initialize SoundPool for audio feedback
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()
        successSoundId = soundPool?.load(application, R.raw.success_ping, 1) ?: 0
        errorSoundId = soundPool?.load(application, R.raw.error_buzz, 1) ?: 0
    }

    // Collaborative reading state (simplified - 3 states max per reviewer feedback)
    enum class CollaborativeState {
        IDLE,       // Not listening
        LISTENING,  // Mic is recording
        FEEDBACK    // Showing success/error briefly
    }

    // UI State
    data class ReaderState(
        val book: Book? = null,
        val pages: List<Page> = emptyList(),
        val currentPageIndex: Int = 0,
        val currentSentenceIndex: Int = 0,
        val sentences: List<SentenceData> = emptyList(),
        val isPlaying: Boolean = false,
        val isLoading: Boolean = true,
        val pendingPageCount: Int = 0,
        val needsMorePages: Boolean = false,
        val autoAdvance: Boolean = true,  // Auto-advance to next sentence after TTS finishes
        val highlightRange: Pair<Int, Int>? = null,  // Word highlight start/end offsets for TTS
        val speechRate: Float = 1.0f,  // TTS speech rate (0.25 to 1.0)
        // Collaborative reading state
        val collaborativeMode: Boolean = false,
        val collaborativeState: CollaborativeState = CollaborativeState.IDLE,
        val targetWord: String? = null,
        val attemptCount: Int = 0,
        val lastAttemptSuccess: Boolean? = null,  // null = no attempt yet, true = success, false = failure
        val micLevel: Int = 0,  // 0-100 mic input level for visual feedback
        val isSpeakingTargetWord: Boolean = false,  // True when TTS is reading target word after failures
        // Kid mode - locks collaborative mode ON and hides toggle button
        val kidMode: Boolean = true  // Default ON - kids can't turn off collaborative mode
    ) {
        val currentPage: Page? get() = pages.getOrNull(currentPageIndex)
        val currentSentence: SentenceData? get() = sentences.getOrNull(currentSentenceIndex)
        val pageNumber: Int get() = currentPage?.pageNumber ?: 1
        val totalPages: Int get() = pages.size
        val sentenceNumber: Int get() = currentSentenceIndex + 1
        val totalSentences: Int get() = sentences.size

        val isLastSentenceIncomplete: Boolean get() =
            currentSentenceIndex == sentences.lastIndex &&
            currentSentence?.isComplete == false
    }

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var bookId: Long = -1L

    fun loadBook(bookId: Long, hasAudioPermission: Boolean) {
        this.bookId = bookId
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)

            val book = withContext(Dispatchers.IO) { repository.getBook(bookId) }
            // Use completed pages only (filter out processing/pending)
            val pages = withContext(Dispatchers.IO) { repository.getCompletedPagesSync(bookId) }

            // Load saved preferences
            val savedCollaborativeMode = ttsPreferences.getCollaborativeMode()
            val savedAutoAdvance = ttsPreferences.getAutoAdvance()
            val savedSpeechRate = ttsPreferences.getSpeechRate()
            val savedKidMode = ttsPreferences.getKidMode()

            // In kid mode, force collaborative mode ON
            // Also require audio permission for collaborative mode to work
            val effectiveCollaborativeMode = if (savedKidMode) {
                hasAudioPermission  // Kid mode forces collaborative ON (if permission granted)
            } else {
                savedCollaborativeMode && hasAudioPermission
            }

            if (book != null && pages.isNotEmpty()) {
                // Resume from last position or start at beginning
                val startPageIndex = pages.indexOfFirst { it.pageNumber == book.lastReadPageNumber }
                    .coerceAtLeast(0)
                val startSentenceIndex = book.lastReadSentenceIndex

                val sentences = parseSentencesForPage(pages.getOrNull(startPageIndex))

                _state.value = ReaderState(
                    book = book,
                    pages = pages,
                    currentPageIndex = startPageIndex,
                    currentSentenceIndex = startSentenceIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0)),
                    sentences = sentences,
                    isPlaying = false,
                    needsMorePages = false,
                    isLoading = false,
                    speechRate = savedSpeechRate,
                    collaborativeMode = effectiveCollaborativeMode,
                    autoAdvance = savedAutoAdvance,
                    kidMode = savedKidMode
                )

                // Pre-cache TTS audio for current page (ensures instant playback even for older pages)
                preCacheCurrentPage(pages.getOrNull(startPageIndex), sentences)
            } else {
                _state.value = ReaderState(
                    book = book,
                    pages = pages,
                    isLoading = false,
                    needsMorePages = pages.isEmpty(),
                    collaborativeMode = effectiveCollaborativeMode,
                    autoAdvance = savedAutoAdvance,
                    kidMode = savedKidMode
                )
            }

            // Observe pending page count
            observePendingPages()
        }
    }

    private fun observePendingPages() {
        viewModelScope.launch {
            repository.getPendingPageCount(bookId).collect { count ->
                _state.value = _state.value.copy(pendingPageCount = count)
            }
        }
    }

    /**
     * Pre-cache TTS audio for the current page in background.
     * Ensures instant playback even for pages that were OCR'd before caching existed.
     */
    private fun preCacheCurrentPage(page: Page?, sentences: List<SentenceData>) {
        if (page == null || sentences.isEmpty()) return
        ttsCacheManager.queueSentencesForCaching(
            sentences.map { it.text },
            page.id
        )
    }

    /**
     * Toggle auto-advance mode.
     * When enabled (default), TTS continues to next sentence automatically.
     * When disabled, TTS reads one sentence and waits for user to tap next.
     */
    fun toggleAutoAdvance() {
        val newValue = !_state.value.autoAdvance
        ttsPreferences.saveAutoAdvance(newValue)
        _state.value = _state.value.copy(autoAdvance = newValue)
    }

    /**
     * Toggle kid mode (parental lock).
     * When enabled, collaborative mode is locked ON and the toggle button is hidden.
     * Unlocked via 5 taps on book title.
     */
    fun toggleKidMode() {
        val current = _state.value
        val newKidMode = !current.kidMode
        ttsPreferences.saveKidMode(newKidMode)

        if (newKidMode) {
            // Entering kid mode - force collaborative mode ON
            ttsPreferences.saveCollaborativeMode(true)
            _state.value = current.copy(
                kidMode = true,
                collaborativeMode = true
            )
            Log.d(TAG, "Kid mode ENABLED - collaborative mode locked ON")
        } else {
            // Exiting kid mode - keep current collaborative setting
            _state.value = current.copy(kidMode = false)
            Log.d(TAG, "Kid mode DISABLED - parent mode unlocked")
        }
    }

    /**
     * Set the TTS speech rate (0.25 to 1.0).
     */
    fun setSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(0.25f, 1.0f)
        ttsService.setSpeechRate(clampedRate)
        ttsPreferences.saveSpeechRate(clampedRate)
        _state.value = _state.value.copy(speechRate = clampedRate)
    }

    /**
     * Set the Kokoro TTS voice.
     */
    fun setKokoroVoice(voice: String) {
        ttsService.setKokoroVoice(voice)
        ttsPreferences.saveKokoroVoice(voice)
    }

    /**
     * Move to a specific page (for ViewPager page changes).
     */
    fun moveToPage(pageIndex: Int) {
        val current = _state.value
        if (pageIndex < 0 || pageIndex >= current.pages.size) return

        val page = current.pages.getOrNull(pageIndex) ?: return
        val sentences = parseSentencesForPage(page)

        ttsService.stop()
        cancelSpeechRecognition()
        _state.value = current.copy(
            currentPageIndex = pageIndex,
            currentSentenceIndex = 0,
            sentences = sentences,
            isPlaying = false,
            needsMorePages = false,
            highlightRange = null,
            targetWord = null,
            attemptCount = 0,
            lastAttemptSuccess = null,
            collaborativeState = CollaborativeState.IDLE
        )
        saveReadingPosition()
    }

    fun nextSentence() {
        val current = _state.value
        if (current.sentences.isEmpty()) return

        val nextIndex = current.currentSentenceIndex + 1

        if (nextIndex < current.sentences.size) {
            // Move to next sentence on same page
            _state.value = current.copy(
                currentSentenceIndex = nextIndex,
                highlightRange = null,
                targetWord = null,  // Clear until TTS reaches the word
                attemptCount = 0,
                lastAttemptSuccess = null,
                collaborativeState = CollaborativeState.IDLE  // Reset collaborative state on navigation
            )
            saveReadingPosition()
        } else {
            // Try to move to next page
            val nextPageIndex = current.currentPageIndex + 1
            if (nextPageIndex < current.pages.size) {
                moveToPage(nextPageIndex)
            } else {
                // No more pages - need to scan more
                _state.value = current.copy(needsMorePages = true, isPlaying = false)
                ttsService.stop()
            }
        }
    }

    fun previousSentence() {
        val current = _state.value
        if (current.sentences.isEmpty()) return

        val prevIndex = current.currentSentenceIndex - 1

        if (prevIndex >= 0) {
            // Move to previous sentence on same page
            _state.value = current.copy(
                currentSentenceIndex = prevIndex,
                highlightRange = null,
                targetWord = null,  // Clear until TTS reaches the word
                attemptCount = 0,
                lastAttemptSuccess = null,
                collaborativeState = CollaborativeState.IDLE  // Reset collaborative state on navigation
            )
            saveReadingPosition()
        } else {
            // Try to move to previous page
            val prevPageIndex = current.currentPageIndex - 1
            if (prevPageIndex >= 0) {
                val prevPage = current.pages.getOrNull(prevPageIndex)
                val prevSentences = parseSentencesForPage(prevPage)
                val lastSentenceIndex = (prevSentences.size - 1).coerceAtLeast(0)

                _state.value = current.copy(
                    currentPageIndex = prevPageIndex,
                    currentSentenceIndex = lastSentenceIndex,
                    sentences = prevSentences,
                    needsMorePages = false,
                    highlightRange = null,
                    targetWord = null,
                    attemptCount = 0,
                    lastAttemptSuccess = null,
                    collaborativeState = CollaborativeState.IDLE
                )
                saveReadingPosition()
            }
            // If at the very beginning, do nothing
        }
    }

    fun togglePlayPause() {
        val current = _state.value

        if (current.isPlaying) {
            // Stop
            ttsService.stop()
            cancelSpeechRecognition()
            _state.value = current.copy(
                isPlaying = false,
                collaborativeState = CollaborativeState.IDLE,
                targetWord = null,
                attemptCount = 0,
                lastAttemptSuccess = null
            )
        } else {
            // Start playing
            if (current.collaborativeMode) {
                speakCurrentSentenceCollaborative()
            } else {
                speakCurrentSentence()
            }
            _state.value = current.copy(isPlaying = true)
        }
    }

    fun stopReading() {
        ttsService.stop()
        _state.value = _state.value.copy(isPlaying = false, highlightRange = null)
    }

    private fun speakCurrentSentence() {
        val sentence = _state.value.currentSentence ?: return

        // Don't read incomplete sentences (they're cut off mid-thought)
        if (!sentence.isComplete && _state.value.currentSentenceIndex == _state.value.sentences.lastIndex) {
            if (_state.value.isPlaying && _state.value.autoAdvance) {
                nextSentence()
            }
            return
        }

        if (ttsService.isReady()) {
            // Set up word highlighting callback
            ttsService.setOnRangeStartListener { start, end ->
                _state.value = _state.value.copy(highlightRange = Pair(start, end))
            }

            viewModelScope.launch {
                ttsService.speakWithKokoro(sentence.text, ttsCacheManager) {
                    // Callback when TTS finishes
                    if (_state.value.isPlaying) {
                        if (_state.value.autoAdvance) {
                            // Auto-advance mode: move to next sentence and keep reading
                            nextSentence()
                            if (_state.value.isPlaying && !_state.value.needsMorePages) {
                                speakCurrentSentence()
                            }
                        } else {
                            // Manual mode: stop after reading one sentence
                            _state.value = _state.value.copy(isPlaying = false)
                        }
                    }
                }
            }
        } else {
            Log.e(TAG, "TTS service not ready, cannot speak")
        }
    }

    /**
     * Start TTS from a specific sentence index (for touch-to-read in full page mode).
     */
    fun speakFromSentence(sentenceIndex: Int) {
        val current = _state.value
        if (sentenceIndex < 0 || sentenceIndex >= current.sentences.size) return

        _state.value = current.copy(currentSentenceIndex = sentenceIndex, isPlaying = true)
        speakCurrentSentence()
    }

    private fun saveReadingPosition() {
        val current = _state.value
        val page = current.currentPage ?: return

        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLastReadPosition(
                bookId = bookId,
                pageNumber = page.pageNumber,
                sentenceIndex = current.currentSentenceIndex
            )
        }
    }

    private fun parseSentencesForPage(page: Page?): List<SentenceData> {
        if (page == null) return emptyList()

        // Parse JSON sentences
        val sentences = repository.parseSentences(page.sentencesJson)
        if (sentences.isNotEmpty()) {
            return sentences
        }

        // Fallback: split plain text into sentences (for compatibility)
        if (page.text.isNotBlank()) {
            return page.text
                .split(Regex("""(?<=[.!?])\s+"""))
                .filter { it.isNotBlank() }
                .map { SentenceData(text = it.trim(), isComplete = true) }
        }

        return emptyList()
    }

    /**
     * Refresh pages (e.g., after background processing completes).
     */
    fun refreshPages() {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) { repository.getCompletedPagesSync(bookId) }
            val current = _state.value

            if (pages.isNotEmpty()) {
                // Keep current position if possible
                val currentPageNumber = current.currentPage?.pageNumber ?: 1
                val newPageIndex = pages.indexOfFirst { it.pageNumber == currentPageNumber }
                    .coerceAtLeast(0)

                val sentences = parseSentencesForPage(pages.getOrNull(newPageIndex))

                _state.value = current.copy(
                    pages = pages,
                    currentPageIndex = newPageIndex,
                    sentences = sentences,
                    currentSentenceIndex = current.currentSentenceIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0)),
                    needsMorePages = false
                )
            }
        }
    }

    // ==================== COLLABORATIVE READING ====================

    /**
     * Toggle collaborative reading mode on/off.
     * Only available in sentence mode.
     */
    fun toggleCollaborativeMode() {
        val current = _state.value
        val newMode = !current.collaborativeMode
        ttsPreferences.saveCollaborativeMode(newMode)

        if (current.collaborativeMode) {
            // Turning off - cancel any active recognition
            cancelSpeechRecognition()
            _state.value = current.copy(
                collaborativeMode = false,
                collaborativeState = CollaborativeState.IDLE,
                targetWord = null,
                attemptCount = 0,
                lastAttemptSuccess = null
            )
        } else {
            // Turning on - don't set targetWord yet, only highlight when it's time to read
            _state.value = current.copy(
                collaborativeMode = true,
                collaborativeState = CollaborativeState.IDLE
            )
        }
    }

    /**
     * Check if speech recognition is available on this device.
     */
    fun isSpeechRecognitionAvailable(): Boolean = isSpeechAvailable

    /**
     * Extract target words from sentence for collaborative reading.
     * If the last word is single-syllable, extract last 2 words for better recognition.
     * Returns Pair(targetWords, rangeInSentence) where range is character indices.
     */
    private fun extractTargetWords(sentence: String): Pair<String, IntRange> {
        val trimmed = sentence.trim()
        val words = trimmed.split(" ")

        if (words.size <= 1) {
            // Single word sentence - user reads the whole thing
            return Pair(trimmed, 0 until trimmed.length)
        }

        val lastWord = words.last()
        val lastWordClean = normalizeWord(lastWord)

        // Check if last word is single-syllable (use vowel count as heuristic)
        val isSingleSyllable = countSyllables(lastWordClean) <= 1

        return if (isSingleSyllable && words.size >= 2) {
            // Use last 2 words for short words like "it", "the", "is", etc.
            val secondLastSpaceIndex = trimmed.lastIndexOf(' ', trimmed.lastIndexOf(' ') - 1)
            if (secondLastSpaceIndex == -1) {
                // Only 2 words total - user reads both
                Pair(trimmed, 0 until trimmed.length)
            } else {
                val lastTwoWords = trimmed.substring(secondLastSpaceIndex + 1)
                Pair(lastTwoWords, (secondLastSpaceIndex + 1) until trimmed.length)
            }
        } else {
            // Use just the last word
            val lastSpaceIndex = trimmed.lastIndexOf(' ')
            Pair(lastWord, (lastSpaceIndex + 1) until trimmed.length)
        }
    }

    /**
     * Count syllables in a word using vowel groups as heuristic.
     * Not perfect but good enough for detecting short words.
     */
    private fun countSyllables(word: String): Int {
        if (word.isEmpty()) return 0
        val vowels = "aeiouy"
        var count = 0
        var prevWasVowel = false

        for (char in word.lowercase()) {
            val isVowel = char in vowels
            if (isVowel && !prevWasVowel) {
                count++
            }
            prevWasVowel = isVowel
        }

        // Handle silent 'e' at end
        if (word.lowercase().endsWith("e") && count > 1) {
            count--
        }

        return count.coerceAtLeast(1)
    }

    /**
     * Normalize word for matching - strip punctuation, lowercase.
     * Keeps hyphens for compound words like "wounded-stream".
     */
    private fun normalizeWord(word: String): String {
        return word.lowercase()
            .trim()
            .replace(Regex("[.!?,;:\"'()\\[\\]]"), "")
    }

    /**
     * Split target words handling hyphens as word separators.
     * "wounded-stream trunk" → ["wounded", "stream", "trunk"]
     */
    private fun splitIntoWords(text: String): List<String> {
        return text.lowercase()
            .replace("-", " ")  // Treat hyphens as spaces
            .split(" ")
            .map { normalizeWord(it) }
            .filter { it.isNotEmpty() }
    }

    // Double Metaphone for phonetic matching (handles "than"/"then"/"Dan", "flesh"/"flash" etc.)
    private val doubleMetaphone = DoubleMetaphone()

    // Homophones map - speech recognition often transcribes numbers for words
    // Each set contains words that sound the same
    private val homophoneSets = listOf(
        setOf("to", "too", "two", "2"),
        setOf("for", "four", "4"),
        setOf("one", "won", "1"),
        setOf("eight", "ate", "8"),
        setOf("be", "bee"),
        setOf("by", "buy", "bye"),
        setOf("know", "no"),
        setOf("knew", "new"),
        setOf("right", "write"),
        setOf("there", "their", "they're"),
        setOf("hear", "here"),
        setOf("see", "sea"),
        setOf("son", "sun"),
        setOf("would", "wood"),
        setOf("you", "u"),
        setOf("your", "you're", "ur"),
        setOf("are", "r"),
        setOf("why", "y"),
        setOf("oh", "o", "0")
    )

    // Build lookup map for O(1) homophone checking
    private val homophoneMap: Map<String, Set<String>> = homophoneSets.flatMap { set ->
        set.map { word -> word to set }
    }.toMap()

    /**
     * Check if spoken words match target using phonetic matching.
     * Handles both single words and multi-word targets (e.g., "read it").
     * Uses Double Metaphone algorithm to match phonetically similar words
     * (e.g., "than"/"then"/"Dan", "flesh"/"flash").
     * Also handles hyphenated words like "wounded-stream" by splitting on hyphens.
     * Handles speech recognition splitting words incorrectly (e.g., "wounded" → "when did").
     */
    private fun isWordMatch(spokenResults: List<String>, targetWords: String): Boolean {
        // Split target handling hyphens: "wounded-stream trunk" → ["wounded", "stream", "trunk"]
        val targetWordsList = splitIntoWords(targetWords)

        return spokenResults.any { spoken ->
            // Split spoken words the same way
            val spokenWordsList = splitIntoWords(spoken)

            // Try matching with flexible word boundaries
            matchWordsFlexibly(spokenWordsList, targetWordsList)
        }
    }

    /**
     * Flexibly match spoken words to target words.
     * Handles cases where speech recognition splits one word into two
     * (e.g., "wounded" → "when did") by trying to match concatenated adjacent words.
     */
    private fun matchWordsFlexibly(spoken: List<String>, target: List<String>): Boolean {
        if (target.isEmpty()) return true
        if (spoken.isEmpty()) return false

        // Try to match starting from position 0 in spoken words
        return tryMatchFromPosition(spoken, 0, target, 0)
    }

    /**
     * Recursively try to match target words against spoken words,
     * allowing 1-2 spoken words to match a single target word.
     */
    private fun tryMatchFromPosition(
        spoken: List<String>,
        spokenIdx: Int,
        target: List<String>,
        targetIdx: Int
    ): Boolean {
        // Base case: matched all target words
        if (targetIdx >= target.size) return true

        // Not enough spoken words left
        if (spokenIdx >= spoken.size) return false

        val targetWord = target[targetIdx]

        // Try 1: Single spoken word matches target word
        if (isPhoneticMatch(spoken[spokenIdx], targetWord)) {
            if (tryMatchFromPosition(spoken, spokenIdx + 1, target, targetIdx + 1)) {
                return true
            }
        }

        // Try 2: Two spoken words concatenated match target word
        // (handles "wounded" being heard as "when did")
        if (spokenIdx + 1 < spoken.size) {
            val twoWordConcat = spoken[spokenIdx] + spoken[spokenIdx + 1]
            if (isPhoneticMatch(twoWordConcat, targetWord)) {
                Log.d(TAG, "Concat match: '${spoken[spokenIdx]}+${spoken[spokenIdx + 1]}' ≈ '$targetWord'")
                if (tryMatchFromPosition(spoken, spokenIdx + 2, target, targetIdx + 1)) {
                    return true
                }
            }
        }

        // Try 3: Skip this spoken word and try again (allows extra words in speech)
        // But only if we haven't matched any target words yet (to avoid skipping important words)
        if (targetIdx == 0 && spokenIdx < spoken.size - target.size) {
            return tryMatchFromPosition(spoken, spokenIdx + 1, target, targetIdx)
        }

        return false
    }

    /**
     * Check if two words are phonetically similar using Double Metaphone.
     * Also checks homophones (too/2, for/4, etc.) and containment.
     */
    private fun isPhoneticMatch(spoken: String, target: String): Boolean {
        // Exact match
        if (spoken == target) return true

        // Empty strings
        if (spoken.isEmpty() || target.isEmpty()) return false

        // Homophone match (handles "too"/"2", "for"/"4", etc.)
        val spokenHomophones = homophoneMap[spoken.lowercase()]
        if (spokenHomophones != null && target.lowercase() in spokenHomophones) {
            Log.d(TAG, "Homophone match: '$spoken' ≈ '$target'")
            return true
        }

        // Containment match: "forever" contains "ever"
        if (target.length >= 3 && spoken.contains(target, ignoreCase = true)) {
            Log.d(TAG, "Containment match: '$spoken' contains '$target'")
            return true
        }

        // Double Metaphone comparison (handles "than"/"then", "flesh"/"flash" etc.)
        if (doubleMetaphone.isDoubleMetaphoneEqual(spoken, target)) {
            Log.d(TAG, "Phonetic match: '$spoken' ≈ '$target' (DoubleMetaphone)")
            return true
        }

        // Also check alternate encodings for edge cases
        val spokenPrimary = doubleMetaphone.doubleMetaphone(spoken)
        val spokenAlt = doubleMetaphone.doubleMetaphone(spoken, true)
        val targetPrimary = doubleMetaphone.doubleMetaphone(target)
        val targetAlt = doubleMetaphone.doubleMetaphone(target, true)

        // Any combination of primary/alternate matches
        if (spokenPrimary == targetAlt || spokenAlt == targetPrimary || spokenAlt == targetAlt) {
            Log.d(TAG, "Phonetic match (alt): '$spoken' ≈ '$target'")
            return true
        }

        return false
    }

    // Store target words outside of state to avoid race conditions with TTS callbacks
    private var currentTargetWords: String? = null

    /**
     * Speak current sentence in collaborative mode:
     * TTS reads all but the last word, then listens for user to speak it.
     * Uses timestamp-based clipping to play partial audio from full sentence cache.
     */
    fun speakCurrentSentenceCollaborative() {
        val sentence = _state.value.currentSentence ?: return

        // Skip incomplete sentences - use normal TTS
        if (!sentence.isComplete && _state.value.currentSentenceIndex == _state.value.sentences.lastIndex) {
            speakCurrentSentence()
            return
        }

        // Pre-warm the recognizer while TTS is playing to reduce startup latency
        ensureRecognizerWarm()

        val (targetWords, range) = extractTargetWords(sentence.text)
        Log.d(TAG, "extractTargetWords: sentence='${sentence.text}', targetWords='$targetWords', range=$range")

        // Store target words locally only - don't set in state until TTS finishes
        // Setting targetWord in state while TTS is playing causes "Your turn!" to show prematurely
        currentTargetWords = targetWords
        _state.value = _state.value.copy(
            targetWord = null,  // Will be set when TTS finishes in onPartialTTSComplete
            attemptCount = 0,
            lastAttemptSuccess = null,
            collaborativeState = CollaborativeState.IDLE,
            isPlaying = true
        )

        // Check if single word sentence
        val textWithoutTargetWords = sentence.text.substring(0, range.first).trim()
        if (textWithoutTargetWords.isEmpty()) {
            // Single word sentence - go straight to listening
            onPartialTTSComplete(targetWords)
            return
        }

        if (!ttsService.isReady()) {
            Log.e(TAG, "TTS service not ready, cannot speak")
            return
        }

        // Set up word highlighting callback
        ttsService.setOnRangeStartListener { start, end ->
            _state.value = _state.value.copy(highlightRange = Pair(start, end))
        }

        viewModelScope.launch {
            // Find where target word starts in the cached audio
            val firstTargetWord = targetWords.split(" ").first()
            val stopAtMs = ttsCacheManager.findWordTimestamp(sentence.text, firstTargetWord)

            if (stopAtMs != null) {
                // Cache hit - play full sentence audio but stop at target word
                Log.d(TAG, "Collaborative: playing until ${stopAtMs}ms (before '$firstTargetWord')")
                ttsService.speakWithKokoroUntil(sentence.text, ttsCacheManager, stopAtMs) {
                    _state.value = _state.value.copy(highlightRange = null)
                    onPartialTTSComplete(targetWords)
                }
            } else {
                // Cache miss - fetch full sentence, find timestamp, play partial
                // speakWithKokoroUntil handles this internally but we need to find the timestamp first
                // For now, fallback to speaking the partial text directly (will be cached as partial)
                Log.d(TAG, "Collaborative: cache miss, fetching full sentence first")
                ttsService.speakWithKokoro(sentence.text, ttsCacheManager) {
                    // Full sentence now cached - but we played it all
                    // On next sentence, cache will hit properly
                    // TODO: Could retry with partial playback here, but user already heard it
                    _state.value = _state.value.copy(highlightRange = null)
                    onPartialTTSComplete(targetWords)
                }
            }
        }
    }

    /**
     * Called when TTS finishes reading partial sentence.
     * Starts listening for user's speech.
     */
    private fun onPartialTTSComplete(targetWords: String) {
        Log.d(TAG, "onPartialTTSComplete: targetWords='$targetWords'")
        // Re-set targetWord in state in case it was lost to race conditions
        _state.value = _state.value.copy(targetWord = targetWords)
        viewModelScope.launch {
            // Small delay for audio buffer to clear (reduced from 300ms)
            delay(RECOGNITION_START_DELAY_MS)
            Log.d(TAG, "After ${RECOGNITION_START_DELAY_MS}ms delay, starting recognition for: '${_state.value.targetWord}'")
            startSpeechRecognition()
        }
    }

    /**
     * Pre-warm the SpeechRecognizer so it's ready to start listening faster.
     * Call this when TTS starts playing in collaborative mode.
     */
    private fun ensureRecognizerWarm() {
        if (speechRecognizer != null && isRecognizerWarm) {
            Log.d(TAG, "Recognizer already warm")
            return
        }

        if (!isSpeechAvailable) {
            Log.w(TAG, "Speech recognition not available, cannot pre-warm")
            return
        }

        val context = getApplication<Application>()

        // Destroy existing recognizer if it exists but isn't warm
        speechRecognizer?.destroy()

        // Create recognizer - explicitly use Google's speech recognizer on Fire tablets
        speechRecognizer = try {
            val googleComponent = ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
            )
            SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Google recognizer, falling back to default: ${e.message}")
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        // Set listener now so it's ready when we call startListening
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
        isRecognizerWarm = true
        Log.d(TAG, "Recognizer pre-warmed and ready")
    }

    /**
     * Create the RecognitionListener used for all speech recognition.
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech - recognizer ready to hear speech")
                _state.value = _state.value.copy(collaborativeState = CollaborativeState.LISTENING)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList()
                Log.d(TAG, "onResults: $matches")
                handleSpeechResults(matches)
            }

            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "UNKNOWN($error)"
                }
                Log.e(TAG, "onError: $errorName")

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Count as failed attempt
                        handleSpeechResults(emptyList())
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        // Transient errors - count as failed attempt and retry
                        Log.w(TAG, "Transient speech error: $errorName, treating as failed attempt")
                        handleSpeechResults(emptyList())
                    }
                    else -> {
                        // Technical error - fall back to normal mode
                        handleSpeechError("Speech recognition error: $errorName")
                    }
                }
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech - user started speaking")
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech - user stopped speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Convert RMS dB to 0-100 level for UI
                // RMS typically ranges from -2 to 10 dB
                val level = ((rmsdB + 2) / 12 * 100).toInt().coerceIn(0, 100)
                _state.value = _state.value.copy(micLevel = level)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Not used but required by interface
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList()
                Log.d(TAG, "onPartialResults: $partial")

                // Check partial results for short words - they often don't make it to final results
                if (partial.isNotEmpty()) {
                    val targetWord = _state.value.targetWord
                    if (targetWord != null && isWordMatch(partial, targetWord)) {
                        // Found a match in partial results - accept it immediately
                        Log.d(TAG, "Partial match found for '$targetWord' in: $partial")
                        speechRecognizer?.cancel()  // Stop listening
                        handleSpeechResults(partial)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Not used but required by interface
            }
        }
    }

    /**
     * Create the recognition intent with optimized settings.
     */
    private fun createRecognitionIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)  // More results to find matches
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)  // Capture short words via partial results
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)  // Very short minimum (100ms)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)  // Slightly longer to capture full word
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
    }

    /**
     * Initialize and start speech recognition.
     * Uses pre-warmed recognizer if available for faster startup.
     */
    fun startSpeechRecognition() {
        if (!isSpeechAvailable) {
            handleSpeechError("Speech recognition not available")
            return
        }

        // Ensure recognizer is warm (creates if needed, reuses if already warm)
        ensureRecognizerWarm()

        val recognizer = speechRecognizer
        if (recognizer == null) {
            Log.e(TAG, "Failed to create speech recognizer")
            handleSpeechError("Speech recognition unavailable")
            return
        }

        val targetWord = _state.value.targetWord ?: ""
        Log.d(TAG, "Starting recognition for target word: '$targetWord'")

        // Set a fresh listener before each startListening call
        recognizer.setRecognitionListener(createRecognitionListener())

        _state.value = _state.value.copy(collaborativeState = CollaborativeState.LISTENING, micLevel = 0)

        try {
            recognizer.startListening(createRecognitionIntent())
            Log.d(TAG, "startListening called successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting listening: ${e.message}")
            isRecognizerWarm = false
            handleSpeechError("Cannot access speech recognition service")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting listening: ${e.message}")
            isRecognizerWarm = false
            handleSpeechError("Speech recognition failed to start")
        }
    }

    /**
     * Handle speech recognition results.
     */
    private fun handleSpeechResults(results: List<String>) {
        val current = _state.value
        val targetWord = current.targetWord ?: return

        // Ignore if we already processed a successful result (prevents error callback after cancel)
        if (current.collaborativeState == CollaborativeState.FEEDBACK && current.lastAttemptSuccess == true) {
            Log.d("SpeechRecognition", "handleSpeechResults: ignoring, already processed success")
            return
        }

        val isMatch = isWordMatch(results, targetWord)
        Log.d("SpeechRecognition", "handleSpeechResults: results=$results, target='$targetWord', isMatch=$isMatch")
        val newAttemptCount = current.attemptCount + 1

        if (isMatch) {
            // Success!
            playSuccessSound()
            _state.value = current.copy(
                collaborativeState = CollaborativeState.FEEDBACK,
                lastAttemptSuccess = true,
                attemptCount = newAttemptCount
            )

            // Auto-advance after brief feedback
            viewModelScope.launch {
                delay(500)
                Log.d("SpeechRecognition", "Success delay complete, calling nextSentence()")
                // Reset collaborative state before advancing to prevent stale indicator showing
                _state.value = _state.value.copy(
                    collaborativeState = CollaborativeState.IDLE,
                    attemptCount = 0,
                    lastAttemptSuccess = null,
                    targetWord = null
                )
                nextSentence()
                Log.d("SpeechRecognition", "After nextSentence: isPlaying=${_state.value.isPlaying}, needsMorePages=${_state.value.needsMorePages}")
                // Continue if still playing
                if (_state.value.isPlaying && !_state.value.needsMorePages) {
                    Log.d("SpeechRecognition", "Continuing to next sentence...")
                    speakCurrentSentenceCollaborative()
                } else {
                    Log.d("SpeechRecognition", "NOT continuing: isPlaying=${_state.value.isPlaying}, needsMorePages=${_state.value.needsMorePages}")
                }
            }
        } else {
            // Failure
            playErrorSound()
            _state.value = current.copy(
                collaborativeState = CollaborativeState.FEEDBACK,
                lastAttemptSuccess = false,
                attemptCount = newAttemptCount
            )

            if (newAttemptCount >= 3) {
                // After 3 failures, TTS reads the correct word and gives 3 more tries
                viewModelScope.launch {
                    delay(500)
                    speakCorrectWordAndRetry(targetWord)
                }
            } else {
                // Try again
                viewModelScope.launch {
                    delay(500)
                    _state.value = _state.value.copy(
                        collaborativeState = CollaborativeState.IDLE,
                        lastAttemptSuccess = null  // Reset so highlight goes back to green
                    )
                    startSpeechRecognition()
                }
            }
        }
    }

    /**
     * TTS reads the correct word after 3 failed attempts, then gives user 3 more tries.
     * Uses cached audio from full sentence if available, playing only from the target word's timestamp.
     */
    private fun speakCorrectWordAndRetry(word: String) {
        if (!ttsService.isReady()) return

        val sentence = _state.value.currentSentence ?: return

        // Clear any previous highlight listener - we don't want position-based highlighting
        // for just the target word (positions would be wrong when playing partial)
        ttsService.setOnRangeStartListener { _, _ -> /* no-op */ }

        // Set flag to show yellow highlight on target word
        // Also reset lastAttemptSuccess so when listening starts, highlight is green (not red from last failure)
        _state.value = _state.value.copy(
            isSpeakingTargetWord = true,
            highlightRange = null,  // Clear any stale highlight range
            lastAttemptSuccess = null  // Reset so highlight will be green when listening starts
        )

        viewModelScope.launch {
            // Try to play the target word from the cached full sentence audio
            val firstWord = word.split(" ").first()
            val startFromMs = ttsCacheManager.findWordTimestamp(sentence.text, firstWord)

            val onComplete: () -> Unit = {
                viewModelScope.launch {
                    delay(300)
                    // Reset attempt count and let user try again after hearing pronunciation
                    _state.value = _state.value.copy(
                        collaborativeState = CollaborativeState.IDLE,
                        attemptCount = 0,
                        isSpeakingTargetWord = false
                    )
                    startSpeechRecognition()
                }
            }

            if (startFromMs != null) {
                // Play just the target word(s) from cached full sentence
                Log.d(TAG, "Speaking correction from cached audio starting at ${startFromMs}ms")
                val played = ttsService.speakCachedFrom(sentence.text, ttsCacheManager, startFromMs, onComplete)
                if (!played) {
                    // Cache disappeared? Fallback to speaking the word directly
                    Log.d(TAG, "Cache miss during correction, speaking word directly")
                    ttsService.speakWithKokoro(word, ttsCacheManager, onComplete)
                }
            } else {
                // Not cached - speak the word directly (will cache it separately)
                Log.d(TAG, "No timestamp found, speaking correction word directly")
                ttsService.speakWithKokoro(word, ttsCacheManager, onComplete)
            }
        }
    }

    /**
     * Handle speech recognition errors.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleSpeechError(message: String) {
        // Fall back to normal reading mode
        _state.value = _state.value.copy(
            collaborativeMode = false,
            collaborativeState = CollaborativeState.IDLE,
            targetWord = null
        )
    }

    /**
     * Cancel any active speech recognition.
     * Note: This cancels but doesn't destroy the recognizer to keep it warm for reuse.
     */
    fun cancelSpeechRecognition() {
        speechRecognizer?.cancel()  // Cancel but keep warm for faster restart
        _state.value = _state.value.copy(collaborativeState = CollaborativeState.IDLE)
    }

    private fun playSuccessSound() {
        soundPool?.play(successSoundId, 1f, 1f, 1, 0, 1f)
    }

    private fun playErrorSound() {
        soundPool?.play(errorSoundId, 1f, 1f, 1, 0, 1f)
    }

    override fun onCleared() {
        super.onCleared()
        ttsService.stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isRecognizerWarm = false
        soundPool?.release()
        soundPool = null
    }
}
