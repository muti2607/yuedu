package com.example.yuedu.data.repository

import com.example.yuedu.data.db.AppDatabase
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.network.HtmlParser
import kotlinx.coroutines.flow.Flow

class BookRepository(private val database: AppDatabase) {

    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()

    suspend fun getCachedPopularBooks(): List<Book> {
        return bookDao.getAllCachedBooksSync()
    }

    suspend fun cachePopularBooks(books: List<Book>) {
        if (books.isNotEmpty()) {
            bookDao.insertBooks(books)
        }
    }

    suspend fun searchBooks(keyword: String): HtmlParser.SearchResult {
        return HtmlParser.searchBooks(keyword)
    }

    // ========== �鼮���� ==========

    suspend fun getBookDetail(bookUrl: String): Book {
        val cached = bookDao.getBookByUrl(bookUrl)
        return if (cached != null && cached.author.isNotEmpty()) {
            cached
        } else {
            val detail = HtmlParser.getBookDetail(bookUrl)
            bookDao.insertBook(detail)
            detail
        }
    }

    // ========== �½��б� ==========

    suspend fun getChapterList(bookUrl: String): List<Chapter> {
        val cached = chapterDao.getChaptersByBookUrlSync(bookUrl)
        val networkChapters = HtmlParser.getChapterList(bookUrl)
        if (networkChapters.isNotEmpty()) {
            chapterDao.deleteChaptersByBookUrl(bookUrl)
            chapterDao.insertChapters(networkChapters)
            return networkChapters
        }
        return cached
    }

    fun getChaptersFlow(bookUrl: String): Flow<List<Chapter>> {
        return chapterDao.getChaptersByBookUrl(bookUrl)
    }

    suspend fun getChapterContent(chapter: Chapter): String {
        if (chapter.content.isNotEmpty() && !isInvalidCachedContent(chapter.content)) {
            return chapter.content
        }
        val content = HtmlParser.getChapterContent(chapter.url)
        if (content.isBlank() || isInvalidCachedContent(content)) {
            val updatedChapter = chapter.copy(content = "")
            chapterDao.updateChapter(updatedChapter)
            return "【章节URL】\n${chapter.url}\n\n【正文】\n(内容获取失败，请稍后重试)"
        }
        val updatedChapter = chapter.copy(content = content)
        chapterDao.updateChapter(updatedChapter)
        return content
    }

    private fun isInvalidCachedContent(content: String): Boolean {
        if (content.length > 500 && content.contains("function(") && content.contains("var ")) return true
        if (content.contains("jQuery") && content.contains("module.exports")) return true
        if (content.contains("CryptoJS") || content.contains("hasOwnProperty")) return true
        return false
    }

    suspend fun getChapterAtOffset(bookUrl: String, offset: Int): Chapter? {
        return chapterDao.getChapterAtOffset(bookUrl, offset)
    }

    // ========== ��� ==========

    fun getBookshelfBooks(): Flow<List<Book>> {
        return bookDao.getBookshelfBooks()
    }

    fun getBookByUrlFlow(url: String): Flow<Book?> {
        return bookDao.getBookByUrlFlow(url)
    }

    suspend fun addToBookshelf(bookUrl: String) {
        val book = bookDao.getBookByUrl(bookUrl)
        if (book != null) {
            bookDao.updateBookshelfStatus(bookUrl, true)
        } else {
            val detail = HtmlParser.getBookDetail(bookUrl)
            bookDao.insertBook(detail.copy(isInBookshelf = true))
        }
    }

    suspend fun removeFromBookshelf(bookUrl: String) {
        bookDao.updateBookshelfStatus(bookUrl, false)
    }

    suspend fun isInBookshelf(bookUrl: String): Boolean {
        val book = bookDao.getBookByUrl(bookUrl)
        return book?.isInBookshelf == true
    }

    // ========== �Ķ����� ==========

    suspend fun updateReadingProgress(bookUrl: String, chapterIndex: Int, position: Int) {
        bookDao.updateReadingProgress(bookUrl, chapterIndex, position)
    }

    suspend fun getBook(bookUrl: String): Book? {
        return bookDao.getBookByUrl(bookUrl)
    }
}