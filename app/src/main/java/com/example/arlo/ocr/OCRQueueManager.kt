package com.example.arlo.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.arlo.ApiKeyManager
import com.example.arlo.data.BookDao
import com.example.arlo.data.Page
import com.example.arlo.data.SentenceData
import com.example.arlo.ml.ClaudeOCRService
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
    private val apiKeyManager: ApiKeyManager
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

            // Work with mutable list for potential merging
            val sentences = result.sentences.toMutableList()

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
            val detectedPageNumber = result.detectedPageNumber

            // Update page with OCR result
            bookDao.updatePageWithOCRResult(
                pageId = page.id,
                text = fullText,
                json = sentencesJson,
                complete = lastComplete,
                detectedPageNum = detectedPageNumber
            )

            Log.d(TAG, "Successfully processed page ${page.id}: ${sentences.size} sentences, detected page number: $detectedPageNumber")

            // Check for missing pages based on detected page numbers
            if (detectedPageNumber != null && page.pageNumber > 1) {
                val prevPage = bookDao.getPageByNumber(page.bookId, page.pageNumber - 1)
                val prevDetected = prevPage?.detectedPageNumber
                if (prevDetected != null && detectedPageNumber > prevDetected + 1) {
                    Log.w(TAG, "Missing pages detected: expected ${prevDetected + 1}, got $detectedPageNumber")
                    _queueState.value = QueueState.MissingPages(
                        bookId = page.bookId,
                        expectedPageNum = prevDetected + 1,
                        detectedPageNum = detectedPageNumber
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing page ${page.id}", e)
            handleProcessingError(page, e)
        }
    }

    private suspend fun handleProcessingError(page: Page, error: Exception) {
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

    companion object {
        private const val TAG = "OCRQueueManager"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 2000L
    }
}
