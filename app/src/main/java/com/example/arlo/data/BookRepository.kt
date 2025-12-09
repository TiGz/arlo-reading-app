package com.example.arlo.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {

    private val gson = Gson()

    val allBooks: Flow<List<Book>> = bookDao.getAllBooks()

    suspend fun createBook(title: String, coverImagePath: String? = null): Long {
        val book = Book(title = title, coverImagePath = coverImagePath)
        return bookDao.insertBook(book)
    }

    suspend fun addPage(bookId: Long, text: String, imagePath: String, pageNumber: Int): Long {
        val page = Page(bookId = bookId, text = text, imagePath = imagePath, pageNumber = pageNumber)
        return bookDao.insertPage(page)
    }

    /**
     * Add a page with sentence data from Claude OCR.
     * Handles sentence continuation from previous page.
     */
    suspend fun addPageWithSentences(
        bookId: Long,
        imagePath: String,
        pageNumber: Int,
        sentences: List<SentenceData>
    ): Long {
        val sentencesToStore = sentences.toMutableList()

        // Handle continuation from previous page
        if (pageNumber > 1) {
            val prevPage = bookDao.getPageByNumber(bookId, pageNumber - 1)
            if (prevPage != null && !prevPage.lastSentenceComplete) {
                val prevSentences = parseSentences(prevPage.sentencesJson)
                if (prevSentences.isNotEmpty() && sentencesToStore.isNotEmpty()) {
                    val lastPrev = prevSentences.last()
                    val firstNew = sentencesToStore.first()

                    // Merge: append first new sentence to last previous
                    val mergedText = "${lastPrev.text} ${firstNew.text}".trim()
                    val mergedComplete = firstNew.isComplete

                    // Update previous page's sentences
                    val updatedPrevSentences = prevSentences.dropLast(1) +
                        SentenceData(mergedText, mergedComplete)

                    bookDao.updatePageSentences(
                        prevPage.id,
                        toJson(updatedPrevSentences),
                        mergedComplete
                    )

                    // Remove merged sentence from new page
                    sentencesToStore.removeAt(0)
                }
            }
        }

        // Determine if this page's last sentence is complete
        val lastComplete = sentencesToStore.lastOrNull()?.isComplete ?: true

        // Build full text from sentences
        val fullText = sentencesToStore.joinToString(" ") { it.text }

        val page = Page(
            bookId = bookId,
            imagePath = imagePath,
            pageNumber = pageNumber,
            text = fullText,
            sentencesJson = toJson(sentencesToStore),
            lastSentenceComplete = lastComplete
        )
        return bookDao.insertPage(page)
    }

    /**
     * Update a page with new sentence data (for re-capture).
     */
    suspend fun updatePageWithSentences(
        pageId: Long,
        imagePath: String,
        sentences: List<SentenceData>
    ) {
        val fullText = sentences.joinToString(" ") { it.text }
        val lastComplete = sentences.lastOrNull()?.isComplete ?: true

        bookDao.updatePageFull(
            pageId,
            fullText,
            imagePath,
            toJson(sentences),
            lastComplete
        )
    }

    fun getPages(bookId: Long): Flow<List<Page>> {
        return bookDao.getPagesForBook(bookId)
    }

    suspend fun getPagesSync(bookId: Long): List<Page> {
        return bookDao.getPagesForBookSync(bookId)
    }

    suspend fun getNextPageNumber(bookId: Long): Int {
        return bookDao.getPageCount(bookId) + 1
    }

    suspend fun getBook(bookId: Long): Book? {
        return bookDao.getBook(bookId)
    }

    suspend fun getPage(pageId: Long): Page? {
        return bookDao.getPage(pageId)
    }

    suspend fun getPageByNumber(bookId: Long, pageNumber: Int): Page? {
        return bookDao.getPageByNumber(bookId, pageNumber)
    }

    suspend fun getPageCount(bookId: Long): Int {
        return bookDao.getPageCount(bookId)
    }

    suspend fun updateBookCover(bookId: Long, coverPath: String) {
        bookDao.updateBookCover(bookId, coverPath)
    }

    suspend fun updateBookTitle(bookId: Long, title: String) {
        bookDao.updateBookTitle(bookId, title)
    }

    suspend fun updateLastReadPage(bookId: Long, pageNumber: Int) {
        bookDao.updateLastReadPage(bookId, pageNumber)
    }

    suspend fun updateLastReadPosition(bookId: Long, pageNumber: Int, sentenceIndex: Int) {
        bookDao.updateLastReadPosition(bookId, pageNumber, sentenceIndex)
    }

    suspend fun deletePage(pageId: Long) {
        bookDao.deletePage(pageId)
    }

    suspend fun updatePage(pageId: Long, text: String, imagePath: String) {
        bookDao.updatePage(pageId, text, imagePath)
    }

    suspend fun deleteBook(bookId: Long) {
        bookDao.deleteBook(bookId)
    }

    // Legacy book detection and cleanup
    suspend fun getBooksWithoutSentences(): List<Book> {
        return bookDao.getBooksWithoutSentences()
    }

    suspend fun deleteLegacyBooks(bookIds: List<Long>) {
        bookDao.deleteBooks(bookIds)
    }

    // OCR Queue methods
    fun getProcessingPages(): Flow<List<Page>> {
        return bookDao.getProcessingPages()
    }

    suspend fun getNextPendingPage(): Page? {
        return bookDao.getNextPendingPage()
    }

    suspend fun updateProcessingStatus(pageId: Long, status: String, error: String? = null) {
        bookDao.updateProcessingStatus(pageId, status, error)
    }

    suspend fun updateProcessingStatusWithRetry(pageId: Long, status: String, retryCount: Int, error: String? = null) {
        bookDao.updateProcessingStatusWithRetry(pageId, status, retryCount, error)
    }

    suspend fun updatePageWithOCRResult(pageId: Long, text: String, sentencesJson: String, lastSentenceComplete: Boolean) {
        bookDao.updatePageWithOCRResult(pageId, text, sentencesJson, lastSentenceComplete)
    }

    fun getCompletedPages(bookId: Long): Flow<List<Page>> {
        return bookDao.getCompletedPagesForBook(bookId)
    }

    suspend fun getCompletedPagesSync(bookId: Long): List<Page> {
        return bookDao.getCompletedPagesForBookSync(bookId)
    }

    fun getPendingPageCount(bookId: Long): Flow<Int> {
        return bookDao.getPendingPageCount(bookId)
    }

    /**
     * Queue a page for OCR processing.
     */
    suspend fun queuePageForProcessing(
        bookId: Long,
        imagePath: String,
        pageNumber: Int
    ): Long {
        val page = Page(
            bookId = bookId,
            imagePath = imagePath,
            pageNumber = pageNumber,
            text = "",
            processingStatus = "PENDING"
        )
        return bookDao.insertPage(page)
    }

    // JSON helpers
    private fun toJson(sentences: List<SentenceData>): String {
        return gson.toJson(sentences)
    }

    fun toSentencesJson(sentences: List<SentenceData>): String {
        return gson.toJson(sentences)
    }

    fun parseSentences(json: String?): List<SentenceData> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SentenceData>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
