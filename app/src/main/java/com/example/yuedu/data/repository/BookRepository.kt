package com.example.yuedu.data.repository

import com.example.yuedu.data.db.AppDatabase
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.network.HtmlParser
import kotlinx.coroutines.flow.Flow

/**
 * 书籍数据仓库
 *
 * 封装所有书籍相关的数据操作，包括：
 * - 热门书籍的缓存与查询
 * - 书籍详情获取（优先缓存，兜底网络）
 * - 章节列表获取（合并缓存内容与网络最新列表）
 * - 章节正文获取（优先缓存，兜底网络）
 * - 书架管理（添加、移除、查询）
 * - 阅读进度保存与查询
 * - 书籍搜索
 */
class BookRepository(private val database: AppDatabase) {

    /** 书籍数据访问对象 */
    private val bookDao = database.bookDao()

    /** 章节数据访问对象 */
    private val chapterDao = database.chapterDao()

    /**
     * 获取本地缓存的热门书籍列表
     *
     * @return 缓存中的所有书籍
     */
    suspend fun getCachedPopularBooks(): List<Book> {
        return bookDao.getAllCachedBooksSync()
    }

    /**
     * 缓存热门书籍列表到本地数据库
     *
     * @param books 待缓存的书籍列表，为空时跳过
     */
    suspend fun cachePopularBooks(books: List<Book>) {
        if (books.isNotEmpty()) {
            bookDao.insertBooks(books)
        }
    }

    /**
     * 搜索书籍
     *
     * @param keyword 搜索关键词
     * @return 搜索结果，包含书籍列表及分页信息
     */
    suspend fun searchBooks(keyword: String): HtmlParser.SearchResult {
        return HtmlParser.searchBooks(keyword)
    }

    // ========== 书籍详情 ==========

    /**
     * 获取书籍详情
     *
     * 优先返回本地缓存，若缓存不存在或作者为空则从网络获取。
     * 从网络获取时会保留已有的阅读进度和书架状态，避免数据覆盖。
     *
     * @param bookUrl 书籍详情页 URL
     * @return 书籍详情实体
     */
    suspend fun getBookDetail(bookUrl: String): Book {
        val cached = bookDao.getBookByUrl(bookUrl)
        return if (cached != null && cached.author.isNotEmpty()) {
            cached
        } else {
            val detail = HtmlParser.getBookDetail(bookUrl)
            val savedProgress = bookDao.getBookByUrl(bookUrl)
            if (savedProgress != null) {
                val merged = detail.copy(
                    lastReadChapterIndex = savedProgress.lastReadChapterIndex,
                    lastReadPosition = savedProgress.lastReadPosition,
                    isInBookshelf = savedProgress.isInBookshelf
                )
                bookDao.insertBook(merged)
                merged
            } else {
                bookDao.insertBook(detail)
                detail
            }
        }
    }

    // ========== 章节列表 ==========

    /**
     * 获取章节目录列表
     *
     * 合并策略：优先从网络获取最新章节列表，同时保留本地缓存中已有的章节正文内容，
     * 避免已下载的章节内容因刷新目录而丢失。
     *
     * @param bookUrl 书籍详情页 URL
     * @return 合并后的章节列表
     */
    suspend fun getChapterList(bookUrl: String): List<Chapter> {
        val cached = chapterDao.getChaptersByBookUrlSync(bookUrl)
        val networkChapters = HtmlParser.getChapterList(bookUrl)
        if (networkChapters.isNotEmpty()) {
            val cachedMap = cached.associateBy { it.url }
            val mergedChapters = networkChapters.map { network ->
                cachedMap[network.url]?.let { cachedChapter ->
                    network.copy(
                        content = cachedChapter.content,
                        id = cachedChapter.id
                    )
                } ?: network
            }
            chapterDao.deleteChaptersByBookUrl(bookUrl)
            chapterDao.insertChapters(mergedChapters)
            return mergedChapters
        }
        return cached
    }

    /**
     * 获取章节列表的 Flow 流
     *
     * 用于响应式观察章节数据变化
     *
     * @param bookUrl 书籍详情页 URL
     * @return 章节列表的 Flow
     */
    fun getChaptersFlow(bookUrl: String): Flow<List<Chapter>> {
        return chapterDao.getChaptersByBookUrl(bookUrl)
    }

