package com.example.yuedu.data.network.source

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 22笔趣阁书源实现
 *
 * 网站: https://m.22biqu.net
 * 支持功能: 搜索、详情、章节列表、正文、热门推荐
 */
class Biqu22Source : BaseBookSource() {

    override val id = "biqu_22"
    override val name = "22笔趣阁"
    override val baseUrl = "https://m.22biqu.net"
    override val group = "笔趣阁系列"
    override var isEnabled = true

    private val fallbackBooks = listOf(
        Book(url = "$baseUrl/biqu6586/", title = "乱世书", author = "姬叉", coverUrl = "",
            description = "仗剑江湖载酒行，楚腰纤细掌中轻。少年肩扛长刀，腰间挂酒，大步前行。", category = "玄幻魔法"),
        Book(url = "$baseUrl/biqu2446/", title = "宇宙职业选手", author = "我吃西红柿", coverUrl = "",
            description = "2036年，人类第一次登上了荧火星。", category = "玄幻魔法"),
        Book(url = "$baseUrl/biqu5456/", title = "请公子斩妖", author = "裴不了", coverUrl = "",
            description = "巍巍人间，九天十地。花花世界，快乐修仙。", category = "玄幻魔法"),
        Book(url = "$baseUrl/biqu5251/", title = "宿命之环", author = "爱潜水的乌贼", coverUrl = "",
            description = "蒸汽与机械的浪潮中，谁能触及非凡？", category = "玄幻魔法"),
        Book(url = "$baseUrl/biqu3969/", title = "混沌幽莲空间", author = "未知", coverUrl = "",
            description = "", category = "玄幻魔法"),
        Book(url = "$baseUrl/biqu57875/", title = "重生商纣", author = "未知", coverUrl = "",
            description = "", category = "玄幻魔法"),
        Book(url = "$baseUrl/biqu5708/", title = "刚成仙神，子孙求我出山", author = "未知", coverUrl = "",
            description = "", category = "玄幻魔法")
    )

