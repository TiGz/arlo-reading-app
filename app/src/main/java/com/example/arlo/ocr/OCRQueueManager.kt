package com.example.arlo.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.arlo.ApiKeyManager
import com.example.arlo.data.BookDao
import com.example.arlo.data.Page
import com.example.arlo.ml.ClaudeOCRService
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

    private val _queueState = MutableStateFlow<QueueState>(QueueState.Idle)
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    val pendingPages: Flow<List<Page>> = bookDao.getProcessingPages()

    private var isProcessing = false

    sealed class QueueState {
        object Idle : QueueState()
        data class Processing(val pageId: Long, val bookId: Long) : QueueState()
        data class Error(val pageId: Long, val message: String) : QueueState()
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

            // Build sentences JSON
            val sentencesJson = result.sentences.let { sentences ->
                val gson = com.google.gson.Gson()
                gson.toJson(sentences)
            }

            val lastComplete = result.sentences.lastOrNull()?.isComplete ?: true

            // Update page with OCR result
            bookDao.updatePageWithOCRResult(
                pageId = page.id,
                text = result.fullText,
                json = sentencesJson,
                complete = lastComplete
            )

            Log.d(TAG, "Successfully processed page ${page.id}: ${result.sentences.size} sentences")

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

    companion object {
        private const val TAG = "OCRQueueManager"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 2000L
    }
}
