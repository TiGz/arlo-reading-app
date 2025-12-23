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

    // OCR Queue management
    @Query("SELECT * FROM pages WHERE processingStatus IN ('PENDING', 'PROCESSING') ORDER BY id ASC")
    fun getProcessingPages(): Flow<List<Page>>

    @Query("SELECT * FROM pages WHERE processingStatus = 'PENDING' ORDER BY id ASC LIMIT 1")
    suspend fun getNextPendingPage(): Page?

    @Query("UPDATE pages SET processingStatus = :status, errorMessage = :error WHERE id = :pageId")
    suspend fun updateProcessingStatus(pageId: Long, status: String, error: String? = null)

    @Query("UPDATE pages SET processingStatus = :status, retryCount = :retryCount, errorMessage = :error WHERE id = :pageId")
    suspend fun updateProcessingStatusWithRetry(pageId: Long, status: String, retryCount: Int, error: String? = null)

    @Query("UPDATE pages SET text = :text, sentencesJson = :json, lastSentenceComplete = :complete, detectedPageLabel = :pageLabel, chapterTitle = :chapterTitle, confidence = :confidence, processingStatus = 'COMPLETED' WHERE id = :pageId")
    suspend fun updatePageWithOCRResult(pageId: Long, text: String, json: String, complete: Boolean, pageLabel: String? = null, chapterTitle: String? = null, confidence: Float = 1.0f)

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

    // Get pending count synchronously
    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId AND processingStatus IN ('PENDING', 'PROCESSING')")
    suspend fun getPendingPageCountSync(bookId: Long): Int

    // Get last completed page's detected page label
    @Query("SELECT detectedPageLabel FROM pages WHERE bookId = :bookId AND processingStatus = 'COMPLETED' ORDER BY pageNumber DESC LIMIT 1")
    suspend fun getLastCompletedPageLabel(bookId: Long): String?

    // Page recapture support
    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPageById(pageId: Long): Page?

    @Query("""
        UPDATE pages SET
            processingStatus = 'PENDING',
            text = '',
            sentencesJson = NULL,
            confidence = 0.0,
            retryCount = 0,
            errorMessage = NULL
        WHERE id = :pageId
    """)
    suspend fun resetPageForRecapture(pageId: Long)

    @Query("UPDATE pages SET imagePath = :imagePath WHERE id = :pageId")
    suspend fun updatePageImage(pageId: Long, imagePath: String)

    // ==================== CHAPTER INFERENCE ====================

    /**
     * Get all pages before a given page (for backward chapter inference).
     */
    @Query("""
        SELECT * FROM pages
        WHERE bookId = :bookId AND pageNumber < (SELECT pageNumber FROM pages WHERE id = :pageId)
        ORDER BY pageNumber DESC
    """)
    suspend fun getPagesBeforePage(bookId: Long, pageId: Long): List<Page>

    /**
     * Update page with OCR result including resolved chapter.
     */
    @Query("""
        UPDATE pages SET
            text = :text,
            sentencesJson = :json,
            lastSentenceComplete = :complete,
            detectedPageLabel = :pageLabel,
            chapterTitle = :chapterTitle,
            resolvedChapter = :resolvedChapter,
            confidence = :confidence,
            processingStatus = 'COMPLETED'
        WHERE id = :pageId
    """)
    suspend fun updatePageWithOCRResultAndChapter(
        pageId: Long,
        text: String,
        json: String,
        complete: Boolean,
        pageLabel: String? = null,
        chapterTitle: String? = null,
        resolvedChapter: String? = null,
        confidence: Float = 1.0f
    )

    /**
     * Get all distinct chapters in a book (from resolvedChapter field), in page order.
     */
    @Query("""
        SELECT resolvedChapter
        FROM pages
        WHERE bookId = :bookId AND resolvedChapter IS NOT NULL
        GROUP BY resolvedChapter
        ORDER BY MIN(pageNumber)
    """)
    suspend fun getChaptersForBook(bookId: Long): List<String>

    /**
     * Get the page that marks the start of a chapter.
     */
    @Query("""
        SELECT * FROM pages
        WHERE bookId = :bookId AND chapterTitle = :chapterTitle
        ORDER BY pageNumber ASC
        LIMIT 1
    """)
    suspend fun getChapterStartPage(bookId: Long, chapterTitle: String): Page?
}
