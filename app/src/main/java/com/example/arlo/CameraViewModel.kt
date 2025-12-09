package com.example.arlo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arlo.ml.ClaudeOCRService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ArloApplication).repository
    private val claudeOCR = ClaudeOCRService(application)
    private val apiKeyManager = ApiKeyManager(application)

    sealed class OCRResult {
        data class Success(val data: Any? = null) : OCRResult()
        data class Error(val message: String, val isApiKeyError: Boolean = false) : OCRResult()
    }

    /**
     * Extract title text from cover image using Claude OCR
     */
    fun extractTitleFromCover(imageUri: Uri, onResult: (String?, OCRResult) -> Unit) {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            onResult(null, OCRResult.Error("API key not configured", isApiKeyError = true))
            return
        }

        viewModelScope.launch {
            try {
                val title = withContext(Dispatchers.IO) {
                    claudeOCR.extractTitle(imageUri, apiKey)
                }
                withContext(Dispatchers.Main) {
                    onResult(title, OCRResult.Success())
                }
            } catch (e: ClaudeOCRService.InvalidApiKeyException) {
                withContext(Dispatchers.Main) {
                    onResult(null, OCRResult.Error("Invalid API key", isApiKeyError = true))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(null, OCRResult.Error(e.message ?: "OCR failed"))
                }
            }
        }
    }

    /**
     * Create a new book with cover image
     */
    fun createBookWithCover(title: String, coverImagePath: String, onResult: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val bookId = repository.createBook(title, coverImagePath)
                withContext(Dispatchers.Main) {
                    onResult(bookId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Add a page to existing book using Claude OCR with sentence extraction
     */
    fun addPageToBook(bookId: Long, imageUri: Uri, onResult: (OCRResult) -> Unit) {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            onResult(OCRResult.Error("API key not configured", isApiKeyError = true))
            return
        }

        viewModelScope.launch {
            try {
                val ocrResult = withContext(Dispatchers.IO) {
                    claudeOCR.extractSentences(imageUri, apiKey)
                }
                val nextPageNum = repository.getNextPageNumber(bookId)

                // Use new method that handles sentence continuation
                repository.addPageWithSentences(
                    bookId = bookId,
                    imagePath = imageUri.toString(),
                    pageNumber = nextPageNum,
                    sentences = ocrResult.sentences
                )

                withContext(Dispatchers.Main) {
                    onResult(OCRResult.Success())
                }
            } catch (e: ClaudeOCRService.InvalidApiKeyException) {
                withContext(Dispatchers.Main) {
                    onResult(OCRResult.Error("Invalid API key", isApiKeyError = true))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(OCRResult.Error(e.message ?: "Failed to extract text"))
                }
            }
        }
    }

    /**
     * Replace/re-capture a page with Claude OCR
     */
    fun replacePage(pageId: Long, imageUri: Uri, onResult: (OCRResult) -> Unit) {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            onResult(OCRResult.Error("API key not configured", isApiKeyError = true))
            return
        }

        viewModelScope.launch {
            try {
                val ocrResult = withContext(Dispatchers.IO) {
                    claudeOCR.extractSentences(imageUri, apiKey)
                }

                repository.updatePageWithSentences(
                    pageId = pageId,
                    imagePath = imageUri.toString(),
                    sentences = ocrResult.sentences
                )

                withContext(Dispatchers.Main) {
                    onResult(OCRResult.Success())
                }
            } catch (e: ClaudeOCRService.InvalidApiKeyException) {
                withContext(Dispatchers.Main) {
                    onResult(OCRResult.Error("Invalid API key", isApiKeyError = true))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(OCRResult.Error(e.message ?: "Failed to extract text"))
                }
            }
        }
    }

    /**
     * Delete a page
     */
    fun deletePage(pageId: Long, onResult: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deletePage(pageId)
                withContext(Dispatchers.Main) {
                    onResult()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
