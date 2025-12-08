package com.example.arlo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arlo.data.Book
import com.example.arlo.data.Page
import com.example.arlo.data.SentenceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Unified Reader that supports both
 * full-page scroll mode and sentence-by-sentence mode.
 * Both modes use sentencesJson as the data source.
 */
class UnifiedReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ArloApplication).repository
    val ttsService = (application as ArloApplication).ttsService

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
        val highlightRange: Pair<Int, Int>? = null  // Word highlight start/end offsets for TTS
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
                    isLoading = false
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
            _state.value = current.copy(isPlaying = false)
        } else {
            // Start playing
            when (current.readerMode) {
                ReaderState.ReaderMode.FULL_PAGE -> speakFullPage()
                ReaderState.ReaderMode.SENTENCE -> speakCurrentSentence()
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

    override fun onCleared() {
        super.onCleared()
        ttsService.stop()
    }
}
