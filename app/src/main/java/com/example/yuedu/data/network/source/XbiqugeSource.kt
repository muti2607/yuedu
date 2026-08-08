package com.example.yuedu.data.network.source

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 新笔趣阁书源实现
 *
 * 网站: https://www.xbiquge.so
 * 类型: 笔趣阁类小说站
 */
class XbiqugeSource : BaseBookSource() {

    override val id = "xbiquge"
    override val name = "新笔趣阁"
    override val baseUrl = "https://www.xbiquge.so"
    override val group = "笔趣阁系列"
    override var isEnabled = true

    override suspend fun searchBooks(keyword: String, page: Int): BookSource.SearchResult = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "$baseUrl/search.php?q=${java.net.URLEncoder.encode(keyword, "UTF-8")}&p=$page"
            val doc = fetchHtmlDocument(searchUrl)
            val books = parseSearchResults(doc)
            val hasMore = doc.select(".page a:contains(下一页), .pagination a:contains(下一页)").isNotEmpty()
            BookSource.SearchResult(books, hasMore)
        } catch (e: Exception) {
            BookSource.SearchResult(emptyList())
        }
    }

    private fun parseSearchResults(doc: org.jsoup.nodes.Document): List<Book> {
        val books = mutableListOf<Book>()

        // 多种可能的搜索结果选择器
        val selectors = listOf(
            ".search-list li", ".result-list .book-item", ".booklist li",
            ".search-result .item", ".novelslist li", ".item-box"
        )

        var items = org.jsoup.select.Elements()
        for (selector in selectors) {
            items = doc.select(selector)
            if (items.isNotEmpty()) break
        }

        // 尝试解析表格形式的结果
        if (items.isEmpty()) {
            items = doc.select("table.grid tr:gt(0)")
        }

        for (item in items) {
            val link = item.selectFirst("a[href*=/book/], a[href*=/info/], h3 a, h4 a, .title a, .bookname a")
                ?: item.selectFirst("a")
                ?: continue

            val title = link.text().trim()
            if (title.length < 2 || title.length > 50) continue

            val url = resolveUrl(link.attr("href"))
            val author = item.selectFirst(".author, .writer, span.author, td:nth-child(3)")
                ?.text()?.trim()
                ?.replace("作者：", "")?.trim() ?: ""

            val cover = item.selectFirst("img")?.attr("src") ?: ""
            val coverUrl = resolveUrl(cover)

            val description = item.selectFirst(".intro, .desc, .summary, td:nth-child(5)")
                ?.text()?.trim() ?: ""

            if (books.none { it.url == url }) {
                books.add(
                    Book(
                        url = url,
                        title = title,
                        author = author,
                        coverUrl = coverUrl,
                        description = description
                    )
                )
            }
        }

        return books
    }

    override suspend fun getBookDetail(bookUrl: String): Book = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(bookUrl)

        val title = doc.selectFirst("h1, .book-title, .bookinfo h1, #title")
            ?.text()?.trim()
            ?: doc.title().replace(Regex("""[（(].*[）)].*最新章节.*"""), "").trim()

        val author = doc.selectFirst("#author, .author, .book-info .author, .detail .author, .small a")
            ?.text()?.trim()
            ?.replace("作者：", "")?.replace("作者:", "")?.trim() ?: ""

        val description = doc.selectFirst("#intro, .intro, .book-intro, .summary, .description")
            ?.text()?.trim()
            ?.replace("【内容简介】", "")?.replace("简介：", "")?.trim() ?: ""

        val cover = doc.selectFirst("#fmimg img, .book-img img, .cover img, .book-cover img, .pic img")
            ?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        val coverUrl = resolveUrl(cover)

        val lastChapter = doc.selectFirst("#lastchapter a, .last a, .newest a, .update a, .latest a")
            ?.text()?.trim() ?: ""

        val category = doc.selectFirst(".category, .sort, .tag a, .book-category")
            ?.text()?.trim()
            ?.replace("分类：", "")?.replace("类别：", "")?.trim() ?: ""

        val lastUpdateTime = doc.selectFirst(".update-time, .time, .last-update")
            ?.text()?.trim() ?: ""

        Book(
            url = bookUrl,
            title = title,
            author = author,
            description = description,
            coverUrl = coverUrl,
            category = category,
            lastChapterTitle = lastChapter,
            lastUpdateTime = lastUpdateTime
        )
    }

    override suspend fun getChapterList(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(bookUrl)

        // 多种可能的章节列表容器
        val containerSelectors = listOf(
            "#list", ".chapter-list", ".box.listmain dl", ".catalog-list",
            ".chapterlist", "#catalog", ".book_list ul", ".list-chapter"
        )

        var container: org.jsoup.nodes.Element? = null
        for (selector in containerSelectors) {
            container = doc.selectFirst(selector)
            if (container != null && container.select("a").size > 5) break
        }

        val chapters = mutableListOf<Chapter>()
        val seenUrls = mutableSetOf<String>()

        val links = container?.select("dd a, li a, p a, a[href*=.html]")
            ?: doc.select("a[href*=.html]")

        for (link in links) {
            val title = link.text().trim()
            val url = resolveUrl(link.attr("href"))
            if (url.isNotEmpty() && title.isNotEmpty() && title.length < 100 && seenUrls.add(url)) {
                // 过滤掉可能不是章节的链接
                if (looksLikeChapterTitle(title)) {
                    chapters.add(Chapter(bookUrl = bookUrl, title = title, url = url))
                }
            }
        }

        chapters.mapIndexed { index, chapter ->
            chapter.copy(index = index)
        }
    }

    private fun looksLikeChapterTitle(title: String): Boolean {
        if (title.contains("最新章节") || title.contains("返回目录") ||
            title.contains("下一页") || title.contains("上一页")) {
            return false
        }
        // 章节标题通常包含"第X章/节/卷/回"等模式
        val chapterPattern = Regex("""^第[一二三四五六七八九十百千万零〇\d]+[章节卷回部篇集话]""")
        if (chapterPattern.containsMatchIn(title)) return true
        // 或者有一定长度且是纯文本
        if (title.length in 2..50 && !title.contains("http")) return true
        return false
    }

    override suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(chapterUrl)
            doc.select("script, style, noscript, iframe, link[rel=stylesheet], .ads, .advertisement, .ad").remove()

            val contentSelectors = listOf(
                "#content", "#BookText", ".read-content", ".chaptercontent", "#htmlContent",
                ".content-body", ".showtxt", ".nr1", "#nr", ".article-content",
                ".chapter-content", ".text", ".txt", "article"
            )

            val content = extractContentBySelectors(doc, contentSelectors)

            if (content.isNotBlank()) {
                return@withContext content
            }

            // 兜底：提取body中的大段文本
            val bodyText = doc.body()?.wholeText()?.trim() ?: ""
            if (bodyText.isNotBlank() && !looksLikeJavaScript(bodyText) && bodyText.length > 300) {
                return@withContext formatContent(bodyText)
            }

            "【章节URL】\n$chapterUrl\n\n【正文】\n(无法解析章节内容，请检查页面结构)"
        } catch (e: Exception) {
            "【章节URL】\n$chapterUrl\n\n【错误】\n${e.message}"
        }
    }

    override suspend fun getPopularBooks(): List<Book> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(baseUrl)
            val books = mutableListOf<Book>()

            // 尝试各种热门/推荐区域
            val selectors = listOf(
                ".hot li", ".rank li", ".recommend li", ".top li",
                ".block li", ".news li", ".update li", ".list-new li"
            )

            var items = org.jsoup.select.Elements()
            for (selector in selectors) {
                items = doc.select(selector)
                if (items.size >= 5) break
            }

            // 尝试从首页的书籍卡片中提取
            if (items.isEmpty()) {
                items = doc.select(".block_img li, .book-item, .novel-item, .pic-list li")
            }

            for (item in items.take(20)) {
                val link = item.selectFirst("a[href*=/book/], a[href*=/info/], h4 a, h3 a, .title a")
                    ?: item.selectFirst("a")
                    ?: continue

                val title = link.attr("title").trim().ifBlank { link.text().trim() }
                if (title.length < 2 || title.length > 50) continue

                val url = resolveUrl(link.attr("href"))
                val author = item.selectFirst(".author, .writer, span.s2")?.text()?.trim()
                    ?.replace("作者：", "")?.trim() ?: ""
                val cover = item.selectFirst("img")?.attr("src") ?: ""
                val coverUrl = resolveUrl(cover)

                if (books.none { it.url == url } && url.contains(baseUrl.substringAfter("//").split("/").first())) {
                    books.add(Book(url = url, title = title, author = author, coverUrl = coverUrl))
                }
            }

            if (books.isNotEmpty()) return@withContext books.take(20)
        } catch (_: Exception) { }

        // 返回一些经典书籍的URL作为兜底
        listOf(
            Book(url = "$baseUrl/book/0/", title = "示例书籍", author = "", coverUrl = "", description = "")
        ).filter { false }
    }
}
