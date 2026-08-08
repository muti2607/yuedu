package com.example.yuedu.data.network.source

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 笔趣看书源实现
 *
 * 网站: https://www.biqukan.la
 * 类型: 通用笔趣阁类小说站，结构灵活
 */
class BiqukanSource : BaseBookSource() {

    override val id = "biqukan"
    override val name = "笔趣看"
    override val baseUrl = "https://www.biqukan.la"
    override val group = "笔趣阁系列"
    override var isEnabled = true

    override suspend fun searchBooks(keyword: String, page: Int): BookSource.SearchResult = withContext(Dispatchers.IO) {
        try {
            // 尝试不同的搜索接口
            val doc = try {
                fetchHtmlDocumentPost(
                    "$baseUrl/modules/article/search.php",
                    mapOf("searchkey" to keyword, "searchtype" to "articlename"),
                    mapOf("Referer" to baseUrl)
                )
            } catch (e: Exception) {
                fetchHtmlDocument("$baseUrl/search?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}&page=$page")
            }
            val books = parseSearchResults(doc)
            BookSource.SearchResult(books)
        } catch (e: Exception) {
            BookSource.SearchResult(emptyList())
        }
    }

    private fun parseSearchResults(doc: org.jsoup.nodes.Document): List<Book> {
        val books = mutableListOf<Book>()

        // 搜索结果的可能容器
        val itemSelectors = listOf(
            ".search-list .bookbox", ".result-item", ".search-result-list li",
            ".book-list li", ".list-item", ".so-item", ".search-item",
            "table.grid tr:gt(0)"
        )

        var items = org.jsoup.select.Elements()
        for (selector in itemSelectors) {
            items = doc.select(selector)
            if (items.isNotEmpty()) break
        }

        for (item in items) {
            val titleLink = item.selectFirst(".bookname a, .title a, h3 a, h4 a, a.book-title, td a")
                ?: item.selectFirst("a")
                ?: continue

            val title = titleLink.attr("title").trim().ifBlank { titleLink.text().trim() }
            if (title.length < 2 || title.length > 60) continue

            val url = resolveUrl(titleLink.attr("href"))

            val author = item.selectFirst(".author, .writer, .book-aut, td:nth-child(3), span.small")
                ?.text()?.trim()
                ?.replace("作者：", "")?.replace("作者:", "")?.trim()
                ?: ""

            val cover = item.selectFirst(".bookimg img, img.cover, img")?.attr("src") ?: ""
            val coverUrl = resolveUrl(cover)

            val description = item.selectFirst(".review, .intro, .desc, .summary, td:nth-child(5)")
                ?.text()?.trim() ?: ""

            val category = item.selectFirst(".sort, .category, .book-class")
                ?.text()?.trim()
                ?.replace("分类：", "")?.trim() ?: ""

            if (books.none { it.url == url }) {
                books.add(
                    Book(
                        url = url,
                        title = title,
                        author = author,
                        coverUrl = coverUrl,
                        description = description,
                        category = category
                    )
                )
            }
        }

        return books
    }

    override suspend fun getBookDetail(bookUrl: String): Book = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(bookUrl)

        val titleElement = doc.selectFirst(".book-title h1, .book-info h1, #info h1, .main-info h1, h1")
        val title = titleElement?.text()?.trim()
            ?: doc.title().replace(Regex("""[（(_].*?[）)_]"""), "").replace("最新章节", "").replace("无弹窗", "").trim()

        val author = doc.select("#info p, .book-info .author, .small span, .detail-info .author")
            .firstOrNull { it.text().contains("作者") }
            ?.text()?.trim()
            ?.replace(Regex(""".*作者[：:]"""), "")?.trim() ?: ""

        val description = doc.selectFirst("#intro, .intro, .book-intro, .description, .synopsis")
            ?.text()?.trim()
            ?.replace(Regex("""^.*?(内容简介|简介)[：:]"""), "")?.trim() ?: ""

        val cover = doc.selectFirst("#sidebar img, .book-cover img, .cover img, #fmimg img, .pic img")
            ?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        val coverUrl = resolveUrl(cover)

        val lastChapter = doc.selectFirst("#newlastchapter a, .newest a, .last-chapter a, .update a, #info a:last-of-type")
            ?.text()?.trim() ?: ""

        val category = doc.selectFirst(".sort, .category, .con_top a:nth-child(2), .breadcrumb a:nth-child(2)")
            ?.text()?.trim()
            ?.replace("分类：", "")?.trim() ?: ""

