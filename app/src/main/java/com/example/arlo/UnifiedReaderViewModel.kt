package com.example.arlo

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private val isSpeechAvailable = SpeechRecognizer.isRecognitionAvailable(application)

    // Audio feedback (inline per reviewer feedback - no separate service)
    private var soundPool: SoundPool? = null
    private var successSoundId: Int = 0
    private var errorSoundId: Int = 0

    init {
        // Load and apply saved speech rate
        val savedRate = ttsPreferences.getSpeechRate()
        ttsService.setSpeechRate(savedRate)

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
        val readerMode: ReaderMode = ReaderMode.FULL_PAGE,
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
        val lastAttemptSuccess: Boolean? = null  // null = no attempt yet, true = success, false = failure
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
                    speechRate = ttsPreferences.getSpeechRate()
                )
            } else {
                _state.value = ReaderState(
                    book = book,
                    pages = pages,
                    isLoading = false,
                    needsMorePages = pages.isEmpty()
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
        _state.value = _state.value.copy(autoAdvance = !_state.value.autoAdvance)
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
            _state.value = current.copy(currentSentenceIndex = nextIndex, highlightRange = null)
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
            _state.value = current.copy(currentSentenceIndex = prevIndex, highlightRange = null)
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
            // Turning on
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
     * Extract last word from sentence for collaborative reading.
     * Returns Pair(lastWord, rangeInSentence) where range is character indices.
     */
    private fun extractLastWord(sentence: String): Pair<String, IntRange> {
        val trimmed = sentence.trim()
        val lastSpaceIndex = trimmed.lastIndexOf(' ')

        return if (lastSpaceIndex == -1) {
            // Single word sentence
            Pair(trimmed, 0 until trimmed.length)
        } else {
            val lastWord = trimmed.substring(lastSpaceIndex + 1)
            Pair(lastWord, (lastSpaceIndex + 1) until trimmed.length)
        }
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
     * Check if spoken word matches target.
     * Per reviewer feedback: use Android's confidence-ranked results,
     * just check if any match (no Levenshtein needed).
     */
    private fun isWordMatch(spokenResults: List<String>, targetWord: String): Boolean {
        val normalizedTarget = normalizeWord(targetWord)
        return spokenResults.any { spoken ->
            // Check each word in the spoken result (in case user said extra words)
            spoken.lowercase().split(" ").any { word ->
                normalizeWord(word) == normalizedTarget
            }
        }
    }

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

        val (lastWord, range) = extractLastWord(sentence.text)

        // Store target word for matching
        _state.value = _state.value.copy(
            targetWord = lastWord,
            attemptCount = 0,
            lastAttemptSuccess = null,
            collaborativeState = CollaborativeState.IDLE,
            isPlaying = true
        )

        // Get text without last word
        val textWithoutLastWord = sentence.text.substring(0, range.first).trim()

        if (textWithoutLastWord.isEmpty()) {
            // Single word sentence - go straight to listening
            onPartialTTSComplete()
        } else {
            // Speak partial sentence, then listen
            if (ttsService.isReady()) {
                ttsService.speak(textWithoutLastWord) {
                    onPartialTTSComplete()
                }
            }
        }
    }

    /**
     * Called when TTS finishes reading partial sentence.
     * Starts listening for user's speech.
     */
    private fun onPartialTTSComplete() {
        viewModelScope.launch {
            // Small delay for audio buffer to clear
            delay(300)
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

        // Create recognizer if needed
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = _state.value.copy(collaborativeState = CollaborativeState.LISTENING)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList()
                handleSpeechResults(matches)
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Count as failed attempt
                        handleSpeechResults(emptyList())
                    }
                    else -> {
                        // Technical error - fall back to normal mode
                        handleSpeechError("Speech recognition error")
                    }
                }
            }

            // Required but unused callbacks
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        // Create recognition intent
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        _state.value = _state.value.copy(collaborativeState = CollaborativeState.LISTENING)
        speechRecognizer?.startListening(intent)
    }

    /**
     * Handle speech recognition results.
     */
    private fun handleSpeechResults(results: List<String>) {
        val current = _state.value
        val targetWord = current.targetWord ?: return

        val isMatch = isWordMatch(results, targetWord)
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
                nextSentence()
                // Continue if still playing
                if (_state.value.isPlaying && !_state.value.needsMorePages) {
                    speakCurrentSentenceCollaborative()
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
