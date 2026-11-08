package com.example.yuedu.data.network

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.network.site.BookSites
import com.example.yuedu.data.network.site.BiqugeMomSite
import com.example.yuedu.data.network.site.BiqugaSite
import com.example.yuedu.data.network.site.BqgNovelsSite
import com.example.yuedu.data.network.site.SharedHttp
import com.example.yuedu.data.network.site.SiteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.nio.charset.Charset

/**
 * 对外统一入口。
 *
 * 原来所有站点的解析都堆在这个文件里，只能服务一个站点；
 * 现在把站点相关逻辑下沉到 [com.example.yuedu.data.network.site.BookSite] 各个实现里，
 * 这里只保留“多站点聚合”的职责：搜索、热门书单、以及兼容旧调用点的方法签名。
 */
object HtmlParser {

    data class SearchResult(
        val books: List<Book>,
        val hasMore: Boolean = false
    )

    // ==================== 书籍信息 ====================

    /** 书籍详情：按 URL 自动选择书源 */
    suspend fun getBookDetail(bookUrl: String): Book = SiteManager.getBookDetail(bookUrl)

    /** 章节目录：按 URL 自动选择书源 */
    suspend fun getChapterList(bookUrl: String): List<Chapter> = SiteManager.getChapterList(bookUrl)

    /** 章节正文 */
    suspend fun getChapterContent(chapterUrl: String): String = SiteManager.getChapterContent(chapterUrl)

    /** 该 URL 对应的书源名称 */
    fun siteNameOf(url: String): String = BookSites.nameFor(url)

    // ==================== 搜索 ====================

    /**
     * 搜索：以 biquga 的 POST 搜索为主源。
     *
     * 之前三个源并发 `awaitAll()`，被最慢的源拖到 20 秒以上，
     * 且其中两个是假搜索：
     *  - biquge.mom /search?q= 纯前端渲染，HTML 里没有结果（解析到的是页脚热门推荐）
     *  - bqgnovels 无搜索页，之前是抓分类页本地过滤
     *  - 试过改用 biquge.mom 的静态 JSON 索引，但该域对 App 直连响应 15s+ 超时，不可行
     *
     * 现在只保留 biquga 一个真搜索源，8 秒超时，失败即返回空（给出明确提示），
     * 不再让任何一个慢源拖累整体。搜索结果按关键词本地过滤一遍，
     * 避免站点返回「请输入搜索词」页里的推荐书混进结果。
     */
    suspend fun searchBooks(keyword: String): SearchResult = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchResult(emptyList())
        val kw = keyword.trim()