    /**
     * 获取章节正文内容
     *
     * 优先使用缓存的有效内容，无效时从网络重新获取。
     * 获取失败时返回包含章节 URL 的错误提示信息。
     *
     * @param chapter 章节实体
     * @return 章节正文文本
     */
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

    /**
     * 判断缓存的章节内容是否为无效数据
     *
     * 常见无效内容特征：包含 JavaScript 代码、jQuery 引用、
     * CryptoJS 加密库等，这些通常是反爬虫页面而非正文。
     *
     * @param content 待检测的章节内容
     * @return 是否为无效内容
     */
    private fun isInvalidCachedContent(content: String): Boolean {
        if (content.length > 500 && content.contains("function(") && content.contains("var ")) return true
        if (content.contains("jQuery") && content.contains("module.exports")) return true
        if (content.contains("CryptoJS") || content.contains("hasOwnProperty")) return true
        return false
    }

    /**
     * 按偏移量获取指定章节
     *
     * @param bookUrl 书籍详情页 URL
     * @param offset 章节偏移量（从 0 开始）
     * @return 对应章节，不存在时返回 null
     */
    suspend fun getChapterAtOffset(bookUrl: String, offset: Int): Chapter? {
        return chapterDao.getChapterAtOffset(bookUrl, offset)
    }

    // ========== 书架管理 ==========

    /**
     * 获取书架上的所有书籍
     *
     * 返回 Flow 流，书架数据变化时自动更新 UI
     *
     * @return 书架书籍列表的 Flow
     */
    fun getBookshelfBooks(): Flow<List<Book>> {
        return bookDao.getBookshelfBooks()
    }

    /**
     * 按 URL 获取书籍的 Flow 流
     *
     * 用于响应式观察单本书籍的数据变化
     *
     * @param url 书籍详情页 URL
     * @return 书籍实体的 Flow，不存在时返回 null
     */
    fun getBookByUrlFlow(url: String): Flow<Book?> {
        return bookDao.getBookByUrlFlow(url)
    }

    /**
     * 将书籍添加到书架
     *
     * 若本地已有该书缓存，仅更新书架状态标记；
     * 若本地无缓存，则先从网络获取详情再插入。
     *
     * @param bookUrl 书籍详情页 URL
     */
    suspend fun addToBookshelf(bookUrl: String) {
        val book = bookDao.getBookByUrl(bookUrl)
        if (book != null) {
            bookDao.updateBookshelfStatus(bookUrl, true)
        } else {
            val detail = HtmlParser.getBookDetail(bookUrl)
            bookDao.insertBook(detail.copy(isInBookshelf = true))
        }
    }

    /**
     * 将书籍从书架移除
     *
     * 仅更新书架状态标记，不删除书籍数据
     *
     * @param bookUrl 书籍详情页 URL
     */
    suspend fun removeFromBookshelf(bookUrl: String) {
        bookDao.updateBookshelfStatus(bookUrl, false)
    }

    /**
     * 判断书籍是否在书架上
     *
     * @param bookUrl 书籍详情页 URL
     * @return 是否在书架中
     */
    suspend fun isInBookshelf(bookUrl: String): Boolean {
        val book = bookDao.getBookByUrl(bookUrl)
        return book?.isInBookshelf == true
    }

    // ========== 阅读进度 ==========

    /**
     * 更新阅读进度
     *
     * 保存当前阅读到的章节索引和页内位置
     *
     * @param bookUrl 书籍详情页 URL
     * @param chapterIndex 当前章节索引
     * @param position 当前章节内的页码位置
     */
    suspend fun updateReadingProgress(bookUrl: String, chapterIndex: Int, position: Int) {
        bookDao.updateReadingProgress(bookUrl, chapterIndex, position)
    }

    /**
     * 按 URL 获取书籍实体
     *
     * @param bookUrl 书籍详情页 URL
     * @return 书籍实体，不存在时返回 null
     */
    suspend fun getBook(bookUrl: String): Book? {
        return bookDao.getBookByUrl(bookUrl)
    }
}