package com.example.arlo.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.arlo.ApiKeyManager
import com.example.arlo.data.BookDao
import com.example.arlo.data.Page
import com.example.arlo.data.SentenceData
import com.example.arlo.ml.ClaudeOCRService
import com.example.arlo.tts.TTSCacheManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages background OCR processing queue.
 * - Pages are queued with PENDING status
 * - Processes one page at a time
 * - Retries failed pages up to MAX_RETRIES with exponential backoff
 * - Queue state is persisted in Room (survives app restart)
 */
class OCRQueueManager(
    private val context: Context,
    private val bookDao: BookDao,
    private val claudeOCR: ClaudeOCRService,
    private val apiKeyManager: ApiKeyManager,
    private val ttsCacheManager: TTSCacheManager? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private val _queueState = MutableStateFlow<QueueState>(QueueState.Idle)
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    val pendingPages: Flow<List<Page>> = bookDao.getProcessingPages()

    private var isProcessing = false

    sealed class QueueState {
        object Idle : QueueState()
        data class Processing(val pageId: Long, val bookId: Long) : QueueState()
        data class Error(val pageId: Long, val message: String) : QueueState()
        data class MissingPages(val bookId: Long, val expectedPageNum: Int, val detectedPageNum: Int) : QueueState()
        data class InsufficientCredits(val message: String) : QueueState()

        // Feedback states for UI
        data class LowConfidence(
            val bookId: Long,
            val pageNumber: Int?,  // null if first page with no detected number
            val confidence: Float
        ) : QueueState()

        data class PagesProcessed(
            val bookId: Long,
            val pageNumbers: List<Int?>,  // detected page numbers (may be null)
            val nextExpectedPage: Int?    // for UI to show "Capture page X next"
        ) : QueueState()
    }

    init {
        // Start processing any pending pages from previous sessions
        startProcessingIfNeeded()
    }

    /**
     * Queue a page for OCR processing.
     * Returns the page ID.
     */
    suspend fun queuePage(bookId: Long, imagePath: String, pageNumber: Int): Long {
        val page = Page(
            bookId = bookId,
            imagePath = imagePath,
            pageNumber = pageNumber,
            text = "",
            processingStatus = "PENDING"
        )
        val pageId = bookDao.insertPage(page)
        Log.d(TAG, "Queued page $pageId for book $bookId")
        startProcessingIfNeeded()
        return pageId
    }

    /**
     * Start processing queue if not already running.
     */
    fun startProcessingIfNeeded() {
        if (isProcessing) return

        scope.launch {
            processQueue()
        }
    }

    private suspend fun processQueue() {
        if (isProcessing) return
        isProcessing = true

        try {
            while (true) {
                val nextPage = bookDao.getNextPendingPage()
                if (nextPage == null) {
                    _queueState.value = QueueState.Idle
                    break
                }

                processPage(nextPage)
            }
        } finally {
            isProcessing = false
        }
    }

    private suspend fun processPage(page: Page) {
        Log.d(TAG, "Processing page ${page.id} for book ${page.bookId}")
        _queueState.value = QueueState.Processing(page.id, page.bookId)

        // Mark as PROCESSING
        bookDao.updateProcessingStatus(page.id, "PROCESSING")

        try {
            val apiKey = apiKeyManager.getApiKey()
            if (apiKey.isNullOrEmpty()) {
                throw IllegalStateException("No API key available")
            }

            val imageUri = Uri.fromFile(File(page.imagePath))
            val result = claudeOCR.extractSentences(imageUri, apiKey)

            val processedPageNumbers = mutableListOf<Int?>()
            var lowestConfidence = 1.0f
            var lowestConfidencePage: Int? = null

            // Process each page in the result
            result.pages.forEachIndexed { index, pageResult ->
                val sequentialPageNum = page.pageNumber + index

                if (index == 0) {
                    // First page updates the queued page entity
                    processFirstPageResult(page, pageResult)
                } else {
                    // Additional pages create new Page entities
                    createAdditionalPage(page.bookId, pageResult, sequentialPageNum)
                }

                // Extract numeric page number from label for tracking (null if roman numeral or no label)
                val numericPage = pageResult.pageLabel?.toIntOrNull()
                processedPageNumbers.add(numericPage)

                // Track lowest confidence
                if (pageResult.confidence < lowestConfidence) {
                    lowestConfidence = pageResult.confidence
                    lowestConfidencePage = numericPage ?: sequentialPageNum
                }

                // Queue TTS pre-caching for each page
                ttsCacheManager?.queueSentencesForCaching(
                    pageResult.sentences.map { it.text },
                    page.id + index
                )
            }

            Log.d(TAG, "Successfully processed ${result.pages.size} page(s) from capture, pages: $processedPageNumbers")

            // Emit low confidence warning if any page below threshold
            if (lowestConfidence < LOW_CONFIDENCE_THRESHOLD) {
                _queueState.value = QueueState.LowConfidence(
                    bookId = page.bookId,
                    pageNumber = lowestConfidencePage,
                    confidence = lowestConfidence
                )
            }

            // Calculate next expected page number (only works for numeric labels)
            val lastDetectedPage = result.pages.lastOrNull()?.pageLabel?.toIntOrNull()
            val nextExpected = lastDetectedPage?.plus(1)

            // Emit pages processed feedback
            _queueState.value = QueueState.PagesProcessed(
                bookId = page.bookId,
                pageNumbers = processedPageNumbers,
                nextExpectedPage = nextExpected
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing page ${page.id}", e)
            handleProcessingError(page, e)
        }
    }

    private suspend fun processFirstPageResult(page: Page, pageResult: ClaudeOCRService.PageOCRResult) {
        // Work with mutable list for potential merging
        val sentences = pageResult.sentences.toMutableList()

        // Handle sentence continuation from previous page
        if (page.pageNumber > 1 && sentences.isNotEmpty()) {
            val prevPage = bookDao.getPageByNumber(page.bookId, page.pageNumber - 1)
            if (prevPage != null &&
                prevPage.processingStatus == "COMPLETED" &&
                !prevPage.lastSentenceComplete) {

                val prevSentences = parseSentences(prevPage.sentencesJson)
                if (prevSentences.isNotEmpty()) {
                    val lastPrev = prevSentences.last()
                    val firstNew = sentences.first()

                    // Merge: prepend previous fragment to first new sentence
                    Log.d(TAG, "Merging sentences: '${lastPrev.text}' + '${firstNew.text}'")
                    sentences[0] = SentenceData(
                        text = "${lastPrev.text} ${firstNew.text}".trim(),
                        isComplete = firstNew.isComplete
                    )

                    // Remove fragment from previous page
                    val updatedPrevSentences = prevSentences.dropLast(1)
                    val prevFullText = updatedPrevSentences.joinToString(" ") { it.text }
                    val prevLastComplete = updatedPrevSentences.lastOrNull()?.isComplete ?: true

                    bookDao.updatePageFull(
                        prevPage.id,
                        prevFullText,
                        prevPage.imagePath,
                        toJson(updatedPrevSentences),
                        prevLastComplete
                    )
                    Log.d(TAG, "Updated previous page ${prevPage.id}, removed fragment")
                }
            }
        }

        // Build final JSON and text
        val sentencesJson = toJson(sentences)
        val fullText = sentences.joinToString(" ") { it.text }
        val lastComplete = sentences.lastOrNull()?.isComplete ?: true

        // Resolve chapter from this page or previous pages
        val resolvedChapter = resolveChapterForPage(
            bookId = page.bookId,
            pageId = page.id,
            detectedChapterTitle = pageResult.chapterTitle
        )

        // Update page with OCR result including resolved chapter
        bookDao.updatePageWithOCRResultAndChapter(
            pageId = page.id,
            text = fullText,
            json = sentencesJson,
            complete = lastComplete,
            pageLabel = pageResult.pageLabel,
            chapterTitle = pageResult.chapterTitle,
            resolvedChapter = resolvedChapter,
            confidence = pageResult.confidence
        )

        Log.d(TAG, "Processed first page ${page.id}: ${sentences.size} sentences, pageLabel: ${pageResult.pageLabel}, chapter: ${pageResult.chapterTitle}, resolvedChapter: $resolvedChapter, confidence: ${pageResult.confidence}")

        // Check for missing pages based on detected page labels (only for numeric labels)
        val currentPageNum = pageResult.pageLabel?.toIntOrNull()
        if (currentPageNum != null && page.pageNumber > 1) {
            val prevPage = bookDao.getPageByNumber(page.bookId, page.pageNumber - 1)
            val prevDetected = prevPage?.detectedPageLabel?.toIntOrNull()
            if (prevDetected != null && currentPageNum > prevDetected + 1) {
                Log.w(TAG, "Missing pages detected: expected ${prevDetected + 1}, got $currentPageNum")
                _queueState.value = QueueState.MissingPages(
                    bookId = page.bookId,
                    expectedPageNum = prevDetected + 1,
                    detectedPageNum = currentPageNum
                )
            }
        }
    }

    /**
     * Resolve the chapter for a page by looking at the detected chapter title
     * or by looking backward through previous pages.
     */
    private suspend fun resolveChapterForPage(
        bookId: Long,
        pageId: Long,
        detectedChapterTitle: String?
    ): String? {
        // 1. If this page has a valid chapter title, use it
        if (detectedChapterTitle != null && isValidChapterTitle(detectedChapterTitle, bookId)) {
            Log.d(TAG, "Using detected chapter title: $detectedChapterTitle")
            return detectedChapterTitle
        }

        // 2. Look backward through previous pages to find the most recent chapter
        val previousPages = bookDao.getPagesBeforePage(bookId, pageId)

        for (page in previousPages) {
            // Check if this page has a detected chapter title that's valid
            if (page.chapterTitle != null && isValidChapterTitle(page.chapterTitle, bookId)) {
                Log.d(TAG, "Resolved chapter from page ${page.id}: ${page.chapterTitle}")
                return page.chapterTitle
            }
            // Also check if it has a resolved chapter (from earlier inference)
            if (page.resolvedChapter != null) {
                Log.d(TAG, "Resolved chapter from previous inference on page ${page.id}: ${page.resolvedChapter}")
                return page.resolvedChapter
            }
        }

        // 3. No chapter found - return null (will be treated as "default chapter")
        Log.d(TAG, "No chapter found for page $pageId in book $bookId")
        return null
    }

    /**
     * Determine if a chapter title is valid (not the book title, not too short, not just a number).
     */
    private suspend fun isValidChapterTitle(title: String, bookId: Long): Boolean {
        val book = bookDao.getBook(bookId) ?: return true

        // Don't use book title as chapter title
        if (title.equals(book.title, ignoreCase = true)) {
            Log.d(TAG, "Rejecting chapter title '$title' - matches book title")
            return false
        }

        // Very short strings (< 3 chars) unlikely to be chapters
        if (title.length < 3) {
            Log.d(TAG, "Rejecting chapter title '$title' - too short")
            return false
        }

        // Strings that are just numbers might be page numbers, not chapters
        if (title.all { it.isDigit() }) {
            Log.d(TAG, "Rejecting chapter title '$title' - all digits")
            return false
        }

        return true
    }

    private suspend fun createAdditionalPage(bookId: Long, pageResult: ClaudeOCRService.PageOCRResult, sequentialNum: Int) {
        // First insert the page to get an ID
        val newPage = Page(
            bookId = bookId,
            imagePath = "",  // No separate image for multi-page captures
            pageNumber = sequentialNum,
            text = pageResult.fullText,
            sentencesJson = toJson(pageResult.sentences),
            lastSentenceComplete = pageResult.sentences.lastOrNull()?.isComplete ?: true,
            detectedPageLabel = pageResult.pageLabel,
            chapterTitle = pageResult.chapterTitle,
            confidence = pageResult.confidence,
            processingStatus = "COMPLETED"
        )
        val newPageId = bookDao.insertPage(newPage)

        // Now resolve chapter for this page (needs the page to exist for backward lookup)
        val resolvedChapter = resolveChapterForPage(
            bookId = bookId,
            pageId = newPageId,
            detectedChapterTitle = pageResult.chapterTitle
        )

        // Update with resolved chapter
        if (resolvedChapter != null) {
            bookDao.updatePageWithOCRResultAndChapter(
                pageId = newPageId,
                text = pageResult.fullText,
                json = toJson(pageResult.sentences),
                complete = pageResult.sentences.lastOrNull()?.isComplete ?: true,
                pageLabel = pageResult.pageLabel,
                chapterTitle = pageResult.chapterTitle,
                resolvedChapter = resolvedChapter,
                confidence = pageResult.confidence
            )
        }

        Log.d(TAG, "Created additional page $newPageId for book $bookId: sequential=$sequentialNum, pageLabel=${pageResult.pageLabel}, chapter=${pageResult.chapterTitle}, resolvedChapter=$resolvedChapter, confidence=${pageResult.confidence}")
    }

    private suspend fun handleProcessingError(page: Page, error: Exception) {
        // Check for non-retryable errors first
        when (error) {
            is ClaudeOCRService.InsufficientCreditsException -> {
                Log.e(TAG, "Insufficient credits - marking page ${page.id} as failed, no retry")
                bookDao.updateProcessingStatusWithRetry(
                    page.id,
                    "FAILED",
                    page.retryCount,
                    error.message
                )
                _queueState.value = QueueState.InsufficientCredits(
                    error.message ?: "API credits exhausted"
                )
                return
            }
            is ClaudeOCRService.InvalidApiKeyException -> {
                Log.e(TAG, "Invalid API key - marking page ${page.id} as failed, no retry")
                bookDao.updateProcessingStatusWithRetry(
                    page.id,
                    "FAILED",
                    page.retryCount,
                    error.message
                )
                _queueState.value = QueueState.Error(page.id, error.message ?: "Invalid API key")
                return
            }
        }

        // Retryable errors
        val newRetryCount = page.retryCount + 1

        if (newRetryCount <= MAX_RETRIES) {
            // Schedule for retry
            Log.d(TAG, "Will retry page ${page.id}, attempt $newRetryCount of $MAX_RETRIES")
            bookDao.updateProcessingStatusWithRetry(
                page.id,
                "PENDING",
                newRetryCount,
                error.message
            )

            // Exponential backoff: 2s, 4s, 8s
            val delayMs = RETRY_BASE_DELAY_MS * (1 shl (newRetryCount - 1))
            delay(delayMs)
        } else {
            // Mark as failed
            Log.e(TAG, "Page ${page.id} failed after $MAX_RETRIES attempts")
            bookDao.updateProcessingStatusWithRetry(
                page.id,
                "FAILED",
                newRetryCount,
                error.message
            )
            _queueState.value = QueueState.Error(page.id, error.message ?: "Unknown error")
        }
    }

    // JSON helper methods for sentence parsing
    private fun parseSentences(json: String?): List<SentenceData> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SentenceData>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sentences JSON", e)
            emptyList()
        }
    }

    private fun toJson(sentences: List<SentenceData>): String {
        return gson.toJson(sentences)
    }

    /**
     * Prepare a page for recapture by resetting its processing state.
     */
    suspend fun prepareForRecapture(pageId: Long) {
        bookDao.resetPageForRecapture(pageId)
        Log.d(TAG, "Page $pageId prepared for recapture")
    }

    /**
     * Queue a recapture of an existing page with a new image.
     */
    suspend fun queueRecapture(pageId: Long, newImagePath: String) {
        val oldPage = bookDao.getPageById(pageId)
        if (oldPage != null && oldPage.imagePath.isNotEmpty()) {
            // Delete old image file
            File(oldPage.imagePath).delete()
            Log.d(TAG, "Deleted old image for page $pageId")
        }

        // Update page with new image path
        bookDao.updatePageImage(pageId, newImagePath)
        Log.d(TAG, "Queued recapture for page $pageId with new image")

        startProcessingIfNeeded()
    }

    companion object {
        private const val TAG = "OCRQueueManager"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 2000L
        private const val LOW_CONFIDENCE_THRESHOLD = 0.7f
    }
}