        val raw = try {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) { searchBiquga(kw) } ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        // 站点搜索失败时会返回「请输入搜索词」+ 热门推荐，
        // 这些书和关键词无关，必须过滤掉，否则就是「搜什么都是这几本」。
        val filtered = raw.filter {
            it.title.contains(kw, ignoreCase = true) || it.author.contains(kw, ignoreCase = true)
        }
        SearchResult(filtered.distinctBy { it.title + "|" + it.author })
    }

    /**
     * biquga 搜索：form POST /search.html，字段名 s。
     * 结果条目结构同首页的 .item（.image img / dl dt a / dl dt span 作者）。
     */
    private suspend fun searchBiquga(keyword: String): List<Book> {
        val site = BookSites.forUrl("https://www.biquga.com/") as BiqugaSite
        val body = FormBody.Builder().add("s", keyword).build()
        val request = Request.Builder()
            .url("${site.baseUrl}/search.html")
            .header("User-Agent", SharedHttp.USER_AGENT)
            .header("Referer", "${site.baseUrl}/")
            .post(body)
            .build()
        val doc = SharedHttp.execute(SharedHttp.client, request) { response, bytes ->
            val charset = SharedHttp.detectCharset(response, bytes)
            Jsoup.parse(String(bytes, Charset.forName(charset)))
        }
        return parseItemCards(doc, site)
    }

    /** 解析 biquga 的 .item 卡片（首页与搜索结果同一套结构） */
    private fun parseItemCards(doc: Document, site: BiqugaSite): List<Book> {
        val books = mutableListOf<Book>()
        for (item in doc.select(".item")) {
            val link = item.selectFirst("dl dt a[href]") ?: continue
            val title = link.text().trim()
            if (title.length < 1 || title.length > 60) continue
            val url = site.resolveUrl(link.attr("href"))
            if (url.isEmpty()) continue
            val author = item.selectFirst("dl dt span")?.text()?.trim().orEmpty()
            val cover = item.selectFirst(".image img")?.attr("src").orEmpty()
            val desc = item.selectFirst("dl dd")?.text()?.trim().orEmpty()
            if (books.none { it.url == url }) {
                books.add(
                    Book(
                        url = url,
                        title = title,
                        author = author,
                        coverUrl = site.resolveUrl(cover),
                        description = desc
                    )
                )
            }
        }
        return books
    }

    /**
     * bqgnovels 没有可用的搜索页（/search 返回 404，搜索框是前端菜单）。
     *
     * 退而求其次：抓分类页的书籍卡片，在本地按书名/作者做包含匹配。
     * 只影响“按关键词找书”，不影响按 URL 直接阅读任何一本书。
     */
    private suspend fun searchBqgNovels(keyword: String): List<Book> {
        val site = BookSites.forUrl("https://www.bqgnovels.com/") as BqgNovelsSite
        val doc = try {
            site.fetchDocument("${site.baseUrl}/xh")
        } catch (_: Exception) {
            return emptyList()
        }
        val kw = keyword.trim()
        return parseNuxtCards(doc, site)
            .filter { it.title.contains(kw, ignoreCase = true) || it.author.contains(kw, ignoreCase = true) }
    }

    /** 解析 bqgnovels 的 .book_card 卡片 */
    private fun parseNuxtCards(doc: Document, site: BqgNovelsSite): List<Book> {
        val books = mutableListOf<Book>()
        for (card in doc.select(".book_card")) {
            val link = card.selectFirst("a.card[href]") ?: continue
            val title = card.selectFirst(".title")?.text()?.trim()
                ?: link.selectFirst("img")?.attr("alt")?.trim()
                ?: continue
            if (title.isEmpty()) continue
            val url = site.resolveUrl(link.attr("href"))
            if (url.isEmpty()) continue
            val cover = link.selectFirst("img")?.attr("src").orEmpty()
            val desc = card.selectFirst(".des")?.text()?.trim().orEmpty()
            if (books.none { it.url == url }) {
                books.add(
                    Book(
                        url = url,
                        title = title,
                        coverUrl = site.resolveUrl(cover),
                        description = desc
                    )
                )
            }
        }
        return books
    }

    /**
     * biquge.mom 搜索页 /search?q=xxx。
     */
    private suspend fun searchBiqugeMom(keyword: String): List<Book> {
        val site = BookSites.forUrl("https://www.biquge.mom/") as BiqugeMomSite
        val doc = try {
            site.fetchDocument("${site.baseUrl}/search?q=" + java.net.URLEncoder.encode(keyword, "UTF-8"))
        } catch (_: Exception) {
            return emptyList()
        }
        return parseBookCards(doc, site)
    }

    /** 解析 biquge.mom 的 a.book-card 列表 */
    private fun parseBookCards(doc: Document, site: BiqugeMomSite): List<Book> {
        val books = mutableListOf<Book>()
        for (card in doc.select("a.book-card[href]")) {
            val title = card.selectFirst(".book-title")?.text()?.trim()
            if (title.isNullOrEmpty()) continue
            val url = site.resolveUrl(card.attr("href"))
            if (url.isEmpty()) continue
            // .book-meta 形如 “作者 · 分类 · 1,006 章”
            val meta = card.selectFirst(".book-meta")?.text().orEmpty().split("·")
            val author = meta.getOrNull(0)?.trim().orEmpty()
            val category = meta.getOrNull(1)?.trim().orEmpty()
            val cover = card.selectFirst("img.cover-img")?.attr("src").orEmpty()
            if (books.none { it.url == url }) {
                books.add(
                    Book(
                        url = url,
                        title = title,
                        author = author,
                        category = category,
                        coverUrl = site.resolveUrl(cover)
                    )
                )
            }
        }
        return books
    }

    // ==================== 书城首页 ====================

    /**
     * 热门书籍：优先 biquga 首页卡片，失败再退到 biquge.mom 分类首页。
     */
    suspend fun getPopularBooks(): List<Book> = withContext(Dispatchers.IO) {
        val biquga = BookSites.forUrl("https://www.biquga.com/") as BiqugaSite
        runCatching {
            parseItemCards(biquga.fetchDocument(biquga.baseUrl + "/"), biquga)
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it.take(20) }

        val mom = BookSites.forUrl("https://www.biquge.mom/") as BiqugeMomSite
        runCatching {
            parseBookCards(mom.fetchDocument(mom.baseUrl + "/categories"), mom)
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it.take(20) }

        emptyList()
    }

    /** 离线兜底书单（全部来自 biquga，实测可访问） */
    fun getFallbackBooks(): List<Book> = fallbackBooks

    /** 可用的分类列表（id、名称） */
    fun getCategories(): List<Pair<Int, String>> = BiqugaSite.CATEGORIES

    /**
     * 按分类获取书籍列表。
     *
     * @param categoryId 分类 id（见 [getCategories]）
     * @param page 页码，从 1 开始
     */
    suspend fun getBooksByCategory(categoryId: Int, page: Int = 1): List<Book> =
        withContext(Dispatchers.IO) {
            val site = BookSites.forUrl("https://www.biquga.com/") as BiqugaSite
            runCatching {
                parseItemCards(site.fetchDocument(site.categoryUrl(categoryId, page)), site)
            }.getOrDefault(emptyList())
        }

    private val fallbackBooks = listOf(
        Book(url = "https://www.biquga.com/56_56838/", title = "星门", author = "老鹰吃小鸡",
            description = "传说，在那古老的星空深处，伫立着一道血与火侵染的红色之门。", category = "玄幻小说"),
        Book(url = "https://www.biquga.com/1_1709/", title = "特种奶爸俏老婆", author = "二斗",
            description = "啥，老子堂堂的漠北兵王，居然要当奶爸？", category = "都市小说"),
        Book(url = "https://www.biquga.com/11_11629/", title = "临渊行", author = "宅猪",
            description = "苏云怎么也没有想到，自己生活了十几年的天门镇，只有自己是人。", category = "玄幻小说"),
        Book(url = "https://www.biquga.com/15_15556/", title = "花娇", author = "吱吱",
            description = "郁棠前世家破人亡，今生只想帮着大堂兄振兴家业。", category = "都市小说"),
        Book(url = "https://www.biquga.com/4_4140/", title = "飞剑问道", author = "我吃西红柿",
            description = "这个世界，有狐仙河神水怪大妖，也有求长生的修行者。", category = "修真小说"),
        Book(url = "https://www.biquga.com/39_39389/", title = "夜的命名术", author = "会说话的肘子",
            description = "蓝与紫的霓虹中，浓密的钢铁苍穹下，数据洪流的前端。", category = "都市小说"),
        Book(url = "https://www.bqgnovels.com/book/34983", title = "三国之席卷天下", author = "君子毅",
            description = "秦峰字子进，孑然一身后世而来，东汉王朝顿起波澜。", category = "穿越历史"),
        Book(url = "https://www.biquge.mom/book/2/", title = "我的导演时代", author = "吃饭打怪兽",
            description = "我是个导演，拍好电影的导演。", category = "娱乐明星")
    )
}
