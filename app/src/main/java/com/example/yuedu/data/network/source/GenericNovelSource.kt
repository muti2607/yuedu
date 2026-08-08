package com.example.yuedu.data.network.source

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 塔读类通用书源适配器
 *
 * 网站: https://www.tadu.com (示例，可适配多种结构)
 * 提供更灵活的解析策略，适配多种结构的小说站
 */
class GenericNovelSource : BaseBookSource() {

    override val id = "generic_novel"
    override val name = "通用小说源"
    override val baseUrl = "https://www.xbiquwx.la"
    override val group = "通用系列"
    override var isEnabled = true

    override suspend fun searchBooks(keyword: String, page: Int): BookSource.SearchResult = withContext(Dispatchers.IO) {
        try {
            val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
            // 尝试多种搜索URL格式
            val doc = try {
                fetchHtmlDocumentPost(
                    "$baseUrl/search.php",
                    mapOf(
                        "keyword" to keyword,
                        "t" to "1",
                        "searchkey" to keyword
                    )
                )
            } catch (e: Exception) {
                fetchHtmlDocument("$baseUrl/s?q=$encodedKeyword&page=$page")
            }

            val books = parseGenericSearchResults(doc)
            val hasMore = checkHasMorePages(doc, page)
            BookSource.SearchResult(books, hasMore)
        } catch (e: Exception) {
            BookSource.SearchResult(emptyList())
        }
    }

