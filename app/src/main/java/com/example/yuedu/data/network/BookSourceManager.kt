package com.example.yuedu.data.network

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.network.source.Biqu22Source
import com.example.yuedu.data.network.source.BiqukanSource
import com.example.yuedu.data.network.source.BookSource
import com.example.yuedu.data.network.source.GenericNovelSource
import com.example.yuedu.data.network.source.XbiqugeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 书籍数据源管理器
 *
 * 负责管理所有已注册的书源，提供统一的聚合操作接口：
 * - 多书源并行搜索
 * - 根据书籍URL自动识别对应书源
 * - 聚合所有书源的热门书籍
 * - 书源的增删改查与启用/禁用
 */
object BookSourceManager {

    /** 所有已注册的书源 */
    private val sources: MutableList<BookSource> by lazy {
        mutableListOf<BookSource>().apply {
            // 按优先级顺序注册书源
            add(Biqu22Source())      // 22笔趣阁（原默认书源，优先级最高）
            add(XbiqugeSource())    // 新笔趣阁
            add(BiqukanSource())    // 笔趣看
            add(GenericNovelSource()) // 通用小说源
        }
    }

    /** 当前选中的默认书源ID */
    private var currentSourceId: String = Biqu22Source().id

    /**
     * 获取所有书源（含已禁用的）
     */
    fun getAllSources(): List<BookSource> = sources.toList()

    /**
     * 获取已启用的书源列表
     */
    fun getEnabledSources(): List<BookSource> = sources.filter { it.isEnabled }

    /**
     * 根据ID获取书源
     */
    fun getSourceById(id: String): BookSource? = sources.firstOrNull { it.id == id }

    /**
     * 获取当前默认书源
     */
    fun getCurrentSource(): BookSource {
        return sources.firstOrNull { it.id == currentSourceId && it.isEnabled }
            ?: sources.firstOrNull { it.isEnabled }
            ?: sources.first()
    }

    /**
     * 设置当前默认书源
     */
    fun setCurrentSource(id: String) {
        if (sources.any { it.id == id }) {
            currentSourceId = id
        }
    }

    /**
     * 切换书源的启用状态
     */
    fun toggleSourceEnabled(id: String, enabled: Boolean) {
        sources.firstOrNull { it.id == id }?.isEnabled = enabled
    }

    /**
     * 根据URL自动匹配合适的书源
     *
     * 策略：
     * 1. 优先匹配URL域名完全一致的书源
     * 2. 如果找不到，返回当前默认书源
     */
    fun resolveSourceByUrl(url: String): BookSource {
        // 提取URL的host部分
        val host = try {
            java.net.URI(url).host ?: ""
        } catch (_: Exception) { "" }

        if (host.isNotEmpty()) {
            // 精确匹配域名
            sources.firstOrNull { source ->
                try {
                    java.net.URI(source.baseUrl).host == host
                } catch (_: Exception) { false }
            }?.let { return it }

            // 模糊匹配域名
            sources.firstOrNull { source ->
                val sourceHost = try { java.net.URI(source.baseUrl).host } catch (_: Exception) { "" }
                sourceHost.isNotEmpty() && (host.contains(sourceHost) || sourceHost.contains(host))
            }?.let { return it }
        }

        // 兜底：尝试基础URL前缀匹配
        sources.firstOrNull { source -> url.startsWith(source.baseUrl) }?.let { return it }

        return getCurrentSource()
    }

    // ==================== 聚合操作 ====================

