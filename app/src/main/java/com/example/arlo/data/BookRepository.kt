package com.example.arlo.data

import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {

    val allBooks: Flow<List<Book>> = bookDao.getAllBooks()

    suspend fun createBook(title: String): Long {
        val book = Book(title = title)
        return bookDao.insertBook(book)
    }

    suspend fun addPage(bookId: Long, text: String, imagePath: String, pageNumber: Int) {
        val page = Page(bookId = bookId, text = text, imagePath = imagePath, pageNumber = pageNumber)
        bookDao.insertPage(page)
    }

    fun getPages(bookId: Long): Flow<List<Page>> {
        return bookDao.getPagesForBook(bookId)
    }
    
    suspend fun getNextPageNumber(bookId: Long): Int {
        return bookDao.getPageCount(bookId) + 1
    }
    
    suspend fun getBook(bookId: Long): Book? {
        return bookDao.getBook(bookId)
    }
}
