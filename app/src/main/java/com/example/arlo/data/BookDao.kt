package com.example.arlo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: Page): Long

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: Long): Book?

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY pageNumber ASC")
    fun getPagesForBook(bookId: Long): Flow<List<Page>>

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId")
    suspend fun getPageCount(bookId: Long): Int

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: Long)
}