    /**
     * 聚合所有已启用书源并行搜索
     *
     * @param keyword 搜索关键词
     * @return 去重合并后的搜索结果
     */
    suspend fun searchBooksFromAll(keyword: String): BookSource.SearchResult = withContext(Dispatchers.IO) {
        val enabledSources = getEnabledSources()
        if (enabledSources.isEmpty()) {
            return@withContext BookSource.SearchResult(emptyList())
        }

        val allBooks = coroutineScope {
            enabledSources.map { source ->
                async {
                    runCatching { source.searchBooks(keyword).books }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }.flatten()

        // 去重：按书名+作者匹配，优先保留书源顺序靠前的
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<Book>()
        for (book in allBooks) {
            val key = "${book.title.trim().lowercase()}|${book.author.trim().lowercase()}"
            if (seen.add(key)) {
                deduped.add(book)
            }
        }

        BookSource.SearchResult(deduped, hasMore = false)
    }

    /**
     * 从指定书源或默认书源搜索
     */
    suspend fun searchBooks(keyword: String, sourceId: String? = null): BookSource.SearchResult {
        val source = sourceId?.let { getSourceById(it) } ?: getCurrentSource()
        return source.searchBooks(keyword)
    }

    /**
     * 获取书籍详情，自动根据URL匹配书源
     */
    suspend fun getBookDetail(bookUrl: String): Book {
        val source = resolveSourceByUrl(bookUrl)
        return source.getBookDetail(bookUrl)
    }

    /**
     * 获取章节列表，自动根据URL匹配书源
     */
    suspend fun getChapterList(bookUrl: String): List<Chapter> {
        val source = resolveSourceByUrl(bookUrl)
        return source.getChapterList(bookUrl)
    }

    /**
     * 获取章节正文，自动根据URL匹配书源
     */
    suspend fun getChapterContent(chapterUrl: String): String {
        val source = resolveSourceByUrl(chapterUrl)
        return source.getChapterContent(chapterUrl)
    }

    /**
     * 获取热门书籍 - 聚合所有书源结果
     */
    suspend fun getPopularBooksFromAll(): List<Book> = withContext(Dispatchers.IO) {
        val enabledSources = getEnabledSources()
        if (enabledSources.isEmpty()) {
            return@withContext emptyList()
        }

        val allBooks = coroutineScope {
            enabledSources.map { source ->
                async {
                    runCatching { source.getPopularBooks() }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        // 按书源顺序合并，去重
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<Book>()
        for (bookList in allBooks) {
            for (book in bookList) {
                val key = "${book.title.trim().lowercase()}|${book.author.trim().lowercase()}"
                if (seen.add(key)) {
                    merged.add(book)
                }
                if (merged.size >= 40) {
                    return@withContext merged
                }
            }
        }

        merged
    }

    /**
     * 获取兜底书籍（当所有书源都失败时使用）
     * 返回预置的经典书籍列表，不依赖网络
     */
    fun getFallbackBooks(): List<Book> {
        val defaultSource = Biqu22Source()
        val base = defaultSource.baseUrl
        return listOf(
            Book(
                url = "$base/biqu6586/",
                title = "乱世书",
                author = "姬叉",
                coverUrl = "",
                description = "仗剑江湖载酒行，楚腰纤细掌中轻。少年肩扛长刀，腰间挂酒，大步前行。",
                category = "玄幻魔法"
            ),
            Book(
                url = "$base/biqu2446/",
                title = "宇宙职业选手",
                author = "我吃西红柿",
                coverUrl = "",
                description = "2036年，人类第一次登上了荧火星。",
                category = "玄幻魔法"
            ),
            Book(
                url = "$base/biqu5456/",
                title = "请公子斩妖",
                author = "裴不了",
                coverUrl = "",
                description = "巍巍人间，九天十地。花花世界，快乐修仙。",
                category = "玄幻魔法"
            ),
            Book(
                url = "$base/biqu5251/",
                title = "宿命之环",
                author = "爱潜水的乌贼",
                coverUrl = "",
                description = "蒸汽与机械的浪潮中，谁能触及非凡？",
                category = "玄幻魔法"
            ),
            Book(
                url = "$base/biqu5708/",
                title = "刚成仙神，子孙求我出山",
                author = "未知",
                coverUrl = "",
                description = "",
                category = "玄幻魔法"
            )
        )
    }

    /**
     * 注册新书源（用于后续动态扩展）
     */
    fun registerSource(source: BookSource) {
        if (sources.none { it.id == source.id }) {
            sources.add(source)
        }
    }

    /**
     * 注销书源
     */
    fun unregisterSource(id: String) {
        sources.removeAll { it.id == id }
    }
}
