package com.example.arlo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.arlo.data.Book
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ArloApplication).repository

    val allBooksWithInfo: LiveData<List<BookWithInfo>> = repository.allBooks
        .map { books ->
            books.map { book ->
                val pageCount = repository.getPageCount(book.id)
                BookWithInfo(book, pageCount)
            }
        }
        .asLiveData()

    // Keep simple books list for backward compatibility if needed
    val allBooks = repository.allBooks.asLiveData()

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            repository.deleteBook(bookId)
        }
    }
}
