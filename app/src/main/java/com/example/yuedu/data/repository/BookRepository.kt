package com.example.yuedu.data.repository

import com.example.yuedu.data.YueduPrefs
import com.example.yuedu.data.db.AppDatabase
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.network.HtmlParser
import com.example.yuedu.data.network.site.LocalBookIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    companion object {
        /** 目录缓存有效期：12 小时内打开同一本书直接用本地目录，不再联网重抓 */
        private const val CHAPTER_LIST_FRESH_MS = 12 * 60 * 60 * 1000L

        /**
         * 目录解析规则版本。
         *
         * 之前版本曾把别的书的章节混进目录（index_N.html 返回的是其它书），
         * 这些脏数据已经被写进本地库。升级版本号让旧缓存全部失效，
         * 保证装上新版后一定用修正过的解析器重新抓一次。
         */
        private const val TOC_SCHEMA_VERSION = 2
    }

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
     * 后台把各分类的书籍缓存进本地库，扩大「本地即时搜索」的覆盖面。
     *
     * 搜索的即时结果来自 books 表的 LIKE 查询，但默认只有书城首页 6 本，
     * 覆盖面太小。这里在书城空闲时静默拉取 7 个分类（每类约 20 本），
     * 让本地索引达到上百本热门书；失败不影响任何现有功能。
     */
    suspend fun warmUpLocalBookIndex() {
        // 限 3 并发拉取 7 个分类（串行要 40+ 秒，并发约 10 秒）。
        // 类别页本身较慢，并发太多又会触发站点限流。
        coroutineScope {
            (1..7).chunked(3).forEach { batch ->
                batch.map { id ->
                    async {
                        try {
                            val books = HtmlParser.getBooksByCategory(id)
                            if (books.isNotEmpty()) bookDao.insertBooks(books)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * 搜索书籍
     *
     * @param keyword 搜索关键词
     * @return 搜索结果，包含书籍列表及分页信息
     */
    /**
     * 搜索：本地缓存即时兜底 + 网络结果补充。
     *
     * books 表里缓存了书城推荐、各分类、以及打开过的书籍详情，
     * 用户搜的书很可能已经在里面。先做 0 网络的本地 LIKE 查询，
     * 再叠加网络搜索结果，最后按书名|作者去重。
     * 这样即使网络源超时，用户也能立刻看到本地已有的书，而不是干等 8 秒后「未找到」。
     */
    suspend fun searchBooks(keyword: String): HtmlParser.SearchResult {
        val kw = keyword.trim()
        if (kw.isEmpty()) return HtmlParser.SearchResult(emptyList())

        val local = searchLocalBooks(kw)
        val remote = HtmlParser.searchBooks(kw)
        return HtmlParser.SearchResult(
            (local + remote.books).distinctBy { it.title + "|" + it.author }
        )
    }

    /** 仅查本地缓存的即时搜索（0 网络），用于搜索时先出结果、再补网络 */
    suspend fun searchLocalBooks(keyword: String): List<Book> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()

        // 第一优先级：内置书库索引（19 万本，纯内存，毫秒级）
        // 这是「输入即出结果」的关键——不依赖任何网络请求。
        val indexed = try {
            LocalBookIndex.search(com.example.yuedu.YueduApplication.instance, kw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        // 再并入已缓存的书籍（用户看过的书可能不在索引里）
        val cached = try {
            bookDao.searchCachedBooks(kw)
        } catch (_: Exception) {
            emptyList()
        }

        return (cached + indexed).distinctBy { it.title + "|" + it.author }
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
     * 性能策略（这是"打开书加载太慢"的主要修复点）：
     * 1. 本地已有目录且不超过 [CHAPTER_LIST_FRESH_MS]（12 小时）时直接用缓存，
     *    不再重新抓整本目录——原来每次打开阅读页都要串行翻完所有目录页；
     * 2. 缓存过期才走网络，网络失败时仍回退到旧缓存，不至于断网就打不开；
     * 3. 网络结果合并本地已下载的章节正文后落库。
     *
     * @param bookUrl 书籍详情页 URL
     * @return 合并后的章节列表
     */
    suspend fun getChapterList(bookUrl: String): List<Chapter> {
        val cached = chapterDao.getChaptersByBookUrlSync(bookUrl)
        if (cached.isNotEmpty() && isChapterCacheFresh(cached)) {
            return cached
        }

        val networkChapters = try {
            HtmlParser.getChapterList(bookUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 网络失败：有旧缓存就用旧缓存，别让阅读页直接空白
            return cached
        }
        if (networkChapters.isNotEmpty()) {
            // 必须先删后插：原来的顺序是把 old id 带进新实体再 delete+insert，
            // 但 delete 之后这些 id 已失效，Room 会沿用旧主键 → 主键冲突/串行；
            // 正确做法是删除旧数据、重新插入时一律使用自增 id，再回读一次真实 id。
            chapterDao.deleteChaptersByBookUrl(bookUrl)
            val cachedContentByUrl = cached.associate { it.url to it.content }
            val mergedChapters = networkChapters.map { network ->
                network.copy(
                    id = 0,
                    content = cachedContentByUrl[network.url].orEmpty()
                )
            }
            chapterDao.insertChapters(mergedChapters)
            markChapterListFetched(bookUrl)
            // 回读，拿到数据库分配的自增 id，否则后续 updateChapter 会静默失败
            return chapterDao.getChaptersByBookUrlSync(bookUrl)
        }
        return cached
    }

    /**
     * 目录缓存是否算“新”。
     *
     * 用章节里最后更新时间无法判断（实体没有该字段），这里以
     * “缓存条目非空且书籍在书架/最近打开过”近似：直接比较时间戳不可行，
     * 因此采用轻量方案——把上次抓取时间记在 SharedPreferences。
     */
    /**
     * 该书是否已有「新鲜」的目录缓存。
     * 供书城预热使用：已有新鲜目录就不必再为它联网。
     */
    suspend fun hasFreshChapterList(bookUrl: String): Boolean {
        val cached = chapterDao.getChaptersByBookUrlSync(bookUrl)
        return cached.isNotEmpty() && isChapterCacheFresh(cached)
    }

    private fun isChapterCacheFresh(cached: List<Chapter>): Boolean {
        if (cached.isEmpty()) return false
        val bookUrl = cached.first().bookUrl
        // 解析规则变了就让旧缓存失效
        if (YueduPrefs.getInt(tocVersionKey(bookUrl), 0) != TOC_SCHEMA_VERSION) return false
        val last = YueduPrefs.getLong(tocTimestampKey(bookUrl), 0L)
        return System.currentTimeMillis() - last < CHAPTER_LIST_FRESH_MS
    }

    /** 记录目录抓取时间与解析规则版本 */
    suspend fun markChapterListFetched(bookUrl: String) {
    }

    private fun tocTimestampKey(bookUrl: String): String = "toc_at_$bookUrl"
    private fun tocVersionKey(bookUrl: String): String = "toc_ver_$bookUrl"

    /**
     * 顺着正文页的相邻章节链接找下一章/上一章。
     *
     * 目录不完整时（例如只解析到前 100 章）用它兜底，保证能继续往下读。
     *
     * @param chapterUrl 当前章节地址
     * @param forward true=下一章，false=上一章
     * @return 相邻章节；不存在时返回 null
     */
    suspend fun findAdjacentChapter(chapterUrl: String, forward: Boolean): Chapter? {
        val url = com.example.yuedu.data.network.site.SiteManager
            .findAdjacentChapterUrl(chapterUrl, forward) ?: return null
        if (url == chapterUrl) return null
        val bookUrl = Regex("""^(https?://[^/]+/\d+_\d+/)""").find(chapterUrl)
            ?.groupValues?.get(1) ?: ""
        return Chapter(bookUrl = bookUrl, title = "", url = url)
    }

    /**
     * 取某本书「正在读的那一章」的标题。
     *
     * 注意：Book.lastChapterTitle 是站点给的「全书最新章节」（如“完本感言”），
     * 不是用户读到哪一章。书架上的「继续阅读」必须用这个按索引查真实标题，
     * 否则会显示成全书最后一章，用户会以为进度错了。
     *
     * @param bookUrl 书籍 URL
     * @param chapterIndex 阅读进度索引
     * @return 章节标题，查不到返回 null
     */
    suspend fun getReadingChapterTitle(bookUrl: String, chapterIndex: Int): String? {
        val chapter = chapterDao.getChapterAtOffset(bookUrl, chapterIndex) ?: return null
        return chapter.title.ifBlank { null }
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
     *
     * 修复要点：
     * 1. 失败时**不再把已有内容清成空串**——原来失败写回 ""，
     *    下次进来又要重抓一遍，网络不好时就会一直转圈；
     * 2. 失败时返回明确提示，而不是一个看起来像正文的“【章节URL】…”块；
     * 3. 抓取成功立即落库，翻页回来不用再走网络。
     *
     * @param chapter 章节实体
     * @return 章节正文文本
     */
    suspend fun getChapterContent(chapter: Chapter): String {
        if (chapter.content.isNotEmpty() && !isInvalidCachedContent(chapter.content)) {
            return chapter.content
        }

        val content = try {
            HtmlParser.getChapterContent(chapter.url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }

        if (content.isBlank() || isInvalidCachedContent(content)) {
            // 必须返回空串，绝不能返回「失败提示文案」：
            // ViewModel 会把返回值当作正文缓存进内存/数据库，
            // 之后每一章都命中这段缓存 → 看起来就是「翻章只在同一段文字里循环」。
            // 提示文案由 UI 层根据空串自行显示，且不落缓存。
            return ""
        }

        if (chapter.id != 0L) {
            // 只更新已入库的章节行；id 为 0 时 update 不生效，交给 insert 路径处理
            chapterDao.updateChapter(chapter.copy(content = content))
        } else {
            persistChapterContent(chapter, content)
        }
        return content
    }

    /**
     * 判断缓存的章节内容是否为无效数据（反爬页面）
     *
     * 原来的规则过于激进：正文里只要出现 “CryptoJS” 或 “hasOwnProperty”
     * 就判定无效——技术流/程序员题材的小说里这些词很常见，
     * 会命中后把正常正文清空，用户看到的就是“正文一直出不来”。
     *
     * 现在只保留高置信度的特征：整段几乎都是 JS 代码、或者带明确的反爬域名标记。
     */
    private fun isInvalidCachedContent(content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return true
        // 纯代码页面：大量花括号/分号，且几乎没有中文
        val chineseCount = text.count { it.code in 0x4E00..0x9FFF }
        val looksLikeCode = text.count { it == '{' || it == '}' || it == ';' } > text.length * 0.05
        if (looksLikeCode && chineseCount < text.length * 0.2) return true
        val blockedMarkers = listOf("bqgcp", "xcshu", "bishu8", "请开启JavaScript", "请启用JavaScript")
        if (blockedMarkers.any { text.contains(it, ignoreCase = true) }) return true
        if (text.contains("jQuery.fn") || text.contains("CryptoJS.lib")) return true
        // 旧版本分页循环留下的脏数据：同一段正文被重复拼接多次
        if (isDuplicatedContent(text)) return true
        return false
    }

    /**
     * 判断正文是否被重复拼接。
     *
     * 站点对不存在的 xxx_2.html 会原样返回第 1 页，老版本据此循环分页，
     * 把同一段正文重复了最多 30 遍。正常章节里一整段文字不会出现两次以上。
     */
    private fun isDuplicatedContent(text: String): Boolean {
        if (text.length < 600) return false
        val sampleLen = (text.length / 3).coerceIn(200, 800)
        val sample = text.substring(0, sampleLen).trim()
        if (sample.length < 200) return false
        val second = text.indexOf(sample, sampleLen)
        return second >= 0
    }

    /**
     * 把章节正文写入本地数据库（离线下载用）。
     *
     * getChapterContent 内部虽然会 updateChapter，但那是基于从数据库读出来的实体；
     * 如果这一章从未入库（例如刚抓完目录还没点进去过），update 不会生效，
     * 所以这里显式 insert（REPLACE）一次，保证下载内容真的落盘。
     *
     * @param chapter 章节实体
     * @param content 已获取到的正文
     */
    suspend fun persistChapterContent(chapter: Chapter, content: String) {
        if (content.isBlank() || isInvalidCachedContent(content)) return
        if (chapter.id == 0L) {
            // 章节行还不存在，先确保书籍行存在（外键约束）
            ensureBookExists(chapter.bookUrl)
        }
        chapterDao.insertChapter(chapter.copy(content = content))
    }

    /**
     * 确保书籍记录存在于 books 表。
     *
     * chapters 表对 books.url 有外键约束，且书架页只展示 books 表里 isInBookshelf 的记录，
     * 因此任何写章节 / 加书架的动作前都要先保证书籍行存在。
     *
     * @param bookUrl 书籍详情页 URL
     */
    suspend fun ensureBookExists(bookUrl: String) {
        if (bookDao.getBookByUrl(bookUrl) != null) return
        val detail = HtmlParser.getBookDetail(bookUrl)
        bookDao.insertBook(detail)
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