    private fun parseGenericSearchResults(doc: org.jsoup.nodes.Document): List<Book> {
        val books = mutableListOf<Book>()

        // 遍历所有可能的搜索结果容器
        val containers = listOf(
            ".search-list", ".search-result", ".result-list", ".search-list-box",
            ".books-list", ".novel-list", ".list-works", ".bookresult"
        )

        var resultItems = doc.select("li.item, .item, li, .book-item, tr, .result-item, .list-item")

        // 如果找不到，尝试在整个body中找看起来像书籍结果的链接
        if (resultItems.isEmpty()) {
            resultItems = doc.select("a").map { link ->
                val parent = link.parent()
                parent ?: link
            }.distinct().let { org.jsoup.select.Elements(it) }
        }

        for (item in resultItems) {
            val allLinks = item.select("a[href]")
            if (allLinks.isEmpty()) continue

            val titleLink = allLinks.firstOrNull { link ->
                val text = link.text().trim()
                text.length in 2..50 && !text.contains("http") && !text.contains("下载")
            } ?: allLinks.first()

            val title = titleLink.attr("title").trim().ifBlank { titleLink.text().trim() }
            if (title.length < 2 || title.length > 60) continue

            val url = resolveUrl(titleLink.attr("href"))
            if (!isValidBookUrl(url)) continue

            // 在同级元素中找作者信息
            val author = item.select(".author, .writer, span:contains(作者)").firstOrNull()
                ?.text()?.trim()
                ?.replace(Regex(""".*作者[：:]"""), "")?.trim()
                ?: findAuthorFromSiblings(item, title)

            val cover = item.selectFirst("img[src]")?.attr("src")
                ?: titleLink.selectFirst("img")?.attr("src")
                ?: ""
            val coverUrl = resolveUrl(cover)

            val description = item.select(".intro, .desc, .summary, .description").firstOrNull()
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

        return books.distinctBy { it.url }.take(50)
    }

    private fun findAuthorFromSiblings(element: org.jsoup.nodes.Element, bookTitle: String): String {
        val siblings = element.parent()?.children() ?: element.siblingElements()
        for (sibling in siblings) {
            val text = sibling.text().trim()
            if (text.length in 2..20 && text != bookTitle &&
                !text.contains("http") && !text.contains("/") &&
                !text.contains("章") && !text.contains("页")) {
                return text
            }
        }
        return ""
    }

    private fun isValidBookUrl(url: String): Boolean {
        val domain = baseUrl.substringAfter("//").split("/").first()
        if (!url.contains(domain) && !url.startsWith("/") && !url.startsWith("./")) return false
        // 过滤掉明显是章节的URL
        if (url.matches(Regex(""".*/\d{4,}(_\d+)?\.html$"""))) return false
        return true
    }

    private fun checkHasMorePages(doc: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        val nextPage = doc.select("a:contains(下一页), .pagination .next, .page .next").firstOrNull()
        if (nextPage != null) return true
        val maxPage = doc.select(".pagination a, .page a").mapNotNull { a ->
            a.text().trim().toIntOrNull()
        }.maxOrNull() ?: 0
        return maxPage > currentPage
    }

    override suspend fun getBookDetail(bookUrl: String): Book = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(bookUrl)

        // 策略1：从元数据获取
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val title = ogTitle
            ?: doc.selectFirst("h1, .title, .book-title, #title, .book-name")?.text()?.trim()
            ?: doc.title().replace(Regex("""[（(_\-|/].*"""), "").trim()
            ?: "未知书籍"

        // 找作者 - 多种策略
        val author = findAuthor(doc)

        // 找描述
        val description = ogDescription
            ?: doc.selectFirst("#intro, .intro, .summary, .desc, .description, .book-summary, .synopsis")
                ?.text()?.trim()
                ?.replace(Regex("""^.*?简介[：:]\s*"""), "")?.trim()
            ?: ""

        // 找封面
        val coverUrl = resolveUrl(
            ogImage
                ?: doc.selectFirst(".cover img, #fmimg img, .book-cover img, .pic img, .img-box img, .book-img img")?.attr("src")
                ?: doc.selectFirst("img[src*=cover], img[src*=book]")?.attr("src")
                ?: ""
        )

        // 找分类
        val category = doc.selectFirst(".category, .sort, .con_top a:nth-child(2), .nav a:nth-child(2), .book-class")
            ?.text()?.trim()
            ?.replace("分类：", "")?.trim() ?: ""

        // 找最新章节
        val lastChapter = doc.selectFirst(
            ".last-chapter a, .newest a, .update a, #new-chapter a, .latest a, .book-new a"
        )?.text()?.trim()
            ?: doc.select("a[href*=.html]").lastOrNull { it.text().trim().length in 2..60 }?.text()?.trim()
            ?: ""

        // 找更新时间
        val lastUpdate = doc.select(
            ".update-time, .time, .last-update, #info p:contains(更新), .info p:contains(更新)"
        ).firstOrNull()?.text()?.trim()
            ?.replace(Regex(""".*?(更新时间|最后更新)[：:]"""), "")?.trim() ?: ""

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

    private fun findAuthor(doc: org.jsoup.nodes.Document): String {
        // 策略1: 明确的作者标签
        val authorSelectors = listOf(
            "#author", ".author", ".book-author", ".writer",
            ".info .small span", ".detail .author", ".bookinfo .author",
            "meta[name=author]"
        )
        for (selector in authorSelectors) {
            val el = doc.selectFirst(selector)
            val attrContent = el?.attr("content")?.trim()
            val textContent = el?.text()?.trim()
            val author = (attrContent ?: textContent)
                ?.replace(Regex("""^.*作者[：:]"""), "")?.trim()
            if (!author.isNullOrBlank() && author.length < 30) return author
        }

        // 策略2: 在包含"作者"文字的段落中
        val authorElements = doc.getElementsContainingOwnText("作者")
        for (el in authorElements.take(5)) {
            val text = el.text().trim()
            val match = Regex("""作者[：:]\s*([^\s，,。；;]+)""").find(text)
            if (match != null) return match.groupValues[1].trim()
        }

        return ""
    }

    override suspend fun getChapterList(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(bookUrl)
        val chapters = mutableListOf<Chapter>()
        val seenUrls = mutableSetOf<String>()

        // 找章节列表容器（选择a标签最多的容器）
        val allContainers = doc.select("dl, ul, ol, div.list, div.chapter-list, div.catalog")
        val bestContainer = allContainers.maxByOrNull { it.select("a[href*=.html]").size }
        val minChapterCount = 20

        val chapterLinks = if (bestContainer != null && bestContainer.select("a[href*=.html]").size >= minChapterCount) {
            bestContainer.select("a[href*=.html], a[href~=\\d+\\.html]")
        } else {
            // 从整个文档中筛选看起来像章节的链接
            doc.select("a[href]").filter { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                val isHtmlLink = href.endsWith(".html") || href.contains(Regex("""/\d+\.html"""))
                val isValidTitle = text.length in 2..80 &&
                    !text.contains("首页") && !text.contains("书架") &&
                    !text.contains("收藏") && !text.contains("下载") &&
                    !text.contains("返回") && !text.contains("上一页") && !text.contains("下一页")
                isHtmlLink && isValidTitle
            }.let { org.jsoup.select.Elements(it) }
        }

        for (link in chapterLinks) {
            val title = link.text().trim()
            val rawUrl = link.attr("href")
            val url = resolveUrl(rawUrl)
            if (url.isNotEmpty() && title.isNotEmpty() && seenUrls.add(url)) {
                chapters.add(Chapter(bookUrl = bookUrl, title = title, url = url))
            }
        }

        // 去重并排序（确保顺序正确）
        chapters.distinctBy { it.url }
            .mapIndexed { index, chapter -> chapter.copy(index = index) }
    }

    override suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(chapterUrl)

            // 移除不需要的元素
            doc.select("script, style, noscript, iframe").remove()
            doc.select(
                ".ads, .ad, .advertisement, .chapter-ads, .box-ad, .tip, .error, " +
                    ".report, .reward, .vote, .like, .share"
            ).remove()

            // 扩展的内容选择器列表
            val contentSelectors = listOf(
                "#content", ".content", "#BookText", ".BookText",
                ".chapter-content", ".read-content", ".chapter-body",
                ".nr1", "#nr1", ".txt", ".txts", ".article",
                ".show-content", ".cont-body", ".readout",
                ".bookcontent", "#htmlContent", ".article-text",
                ".novel-content", ".main-text"
            )

            val content = extractContentBySelectors(doc, contentSelectors)
            if (content.isNotBlank()) {
                return@withContext content
            }

            // 兜底：找页面中最大的文本块
            val largestBlock = findLargestTextBlock(doc)
            if (largestBlock != null && largestBlock.length > 300) {
                return@withContext formatContent(largestBlock)
            }

            "【章节URL】\n$chapterUrl\n\n【正文】\n(内容解析失败，请尝试其他书源)"
        } catch (e: Exception) {
            "【章节URL】\n$chapterUrl\n\n【错误】\n${e.message}"
        }
    }

    private fun findLargestTextBlock(doc: org.jsoup.nodes.Document): String? {
        var best: org.jsoup.nodes.Element? = null
        var bestTextLength = 0

        doc.allElements.forEach { el ->
            // 只考虑块级元素
            if (el.tagName() in listOf("div", "p", "article", "section", "td")) {
                val text = el.ownText().trim()
                if (text.length > bestTextLength && !looksLikeJavaScript(text)) {
                    // 检查子元素数量，避免选到body等太大的容器
                    val childCount = el.children().filter { it.tagName() != "br" }.size
                    if (childCount <= 20) {
                        best = el
                        bestTextLength = text.length
                    }
                }
            }
        }

        return best?.let { cleanHtmlContent(it.html()) }
    }

    override suspend fun getPopularBooks(): List<Book> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(baseUrl)
            val books = mutableListOf<Book>()

            // 提取首页所有可能是书籍卡片的链接
            val candidates = doc.select("a").mapNotNull { link ->
                val href = link.attr("href")
                val url = resolveUrl(href)
                val title = link.attr("title").trim().ifBlank { link.text().trim() }
                val parent = link.parent()

                if (title.length in 2..50 && isValidBookUrl(url) && url.contains(baseUrl.substringAfter("//").split("/").first())) {
                    Triple(url, title, parent)
                } else null
            }.distinctBy { it.first }

            for ((url, title, parent) in candidates.take(40)) {
                val cover = parent?.selectFirst("img")?.attr("src")
                    ?: linkToImg(url, doc)
                val coverUrl = resolveUrl(cover ?: "")

                val author = parent?.selectFirst(".author, .writer, span")
                    ?.text()?.trim()
                    ?.replace("作者：", "")?.trim() ?: ""

                if (books.none { it.url == url } && books.size < 20) {
                    books.add(
                        Book(
                            url = url,
                            title = title,
                            author = author,
                            coverUrl = coverUrl
                        )
                    )
                }
            }

            return@withContext books
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun linkToImg(bookUrl: String, doc: org.jsoup.nodes.Document): String? {
        // 尝试找同一个父容器中与这个URL相邻的img
        val link = doc.selectFirst("a[href='${bookUrl.substringAfter(baseUrl)}']")
        return link?.parent()?.selectFirst("img")?.attr("src")
    }
}
