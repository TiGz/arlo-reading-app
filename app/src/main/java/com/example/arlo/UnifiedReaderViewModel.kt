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
import java.util.Locale

/**
 * ViewModel for the Unified Reader that supports both
 * full-page scroll mode and sentence-by-sentence mode.
 * Both modes use sentencesJson as the data source.
 */
class UnifiedReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ArloApplication).repository
    val ttsService = (application as ArloApplication).ttsService
    private val ttsPreferences = TTSPreferences(application)

    // Speech recognition (inline per reviewer feedback - no separate service)
    private var speechRecognizer: SpeechRecognizer? = null
    private val isSpeechAvailable: Boolean
    val speechDiagnostics: String

    // Audio feedback (inline per reviewer feedback - no separate service)
    private var soundPool: SoundPool? = null
    private var successSoundId: Int = 0
    private var errorSoundId: Int = 0

    init {
        // Load and apply saved speech rate
        val savedRate = ttsPreferences.getSpeechRate()
        ttsService.setSpeechRate(savedRate)

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
        val readerMode: ReaderMode = ReaderMode.SENTENCE,
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
        val micLevel: Int = 0  // 0-100 mic input level for visual feedback
    ) {
        enum class ReaderMode { FULL_PAGE, SENTENCE }

        val currentPage: Page? get() = pages.getOrNull(currentPageIndex)
        val currentSentence: SentenceData? get() = sentences.getOrNull(currentSentenceIndex)
        val pageNumber: Int get() = currentPage?.pageNumber ?: 1
        val totalPages: Int get() = pages.size
        val sentenceNumber: Int get() = currentSentenceIndex + 1
        val totalSentences: Int get() = sentences.size

        // Full page text built from sentences
        val fullPageText: String get() = sentences.joinToString(" ") { it.text }

        val isLastSentenceIncomplete: Boolean get() =
            currentSentenceIndex == sentences.lastIndex &&
            currentSentence?.isComplete == false
    }

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var bookId: Long = -1L

    fun loadBook(bookId: Long) {
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
                    collaborativeMode = savedCollaborativeMode,
                    autoAdvance = savedAutoAdvance
                )
            } else {
                _state.value = ReaderState(
                    book = book,
                    pages = pages,
                    isLoading = false,
                    needsMorePages = pages.isEmpty(),
                    collaborativeMode = savedCollaborativeMode,
                    autoAdvance = savedAutoAdvance
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
     * Toggle between full-page and sentence reading modes.
     * Preserves current position.
     */
    fun toggleMode() {
        val newMode = when (_state.value.readerMode) {
            ReaderState.ReaderMode.FULL_PAGE -> ReaderState.ReaderMode.SENTENCE
            ReaderState.ReaderMode.SENTENCE -> ReaderState.ReaderMode.FULL_PAGE
        }
        ttsService.stop()
        _state.value = _state.value.copy(readerMode = newMode, isPlaying = false, highlightRange = null)
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
     * Set the TTS speech rate (0.25 to 1.0).
     */
    fun setSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(0.25f, 1.0f)
        ttsService.setSpeechRate(clampedRate)
        ttsPreferences.saveSpeechRate(clampedRate)
        _state.value = _state.value.copy(speechRate = clampedRate)
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
        _state.value = current.copy(
            currentPageIndex = pageIndex,
            currentSentenceIndex = 0,
            sentences = sentences,
            isPlaying = false,
            needsMorePages = false,
            highlightRange = null
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
                lastAttemptSuccess = null
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
                lastAttemptSuccess = null
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
                    highlightRange = null
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
            when (current.readerMode) {
                ReaderState.ReaderMode.FULL_PAGE -> speakFullPage()
                ReaderState.ReaderMode.SENTENCE -> {
                    if (current.collaborativeMode) {
                        speakCurrentSentenceCollaborative()
                    } else {
                        speakCurrentSentence()
                    }
                }
            }
            _state.value = current.copy(isPlaying = true)
        }
    }

    fun stopReading() {
        ttsService.stop()
        _state.value = _state.value.copy(isPlaying = false, highlightRange = null)
    }

    private fun speakFullPage() {
        val text = _state.value.fullPageText
        if (text.isBlank()) return

        if (ttsService.isReady()) {
            // Set up word highlighting callback
            ttsService.setOnRangeStartListener { start, end ->
                _state.value = _state.value.copy(highlightRange = Pair(start, end))
            }

            ttsService.speak(text) {
                // Callback when TTS finishes
                if (_state.value.isPlaying) {
                    // Move to next page if auto-advancing
                    val nextPageIndex = _state.value.currentPageIndex + 1
                    if (nextPageIndex < _state.value.pages.size) {
                        moveToPage(nextPageIndex)
                        speakFullPage()
                    } else {
                        _state.value = _state.value.copy(isPlaying = false, needsMorePages = true)
                    }
                }
            }
        }
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

            ttsService.speak(sentence.text) {
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
     */
    private fun normalizeWord(word: String): String {
        return word.lowercase()
            .trim()
            .replace(Regex("[.!?,;:\"'()\\[\\]]"), "")
    }

    /**
     * Check if spoken words match target.
     * Handles both single words and multi-word targets (e.g., "read it").
     */
    private fun isWordMatch(spokenResults: List<String>, targetWords: String): Boolean {
        val targetWordsList = targetWords.lowercase().split(" ").map { normalizeWord(it) }

        return spokenResults.any { spoken ->
            val spokenWordsList = spoken.lowercase().split(" ").map { normalizeWord(it) }

            // Check if all target words appear in order in the spoken result
            if (targetWordsList.size == 1) {
                // Single word - just check if it appears anywhere
                spokenWordsList.any { it == targetWordsList[0] }
            } else {
                // Multiple words - check if they appear in sequence
                val spokenJoined = spokenWordsList.joinToString(" ")
                val targetJoined = targetWordsList.joinToString(" ")
                spokenJoined.contains(targetJoined)
            }
        }
    }

    // Store target words outside of state to avoid race conditions with TTS callbacks
    private var currentTargetWords: String? = null

    /**
     * Speak current sentence in collaborative mode:
     * TTS reads all but the last word, then listens for user to speak it.
     */
    fun speakCurrentSentenceCollaborative() {
        val sentence = _state.value.currentSentence ?: return

        // Skip incomplete sentences - use normal TTS
        if (!sentence.isComplete && _state.value.currentSentenceIndex == _state.value.sentences.lastIndex) {
            speakCurrentSentence()
            return
        }

        val (targetWords, range) = extractTargetWords(sentence.text)
        Log.d("SpeechRecognition", "extractTargetWords: sentence='${sentence.text}', targetWords='$targetWords', range=$range")

        // Store target words locally AND in state (local copy avoids race conditions)
        currentTargetWords = targetWords
        _state.value = _state.value.copy(
            targetWord = targetWords,
            attemptCount = 0,
            lastAttemptSuccess = null,
            collaborativeState = CollaborativeState.IDLE,
            isPlaying = true
        )

        // Get text without target words
        val textWithoutTargetWords = sentence.text.substring(0, range.first).trim()

        if (textWithoutTargetWords.isEmpty()) {
            // Single word sentence - go straight to listening
            onPartialTTSComplete(targetWords)
        } else {
            // Speak partial sentence, then listen
            if (ttsService.isReady()) {
                // Set up word highlighting callback for collaborative mode too
                ttsService.setOnRangeStartListener { start, end ->
                    _state.value = _state.value.copy(highlightRange = Pair(start, end))
                }

                ttsService.speak(textWithoutTargetWords) {
                    // Clear highlight when done speaking partial sentence
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
        Log.d("SpeechRecognition", "onPartialTTSComplete: targetWords='$targetWords'")
        // Re-set targetWord in state in case it was lost to race conditions
        _state.value = _state.value.copy(targetWord = targetWords)
        viewModelScope.launch {
            // Small delay for audio buffer to clear
            delay(300)
            Log.d("SpeechRecognition", "After delay, before startSpeechRecognition: targetWord='${_state.value.targetWord}'")
            startSpeechRecognition()
        }
    }

    /**
     * Initialize and start speech recognition.
     */
    fun startSpeechRecognition() {
        if (!isSpeechAvailable) {
            handleSpeechError("Speech recognition not available")
            return
        }

        val context = getApplication<Application>()

        // Create recognizer if needed - explicitly use Google's speech recognizer on Fire tablets
        // to avoid SecurityException when Fire OS tries to bind to Amazon's Alexa service
        if (speechRecognizer == null) {
            speechRecognizer = try {
                // Try Google's speech recognizer first (required for Fire tablets with sideloaded Google app)
                val googleComponent = ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
                )
                SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
            } catch (e: Exception) {
                Log.w("SpeechRecognition", "Failed to create Google recognizer, falling back to default: ${e.message}")
                // Fall back to default (may not work on Fire tablets)
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("SpeechRecognition", "onReadyForSpeech")
                _state.value = _state.value.copy(collaborativeState = CollaborativeState.LISTENING)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList()
                Log.d("SpeechRecognition", "onResults: $matches")
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
                Log.e("SpeechRecognition", "onError: $errorName")

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Count as failed attempt
                        handleSpeechResults(emptyList())
                    }
                    else -> {
                        // Technical error - fall back to normal mode
                        handleSpeechError("Speech recognition error: $errorName")
                    }
                }
            }

            // Required but unused callbacks
            override fun onBeginningOfSpeech() {
                Log.d("SpeechRecognition", "onBeginningOfSpeech")
            }
            override fun onEndOfSpeech() {
                Log.d("SpeechRecognition", "onEndOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {
                // Convert RMS dB to 0-100 level for UI
                // RMS typically ranges from -2 to 10 dB
                val level = ((rmsdB + 2) / 12 * 100).toInt().coerceIn(0, 100)
                _state.value = _state.value.copy(micLevel = level)
            }
            override fun onBufferReceived(buffer: ByteArray?) {
                Log.d("SpeechRecognition", "onBufferReceived: ${buffer?.size} bytes")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList()
                Log.d("SpeechRecognition", "onPartialResults: $partial")

                // Check partial results for short words - they often don't make it to final results
                if (partial.isNotEmpty()) {
                    val targetWord = _state.value.targetWord
                    if (targetWord != null && isWordMatch(partial, targetWord)) {
                        // Found a match in partial results - accept it immediately
                        Log.d("SpeechRecognition", "Partial match found for '$targetWord' in: $partial")
                        speechRecognizer?.cancel()  // Stop listening
                        handleSpeechResults(partial)
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        // Create recognition intent
        // Note: Short words like "it" require minimal timing constraints
        val targetWord = _state.value.targetWord ?: ""
        Log.d("SpeechRecognition", "Starting recognition for target word: '$targetWord'")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)  // More results to find matches
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)  // Capture short words via partial results
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)  // Very short minimum (100ms)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)  // Slightly longer to capture full word
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }

        _state.value = _state.value.copy(collaborativeState = CollaborativeState.LISTENING, micLevel = 0)

        Log.d("SpeechRecognition", "About to call startListening...")
        try {
            speechRecognizer?.startListening(intent)
            Log.d("SpeechRecognition", "startListening called successfully")
        } catch (e: SecurityException) {
            Log.e("SpeechRecognition", "SecurityException starting listening: ${e.message}")
            handleSpeechError("Cannot access speech recognition service")
        } catch (e: Exception) {
            Log.e("SpeechRecognition", "Exception starting listening: ${e.message}")
            handleSpeechError("Speech recognition failed to start")
        }
    }

    /**
     * Handle speech recognition results.
     */
    private fun handleSpeechResults(results: List<String>) {
        val current = _state.value
        val targetWord = current.targetWord ?: return

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
                // After 3 failures, TTS reads the correct word and advances
                viewModelScope.launch {
                    delay(500)
                    speakCorrectWordAndAdvance(targetWord)
                }
            } else {
                // Try again
                viewModelScope.launch {
                    delay(500)
                    _state.value = _state.value.copy(collaborativeState = CollaborativeState.IDLE)
                    startSpeechRecognition()
                }
            }
        }
    }

    /**
     * TTS reads the correct word after 3 failed attempts, then advances.
     */
    private fun speakCorrectWordAndAdvance(word: String) {
        if (ttsService.isReady()) {
            ttsService.speak(word) {
                viewModelScope.launch {
                    delay(300)
                    nextSentence()
                    // Continue if still playing
                    if (_state.value.isPlaying && !_state.value.needsMorePages) {
                        speakCurrentSentenceCollaborative()
                    }
                }
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
     */
    fun cancelSpeechRecognition() {
        speechRecognizer?.cancel()
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
        soundPool?.release()
        soundPool = null
    }
}