    override suspend fun searchBooks(keyword: String, page: Int): BookSource.SearchResult = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocumentPost(
                "$baseUrl/ss/",
                mapOf("searchkey" to keyword)
            )
            val books = parseSearchResults(doc)
            BookSource.SearchResult(books)
        } catch (e: Exception) {
            BookSource.SearchResult(emptyList())
        }
    }

    private fun parseSearchResults(doc: org.jsoup.nodes.Document): List<Book> {
        val books = mutableListOf<Book>()
        val items = doc.select(".read_book .bookbox")
        for (item in items) {
            val nameLink = item.selectFirst(".bookinfo .bookname a") ?: continue
            val title = nameLink.text().trim()
            if (title.length < 2 || title.length > 50) continue
            val url = resolveUrl(nameLink.attr("href"))
            val author = item.selectFirst(".bookinfo .author")?.text()?.trim()
                ?.replace("作者：", "")?.trim() ?: ""
            val cover = item.selectFirst(".bookimg img")?.attr("src") ?: ""
            val coverUrl = resolveUrl(cover)
            if (books.none { it.url == url }) {
                books.add(Book(url = url, title = title, author = author, coverUrl = coverUrl))
            }
        }
        return books
    }

    override suspend fun getBookDetail(bookUrl: String): Book = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(bookUrl)
        val title = doc.title().replace(Regex("""\(.*"""), "").replace(Regex("""[（(].*"""), "").trim()
        val author = doc.select(".synopsisArea_detail b.author").firstOrNull()?.text()?.trim()
            ?.replace("作者：", "")?.trim() ?: ""
        val description = doc.select(".synopsisArea .review p").text().trim()
        val cover = doc.select(".synopsisArea_detail img").firstOrNull()?.attr("src")
            ?: doc.select(".bookimg img").firstOrNull()?.attr("src")
            ?: doc.select("#bookimg img").firstOrNull()?.attr("src")
            ?: doc.select("img[src*=/bookimg/]").firstOrNull()?.attr("src")
            ?: doc.select("meta[property=og:image]").firstOrNull()?.attr("content")
            ?: doc.select("img[src*=book]").firstOrNull()?.attr("src")
            ?: ""
        val coverUrl = resolveUrl(cover)
        val lastChapter = doc.select(".synopsisArea_detail .lastchapter a").firstOrNull()?.text()?.trim() ?: ""
        val category = doc.select(".synopsisArea_detail p.sort").firstOrNull()?.text()?.trim()
            ?.replace("类别：", "")?.trim() ?: ""
        Book(
            url = bookUrl,
            title = title,
            author = author,
            description = description,
            coverUrl = coverUrl,
            category = category,
            lastChapterTitle = lastChapter
        )
    }

    override suspend fun getChapterList(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        val seenUrls = mutableSetOf<String>()
        val doc = fetchHtmlDocument(bookUrl)
        val chapterElements = selectChapterElements(doc)
        if (chapterElements.isEmpty()) {
            return@withContext emptyList()
        }

        val firstPageChapters = chapterElements.mapNotNull { element ->
            val title = element.text().trim()
            val url = resolveUrl(element.attr("href"))
            if (url.isNotEmpty() && title.isNotEmpty() && seenUrls.add(url)) {
                Chapter(bookUrl = bookUrl, title = title, url = url)
            } else null
        }

        val optionUrls = doc.select("#indexselect option").map { resolveUrl(it.attr("value")) }
            .filter { it.isNotEmpty() && it != bookUrl }

        val additionalChapters = when {
            optionUrls.isNotEmpty() -> {
                coroutineScope {
                    optionUrls.map { pageUrl ->
                        async { fetchChapterPageFromUrl(bookUrl, pageUrl) }
                    }.awaitAll().flatten()
                }
            }
            else -> {
                val maxPage = findMaxPageNumber(doc)
                if (maxPage > 1) {
                    coroutineScope {
                        (2..maxPage).map { p ->
                            async {
                                val pageUrl = buildPageUrl(bookUrl, p) ?: return@async emptyList()
                                fetchChapterPageFromUrl(bookUrl, pageUrl)
                            }
                        }.awaitAll().flatten()
                    }
                } else {
                    val result = mutableListOf<Chapter>()
                    var p = 2
                    while (p <= 50) {
                        val pageUrl = buildPageUrl(bookUrl, p) ?: break
                        val pageChapters = fetchChapterPageFromUrl(bookUrl, pageUrl)
                        if (pageChapters.isEmpty()) break
                        result.addAll(pageChapters)
                        p++
                    }
                    result
                }
            }
        }

        val allChapters = (firstPageChapters + additionalChapters).distinctBy { it.url }
        allChapters.mapIndexed { index, chapter ->
            chapter.copy(index = index)
        }
    }

    private fun selectChapterElements(doc: org.jsoup.nodes.Document): org.jsoup.select.Elements {
        val allDirectories = doc.select(".directoryArea")
        val mainDirectory = allDirectories.maxByOrNull { it.select("p a").size }
        return mainDirectory?.select("p a") ?: org.jsoup.select.Elements()
    }

    private suspend fun fetchChapterPageFromUrl(bookUrl: String, pageUrl: String): List<Chapter> {
        return try {
            val pageDoc = fetchHtmlDocument(pageUrl)
            val pageElements = selectChapterElements(pageDoc)
            if (pageElements.isEmpty()) return emptyList()
            pageElements.mapNotNull { element ->
                val title = element.text().trim()
                val url = resolveUrl(element.attr("href"))
                if (url.isNotEmpty() && title.isNotEmpty()) {
                    Chapter(bookUrl = bookUrl, title = title, url = url)
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findMaxPageNumber(doc: org.jsoup.nodes.Document): Int {
        var maxPage = 1
        val options = doc.select("#indexselect option")
        for (option in options) {
            val pageNum = option.text().trim().toIntOrNull()
            if (pageNum != null && pageNum > maxPage) maxPage = pageNum
        }
        if (maxPage <= 1) {
            val pageLinks = doc.select(".page a, .pagelist a, .pagination a, .paginator a, .index-container-btn, .directoryArea .page a")
            for (link in pageLinks) {
                val pageNum = link.text().trim().toIntOrNull()
                if (pageNum != null && pageNum > maxPage) maxPage = pageNum
            }
        }
        return maxPage
    }

    private fun buildPageUrl(bookUrl: String, page: Int): String? {
        if (page <= 1) return null
        return "${bookUrl.trimEnd('/')}/$page/"
    }

    override suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val allContent = StringBuilder()
            var pageNum = 1
            var currentUrl = chapterUrl

            while (true) {
                val doc = fetchHtmlDocument(currentUrl)
                doc.select("script, style, noscript, iframe, link[rel=stylesheet]").remove()

                val pageContent = extractContentBySelectors(doc, listOf(
                    "#chaptercontent", "#content", "#nr1", "#BookText",
                    ".ReadAjax_content", ".showtxt", ".content", ".txt",
                    "article", ".article", ".read-content", ".book-content"
                ))

                if (pageNum == 1 && pageContent.isBlank()) {
                    return@withContext "【章节URL】\n$chapterUrl\n\n【正文】\n(无法解析章节内容，请检查页面结构)"
                }

                allContent.append(pageContent)

                val hasNextPage = doc.select("a:contains(下一页)").isNotEmpty() ||
                    doc.select(".Readpage a:contains(下一页)").isNotEmpty() ||
                    doc.select(".page a:contains(下一页)").isNotEmpty()

                if (!hasNextPage) break
                pageNum++
                currentUrl = buildSubPageUrl(chapterUrl, pageNum)
            }

            if (allContent.isBlank()) {
                return@withContext "【章节URL】\n$chapterUrl\n\n【正文】\n(无法解析章节内容，请检查页面结构)"
            }
            allContent.toString()
        } catch (e: Exception) {
            "【章节URL】\n$chapterUrl\n\n【错误】\n${e.message}"
        }
    }

    private fun buildSubPageUrl(baseUrl: String, pageNum: Int): String {
        if (pageNum <= 1) return baseUrl
        return baseUrl.replace(".html", "_$pageNum.html")
    }

    override suspend fun getPopularBooks(): List<Book> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchHtmlDocument(baseUrl)
            val books = mutableListOf<Book>()
            val items = doc.select(".hot_sale a, .slide-item a")
            for (item in items) {
                val title = item.selectFirst("p.title, span.title")?.text()?.trim()
                    ?: item.ownText().trim()
                if (title.length < 2 || title.length > 50) continue
                val url = resolveUrl(item.attr("href"))
                if (!url.contains(baseUrl.substringAfter("//").split("/").first())) continue
                val author = item.selectFirst("p.author")?.text()?.trim()
                    ?.replace("作者：", "")?.trim() ?: ""
                val cover = item.selectFirst("img")?.attr("src") ?: ""
                val coverUrl = resolveUrl(cover)
                if (books.none { it.url == url }) {
                    books.add(Book(url = url, title = title, author = author, coverUrl = coverUrl))
                }
                if (books.size >= 20) break
            }
            if (books.isNotEmpty()) return@withContext books.take(20)
        } catch (_: Exception) { }
        fallbackBooks
    }
}
