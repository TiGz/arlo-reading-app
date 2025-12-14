package com.example.arlo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arlo.data.AppDatabase
import com.example.arlo.data.Page
import com.example.arlo.ocr.OCRQueueManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PageReviewViewModel(application: Application) : AndroidViewModel(application) {

    private val bookDao = AppDatabase.getDatabase(application).bookDao()

    private var _bookId: Long = -1L
    val bookId: Long get() = _bookId

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    fun setBookId(bookId: Long) {
        _bookId = bookId
    }

    fun getPagesForBook(): Flow<List<Page>> {
        return bookDao.getPagesForBook(_bookId)
    }

    fun setCurrentPageIndex(index: Int) {
        _currentPageIndex.value = index
    }

    suspend fun prepareForRecapture(pageId: Long) {
        bookDao.resetPageForRecapture(pageId)
    }
}