        val lastUpdate = doc.select(".info p, #info p")
            .firstOrNull { it.text().contains("更新") || it.text().contains("时间") }
            ?.text()?.trim()
            ?.replace(Regex(""".*?最后更新[：:]"""), "")?.trim() ?: ""

        Book(
            url = bookUrl,
            title = title,
            author = author,
            description = description,
            coverUrl = coverUrl,
            category = category,
            lastChapterTitle = lastChapter,
            lastUpdateTime = lastUpdate
        )
    }

    override suspend fun getChapterList(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(bookUrl)

        val chapters = mutableListOf<Chapter>()
        val seenUrls = mutableSetOf<String>()

        // 找到章节列表容器 - 优先选择有最多链接的容器
        val containerSelectors = listOf(
            "#list", ".chapterlist", ".box_con dl", ".book-list",
            ".catalog-list", ".chapter-list", ".section-list", "#chapters"
        )

        var bestContainer: org.jsoup.nodes.Element? = null
        var maxLinks = 0
        for (selector in containerSelectors) {
            val container = doc.selectFirst(selector)
            val linkCount = container?.select("a")?.size ?: 0
            if (linkCount > maxLinks) {
                maxLinks = linkCount
                bestContainer = container
            }
        }

        val chapterLinks = if (bestContainer != null && maxLinks > 10) {
            bestContainer.select("dd a, li a, p a, a")
        } else {
            // 兜底：在页面中找看起来像章节的链接
            doc.select("a[href*='.html']").filter { link ->
                val text = link.text().trim()
                (text.contains(Regex("""第.+[章节卷回]""")) || (text.length in 2..60 && !text.contains("http"))) &&
                    !text.contains("最新章节") && !text.contains("返回")
            }.let { org.jsoup.select.Elements(it) }
        }

        for (link in chapterLinks) {
            val title = link.text().trim()
            val url = resolveUrl(link.attr("href"))
            if (url.isNotEmpty() && title.isNotEmpty() && title.length < 100 && seenUrls.add(url)) {
                if (isChapterTitleValid(title)) {
                    chapters.add(Chapter(bookUrl = bookUrl, title = title, url = url))
                }
            }
        }

        // 检查是否有分页
        val pagedUrls = doc.select("#pagelink select option, .pagination option, .pager select option")
            .map { resolveUrl(it.attr("value")) }
            .filter { it.isNotEmpty() && it != bookUrl && !it.contains("#") }

        val additionalChapters = if (pagedUrls.isNotEmpty()) {
            coroutineScope {
                pagedUrls.take(10).map { pageUrl ->
                    async { fetchAdditionalChapters(bookUrl, pageUrl, seenUrls) }
                }.awaitAll().flatten()
            }
        } else emptyList()

        (chapters + additionalChapters).distinctBy { it.url }
            .mapIndexed { index, chapter -> chapter.copy(index = index) }
    }

    private suspend fun fetchAdditionalChapters(
        bookUrl: String,
        pageUrl: String,
        globalSeenUrls: MutableSet<String>
    ): List<Chapter> {
        return try {
            val pageDoc = fetchHtmlDocument(pageUrl)
            val tempList = mutableListOf<Chapter>()
            // 先收集本页所有有效章节
            for (link in pageDoc.select("a[href*='.html']")) {
                val title = link.text().trim()
                val url = resolveUrl(link.attr("href"))
                if (url.isNotEmpty() && title.isNotEmpty() && isChapterTitleValid(title)) {
                    tempList.add(Chapter(bookUrl = bookUrl, title = title, url = url))
                }
            }
            // 返回前在外层调用处统一去重
            tempList
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isChapterTitleValid(title: String): Boolean {
        val invalidPatterns = listOf(
            "最新章节", "返回目录", "返回书页", "上一页", "下一页", "末页", "首页",
            "加入书架", "推荐投票", "打赏", "收藏", "笔趣阁", "求推荐", "报错"
        )
        for (pattern in invalidPatterns) {
            if (title.contains(pattern)) return false
        }
        if (title.length < 2 || title.length > 80) return false
        return true
    }

    override suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(chapterUrl)
            doc.select("script, style, noscript, iframe").remove()
            doc.select(".adsbygoogle, .ad, .advertisement, .chapter-error, .cont_error").remove()

            val contentSelectors = listOf(
                "#content", ".contentbox", "#htmlContent", ".BookText", "#BookText",
                ".chapter-content", ".read-content", ".articlecon", ".nr1", "#nr1",
                ".txt_cont", ".showtxt", ".bookcontent", ".txt"
            )

            var content = extractContentBySelectors(doc, contentSelectors)

            // 如果内容包含"本章未完"或分页提示，尝试合并多页
            if (content.contains("本章未完") || content.contains("下一页") ||
                doc.select("a:contains(下一页), #pagelink a:contains(下一页)").isNotEmpty()) {
                val pagedContent = mergePagedContent(chapterUrl, doc, contentSelectors)
                if (pagedContent.length > content.length) {
                    content = pagedContent
                }
            }

            if (content.isNotBlank()) {
                return@withContext content
            }

            // 最后的兜底策略
            val bodyTexts = doc.body()?.select("p, div")
                ?.map { it.text().trim() }
                ?.filter { it.length in 30..2000 && !looksLikeJavaScript(it) }
                ?: emptyList()

            if (bodyTexts.size >= 3) {
                return@withContext formatContent(bodyTexts.joinToString("\n\n"))
            }

            "【章节URL】\n$chapterUrl\n\n【正文】\n(无法解析章节内容，请尝试切换书源)"
        } catch (e: Exception) {
            "【章节URL】\n$chapterUrl\n\n【错误】\n${e.message}"
        }
    }

    private suspend fun mergePagedContent(
        baseUrl: String,
        firstDoc: org.jsoup.nodes.Document,
        contentSelectors: List<String>
    ): String {
        val content = StringBuilder()
        content.append(extractContentBySelectors(firstDoc, contentSelectors))

        // 找分页链接
        val pageLinks = firstDoc.select("#pagelink a, .pagination a, .paging a, .page-chapter a")
            .filter { a ->
                val text = a.text().trim()
                text.matches(Regex("""\d+""")) || text == "下一页"
            }
            .map { resolveUrl(it.attr("href")) }
            .distinct()
            .filter { it != baseUrl }

        for (pageUrl in pageLinks.take(5)) {
            try {
                val pageDoc = fetchHtmlDocument(pageUrl)
                val pageContent = extractContentBySelectors(pageDoc, contentSelectors)
                if (pageContent.isNotBlank()) {
                    content.append("\n\n").append(pageContent)
                }
            } catch (_: Exception) {
            }
        }
        return content.toString()
    }

    override suspend fun getPopularBooks(): List<Book> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(baseUrl)
            val books = mutableListOf<Book>()

            // 各种首页推荐位选择器
            val itemSelectors = listOf(
                ".hot_sale .bookbox", ".block .bookbox", ".paihang li", ".top-list li",
                ".rank-item", ".new-list li", ".remen li", ".book-list li",
                ".card-book, .book-card, .item"
            )

            var items = org.jsoup.select.Elements()
            for (selector in itemSelectors) {
                items = doc.select(selector)
                if (items.size >= 5) break
            }

            for (item in items.take(25)) {
                val link = item.selectFirst("a[href*='.html']:not(a[href*='/'], a[href~='\\d{5,}\\.html']), .bookname a, h3 a, h4 a, .title a")
                    ?: item.selectFirst("a")
                    ?: continue

                var title = link.attr("title").trim()
                if (title.isBlank()) title = link.text().trim()
                if (title.length < 2 || title.length > 50) continue

                val url = resolveUrl(link.attr("href"))
                if (!looksLikeBookUrl(url)) continue

                val author = item.selectFirst(".author, .writer, .book-aut, .s2")
                    ?.text()?.trim()
                    ?.replace("作者：", "")?.trim() ?: ""

                val cover = item.selectFirst("img")?.attr("src") ?: ""
                val coverUrl = resolveUrl(cover)

                if (books.none { it.url == url }) {
                    books.add(Book(url = url, title = title, author = author, coverUrl = coverUrl))
                }
            }

            if (books.size >= 5) return@withContext books.take(20)
        } catch (_: Exception) { }

        // 返回空列表，让上层使用其他书源的结果
        emptyList()
    }

    private fun looksLikeBookUrl(url: String): Boolean {
        // 书籍详情页通常是目录页，不是具体章节
        if (url.contains(Regex("""\d+\.html$"""))) return false
        // 需要包含基础域名的路径部分
        val domain = baseUrl.substringAfter("//").split("/").first()
        return url.contains(domain)
    }
}
