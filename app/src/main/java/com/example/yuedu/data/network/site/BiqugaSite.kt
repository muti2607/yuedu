package com.example.yuedu.data.network.site

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import org.jsoup.nodes.Document
import android.util.Base64

/**
 * 书源：笔趣阁 www.biquga.com
 *
 * 结构（实测）：
 * - 详情页 /56_56838/ ：
 *   h1 书名，.p_author 作者，.desc 简介，.imgbox img 封面，.info .p 里有状态/更新时间
 * - 目录：详情页里的第一段 .section-list 最多只有 100 章，
 *   完整目录在 /56_56838/index_1.html（.section-list）
 * - 正文 /56_56838/39982122.html ：
 *   正文不在 DOM 里，而是被拆成若干 <script>document.writeln(qsbs.bb('BASE64'))</script>，
 *   其中 qsbs.bb 就是标准 Base64 解码（见 /biquge/pc/js/yueduqsbs.js），可静态还原。
 *   多页时第二页为 /56_56838/39982122_2.html，页面里有 kkehvov 变量指向下一页。
 */
class BiqugaSite : BookSite() {

    override val name = "笔趣阁(biquga)"
    override val baseUrl = "https://www.biquga.com"

    override fun parseBookDetail(doc: Document, bookUrl: String): Book {
        val title = doc.selectFirst("meta[property=og:novel:book_name]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst(".detail-box .info h1")?.text()?.trim()
            ?: doc.title().substringBefore("最新章节").trim()

        val author = doc.selectFirst("meta[property=og:novel:author]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst(".p_author a")?.text()?.trim()
            ?: ""

        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".imgbox img")?.attr("src")
            ?: ""

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst(".desc")?.text()?.trim()
            ?: ""

        val category = doc.selectFirst("meta[property=og:novel:category]")?.attr("content") ?: ""

        val lastChapter = doc.selectFirst("meta[property=og:novel:latest_chapter_name]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst(".info p:containsOwn(最新章节) a")?.text()?.trim()
            ?: ""

        // 状态：优先 og:novel:status，其次页面里的“状　态：已完结”
        val status = doc.selectFirst("meta[property=og:novel:status]")?.attr("content")?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst(".info .fix p:contains(态)")?.text()?.substringAfter("：")?.trim()
            ?: ""

        return Book(
            url = bookUrl,
            title = title,
            author = author,
            description = description,
            coverUrl = resolveUrl(cover),
            category = category,
            lastChapterTitle = lastChapter,
            status = status
        )
    }

    override fun parseChapterList(doc: Document, bookUrl: String): List<Chapter> {
        // 详情页有两个 .section-list：「最新章节」(约12条，倒序) 和「章节列表」(正序，约100条)。
        // 必须选正序那个，否则第一章会从末尾开始，看起来就像「翻章又回去了」。
        val containers = doc.select(".section-list")
        val container = containers
            .filter { it.select("li a[href]").isNotEmpty() }
            .maxByOrNull { it.select("li a[href]").size }
            ?: return emptyList()

        // 只认本书路径下的 .html 链接。index_N.html 那些分页页实测会返回
        // 别的书（如 /5_5683/）的目录，且是 onclick="read_tz()" 无 href，
        // 严格按 bookUrl 的书号过滤可以避免把别的书章节混进来。
        val bookId = Regex("""/(\d+_\d+)/?$""").find(bookUrl.trimEnd('/'))?.groupValues?.get(1)
        val links = container.select("li a[href]")
        return links.mapNotNull { a ->
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (!href.endsWith(".html") || title.isEmpty()) return@mapNotNull null
            if (bookId != null && !href.contains("/$bookId/")) return@mapNotNull null
            val url = resolveUrl(href, bookUrl)
            if (url.isEmpty()) null else Chapter(bookUrl = bookUrl, title = title, url = url)
        }.distinctBy { it.url }
    }

    /**
     * 按「首章 ID + 偏移」补全整本目录。
     *
     * biquga 的详情页只列出前 100 章，而章节 ID 是严格连续递增的
     * （实测 39982122、39982123… 每个 +1），最新章节 ID 在 meta 里也有。
     * 因此可以从首章推出全部章节地址，不需要依赖会跳到别的书的 index_N.html。
     */
    fun expandFullCatalog(doc: Document, bookUrl: String, firstPage: List<Chapter>): List<Chapter> {
        val first = firstPage.firstOrNull() ?: return firstPage
        val firstId = Regex("""/(\d+)\.html""").find(first.url)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return firstPage
        val lastId = lastChapterNumber(doc) ?: return firstPage
        if (lastId <= firstId) return firstPage

        val base = bookUrl.trimEnd('/')
        val existing = firstPage.associateBy { it.url }.toMutableMap()
        val all = mutableListOf<Chapter>()
        for (id in firstId..lastId) {
            val url = "$base/$id.html"
            all.add(existing[url] ?: Chapter(bookUrl = bookUrl, title = "", url = url))
        }
        return all
    }

    /**
     * 不再做目录分页。
     *
     * 站点详情页本身已经给出约 100 章且链接真实可用；而 index_N.html
     * 经实测会返回其它书籍的目录（或反爬兜底页），继续翻页只会：
     *  1) 发出几十次无效请求 → 加载极慢；
     *  2) 把别的书章节混进目录 → 翻章错乱。
     * 因此这里返回 null，只用详情页的目录。
     */
    override fun chapterListPageUrl(bookUrl: String, page: Int): String? = null

    /** 没有单独的全部目录页，直接用详情页 */
    override fun catalogUrl(bookUrl: String): String = bookUrl

    /**
     * 分类列表页：/list/{分类id}/1.html
     *
     * 实测 7 个分类：1玄幻 2修真 3都市 4历史 5网游 6科幻 7其他
     */
    fun categoryUrl(categoryId: Int, page: Int = 1): String =
        "${baseUrl.trimEnd('/')}/list/$categoryId/$page.html"

    companion object {
        /** 分类 id → 名称 */
        val CATEGORIES: List<Pair<Int, String>> = listOf(
            1 to "玄幻",
            2 to "修真",
            3 to "都市",
            4 to "历史",
            5 to "网游",
            6 to "科幻",
            7 to "其他"
        )
    }

    /**
     * 章节 ID 是连续递增的（实测 39982122、39982123… 逐次 +1），
     * 因此可以从详情页里首章 ID 直接推出后续任意一章的地址，
     * 不依赖站点的目录分页（那个会跳到别的书）。
     *
     * 注意：章节正文页的 K 变量（uiiekp0do/kkehvov）才是权威的上一章/下一章，
     * 这里的推算主要用于「目录只有前 100 章」时补全列表。
     */
    /**
     * 章节 ID 连续递增，目录补全见 [expandFullCatalog]；
     * 这里按序号解析交给 SiteManager 的 expandChaptersByIndex 处理。
     */
    override fun chapterUrlForIndex(bookUrl: String, chapterNo: Int): String? = null

    /** 缓存每本书的首章 ID：/56_56838/ -> 39982122 */
    /**
     * 下一章：从正文页的翻页链接里取（比目录可靠，目录可能只有前 100 章）。
     */
    override fun nextChapterUrl(doc: Document, currentChapterUrl: String): String? {
        return findNavUrl(doc, currentChapterUrl, isNext = true)
    }

    override fun prevChapterUrl(doc: Document, currentChapterUrl: String): String? {
        return findNavUrl(doc, currentChapterUrl, isNext = false)
    }

    /**
     * 从正文页里找上一章/下一章。
     *
     * 页面里有两套线索：
     * 1. `<script>` 中的 `var uiiekp0do='/56_56838/xxx.html'`（上一章）
     *    与 `var kkehvov='/56_56838/xxx_1.html'`（本章第2页，不是下一章）
     * 2. `.read_btn` / 底部 `.nav` 里的「上一章 / 下一章」文字链接
     * 优先用第 2 种（语义明确），第 1 种只在能确定是章节页时用。
     */
    private fun findNavUrl(doc: Document, currentChapterUrl: String, isNext: Boolean): String? {
        val keyword = if (isNext) "下一章" else "上一章"
        // DOM 链接：按文字匹配
        val link = doc.select("a[href]").firstOrNull {
            it.text().trim().startsWith(keyword) && it.attr("href").contains(".html")
        }
        if (link != null) {
            val href = link.attr("href").trim()
            if (href.isNotEmpty() && !href.startsWith("javascript")) {
                return resolveUrl(href, currentChapterUrl)
            }
        }
        return null
    }

    /** 最新章节号：详情页 meta 里的 latest_chapter_url 形如 /56_56838/69753195.html */
    fun lastChapterNumber(doc: Document): Int? {
        val url = doc.selectFirst("meta[property=og:novel:latest_chapter_url]")?.attr("content")
            ?: return null
        return Regex("""/(\d+)\.html""").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 正文分页：xxx.html 的第二页是 xxx_2.html
     */
    override fun contentPageUrl(chapterUrl: String, page: Int): String? {
        if (page <= 1) return null
        return if (chapterUrl.endsWith(".html")) {
            chapterUrl.dropLast(".html".length) + "_$page.html"
        } else {
            "$chapterUrl" + "_$page.html"
        }
    }

    override fun parseChapterContent(doc: Document, chapterUrl: String): String {
        val reader = doc.selectFirst(".word_read") ?: doc.body() ?: return ""

        // 1. 先收集 script 里 qsbs.bb('...') 的 base64 片段
        val encoded = Regex("""qsbs\.(?:bb|aa)\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""")
            .findAll(reader.html())
            .map { it.groupValues[1] }
            .toList()

        if (encoded.isNotEmpty()) {
            val html = encoded.joinToString("") { decodeBase64(it) }
            val text = cleanContent(html)
            if (text.isNotBlank()) return text
        }

        // 2. 兜底：正文直接写在 DOM 里（部分老章节）
        val direct = reader.selectFirst("#chaptercontent, #content, .content, .text")
        if (direct != null) {
            val text = cleanContent(direct.html())
            if (text.isNotBlank()) return text
        }
        return cleanContent(reader.html())
    }

    private fun decodeBase64(input: String): String {
        return try {
            String(Base64.decode(input, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
