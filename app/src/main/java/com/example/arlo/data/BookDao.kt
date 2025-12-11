package com.example.arlo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY pageNumber ASC")
    suspend fun getPagesForBookSync(bookId: Long): List<Page>

    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPage(pageId: Long): Page?

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND pageNumber = :pageNumber")
    suspend fun getPageByNumber(bookId: Long, pageNumber: Int): Page?

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId")
    suspend fun getPageCount(bookId: Long): Int

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: Long)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePage(pageId: Long)

    @Query("UPDATE books SET coverImagePath = :coverPath WHERE id = :bookId")
    suspend fun updateBookCover(bookId: Long, coverPath: String)

    @Query("UPDATE books SET title = :title WHERE id = :bookId")
    suspend fun updateBookTitle(bookId: Long, title: String)

    @Query("UPDATE books SET lastReadPageNumber = :pageNumber WHERE id = :bookId")
    suspend fun updateLastReadPage(bookId: Long, pageNumber: Int)

    @Query("UPDATE books SET lastReadPageNumber = :pageNumber, lastReadSentenceIndex = :sentenceIndex WHERE id = :bookId")
    suspend fun updateLastReadPosition(bookId: Long, pageNumber: Int, sentenceIndex: Int)

    @Query("UPDATE pages SET text = :text, imagePath = :imagePath WHERE id = :pageId")
    suspend fun updatePage(pageId: Long, text: String, imagePath: String)

    @Query("UPDATE pages SET sentencesJson = :sentencesJson, lastSentenceComplete = :lastSentenceComplete WHERE id = :pageId")
    suspend fun updatePageSentences(pageId: Long, sentencesJson: String?, lastSentenceComplete: Boolean)

    @Query("UPDATE pages SET text = :text, imagePath = :imagePath, sentencesJson = :sentencesJson, lastSentenceComplete = :lastSentenceComplete WHERE id = :pageId")
    suspend fun updatePageFull(pageId: Long, text: String, imagePath: String, sentencesJson: String?, lastSentenceComplete: Boolean)

    // Legacy book detection - books with no pages that have sentencesJson
    @Query("""
        SELECT b.* FROM books b
        WHERE NOT EXISTS (
            SELECT 1 FROM pages p
            WHERE p.bookId = b.id AND p.sentencesJson IS NOT NULL
        )
    """)
    suspend fun getBooksWithoutSentences(): List<Book>

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteBooks(bookIds: List<Long>)

    // OCR Queue management
    @Query("SELECT * FROM pages WHERE processingStatus IN ('PENDING', 'PROCESSING') ORDER BY id ASC")
    fun getProcessingPages(): Flow<List<Page>>

    @Query("SELECT * FROM pages WHERE processingStatus = 'PENDING' ORDER BY id ASC LIMIT 1")
    suspend fun getNextPendingPage(): Page?

    @Query("UPDATE pages SET processingStatus = :status, errorMessage = :error WHERE id = :pageId")
    suspend fun updateProcessingStatus(pageId: Long, status: String, error: String? = null)

    @Query("UPDATE pages SET processingStatus = :status, retryCount = :retryCount, errorMessage = :error WHERE id = :pageId")
    suspend fun updateProcessingStatusWithRetry(pageId: Long, status: String, retryCount: Int, error: String? = null)

    @Query("UPDATE pages SET text = :text, sentencesJson = :json, lastSentenceComplete = :complete, detectedPageNumber = :detectedPageNum, processingStatus = 'COMPLETED' WHERE id = :pageId")
    suspend fun updatePageWithOCRResult(pageId: Long, text: String, json: String, complete: Boolean, detectedPageNum: Int? = null)

    @Query("UPDATE pages SET pageNumber = :pageNumber WHERE id = :pageId")
    suspend fun updatePageNumber(pageId: Long, pageNumber: Int)

    // Get completed pages only for reader
    @Query("SELECT * FROM pages WHERE bookId = :bookId AND processingStatus = 'COMPLETED' ORDER BY pageNumber ASC")
    fun getCompletedPagesForBook(bookId: Long): Flow<List<Page>>

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND processingStatus = 'COMPLETED' ORDER BY pageNumber ASC")
    suspend fun getCompletedPagesForBookSync(bookId: Long): List<Page>

    // Get pending count for a book
    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId AND processingStatus IN ('PENDING', 'PROCESSING')")
    fun getPendingPageCount(bookId: Long): Flow<Int>
}
