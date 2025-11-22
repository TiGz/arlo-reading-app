package com.example.arlo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arlo.data.BookRepository
import com.example.arlo.ml.OCRService
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ArloApplication).repository
    private val ocrService = OCRService(application)

    fun createBook(imageUri: Uri, onResult: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                // 1. Run OCR
                val text = ocrService.processImage(imageUri)

                // 2. Create Book
                val bookId = repository.createBook("Scanned Book ${System.currentTimeMillis()}")

                // 3. Save Page (Page 1)
                repository.addPage(bookId, text, imageUri.toString(), 1)

                onResult(bookId)
            } catch (e: Exception) {
                e.printStackTrace()
                // Handle error
            }
        }
    }

    fun addPageToBook(bookId: Long, imageUri: Uri, onResult: () -> Unit) {
        viewModelScope.launch {
            try {
                // 1. Run OCR
                val text = ocrService.processImage(imageUri)

                // 2. Get current page count to determine new page number
                // For MVP, we might need a way to get max page number. 
                // A simple query or counting existing pages would work.
                // Let's assume we can get the last page number or just count.
                // For now, let's just use a timestamp or auto-increment if possible, 
                // but our Page entity has pageNumber.
                // Let's add a method to repo to get next page number.
                val nextPageNum = repository.getNextPageNumber(bookId)

                // 3. Save Page
                repository.addPage(bookId, text, imageUri.toString(), nextPageNum)

                onResult()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
