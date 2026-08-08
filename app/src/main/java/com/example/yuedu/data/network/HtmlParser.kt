package com.example.yuedu.data.network

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.network.source.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTML解析器（兼容层）
 *
 * 为保持旧代码兼容性，将所有操作委托给 [BookSourceManager]。
 * 新代码请直接使用 [BookSourceManager] 或具体的 [BookSource] 实现。
 */
object HtmlParser {

    /** 搜索结果封装（保持与原接口兼容） */
    data class SearchResult(
        val books: List<Book>,
        val hasMore: Boolean = false
    )

    /**
     * 搜索书籍
     * 多书源并行搜索，返回聚合去重结果
     */
    suspend fun searchBooks(keyword: String): SearchResult = withContext(Dispatchers.IO) {
        val result = BookSourceManager.searchBooksFromAll(keyword)
        SearchResult(result.books, result.hasMore)
    }

    /**
     * 获取书籍详情
     * 根据URL自动匹配对应书源
     */
    suspend fun getBookDetail(bookUrl: String): Book = withContext(Dispatchers.IO) {
        BookSourceManager.getBookDetail(bookUrl)
    }

    /**
     * 获取章节列表
     * 根据URL自动匹配对应书源
     */
    suspend fun getChapterList(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        BookSourceManager.getChapterList(bookUrl)
    }

    /**
     * 获取章节正文
     * 根据URL自动匹配对应书源
     */
    suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        BookSourceManager.getChapterContent(chapterUrl)
    }

    /**
     * 获取热门书籍
     * 聚合所有已启用书源的推荐书籍
     */
    suspend fun getPopularBooks(): List<Book> = withContext(Dispatchers.IO) {
        val result = BookSourceManager.getPopularBooksFromAll()
        result.ifEmpty { BookSourceManager.getFallbackBooks() }
    }

    /**
     * 获取兜底书籍列表
     */
    fun getFallbackBooks(): List<Book> {
        return BookSourceManager.getFallbackBooks().ifEmpty { defaultFallbackBooks }
    }

    /**
     * URL解析（兼容旧接口）
     */
    fun resolveUrl(path: String): String {
        if (path.isBlank()) return ""
        val currentSource = BookSourceManager.getCurrentSource()
        return currentSource.resolveUrl(path)
    }

    /** 兜底书籍列表，极端情况下使用 */
    private val defaultFallbackBooks = listOf(
        Book(
            url = "https://m.22biqu.net/biqu6586/",
            title = "乱世书",
            author = "姬叉",
            coverUrl = "",
            description = "仗剑江湖载酒行，楚腰纤细掌中轻。少年肩扛长刀，腰间挂酒，大步前行。",
            category = "玄幻魔法"
        ),
        Book(
            url = "https://m.22biqu.net/biqu2446/",
            title = "宇宙职业选手",
            author = "我吃西红柿",
            coverUrl = "",
            description = "2036年，人类第一次登上了荧火星。",
            category = "玄幻魔法"
        ),
        Book(
            url = "https://m.22biqu.net/biqu5456/",
            title = "请公子斩妖",
            author = "裴不了",
            coverUrl = "",
            description = "巍巍人间，九天十地。花花世界，快乐修仙。",
            category = "玄幻魔法"
        ),
        Book(
            url = "https://m.22biqu.net/biqu5251/",
            title = "宿命之环",
            author = "爱潜水的乌贼",
            coverUrl = "",
            description = "蒸汽与机械的浪潮中，谁能触及非凡？",
            category = "玄幻魔法"
        ),
        Book(
            url = "https://m.22biqu.net/biqu5708/",
            title = "刚成仙神，子孙求我出山",
            author = "未知",
            coverUrl = "",
            description = "",
            category = "玄幻魔法"
        )
    )
}