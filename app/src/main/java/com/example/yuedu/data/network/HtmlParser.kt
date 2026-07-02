package com.example.yuedu.data.network

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

object HtmlParser {

    private const val BASE_URL = "https://m.22biqu.net"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val COOKIE = ""

    private fun buildChapterUrl(bookId: String, chapterNum: Int): String {
        return "$BASE_URL/$bookId/$chapterNum.html"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class SearchResult(
        val books: List<Book>,
        val hasMore: Boolean = false
    )

    suspend fun searchBooks(keyword: String): SearchResult = withContext(Dispatchers.IO) {
        try {
            val doc = fetchSearchResultDocument(keyword)
            val books = parseHtmlSearchResults(doc)
            return@withContext SearchResult(books)
        } catch (e: Exception) {
            return@withContext SearchResult(emptyList())
        }
    }

    private suspend fun fetchSearchResultDocument(keyword: String): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        val body = okhttp3.FormBody.Builder()
            .add("searchkey", keyword)
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/ss/")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", COOKIE)
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        val respBody = response.body?.bytes() ?: throw Exception("Empty response")
        val charset = detectCharset(response)
        Jsoup.parse(String(respBody, java.nio.charset.Charset.forName(charset)), "$BASE_URL/ss/")
    }

    private fun parseHtmlSearchResults(doc: org.jsoup.nodes.Document): List<Book> {
        val books = mutableListOf<Book>()
        val items = doc.select(".read_book .bookbox")
        for (item in items) {
            val nameLink = item.selectFirst(".bookinfo .bookname a")
            val title = nameLink?.text()?.trim() ?: continue
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

    suspend fun getBookDetail(bookUrl: String): Book = withContext(Dispatchers.IO) {
        getBookDetailFallback(bookUrl)
    }

    private suspend fun getBookDetailFallback(bookUrl: String): Book = withContext(Dispatchers.IO) {
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
        Book(url = bookUrl, title = title, author = author, description = description, coverUrl = coverUrl, category = category, lastChapterTitle = lastChapter)
    }

    suspend fun getChapterList(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        getChapterListFallback(bookUrl)
    }

    private suspend fun getChapterListFallback(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
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
    val additionalChapters = if (optionUrls.isNotEmpty()) {
        coroutineScope {
            optionUrls.map { pageUrl ->
                async {
                    fetchChapterPageFromUrl(bookUrl, pageUrl)
                }
            }.awaitAll().flatten()
        }
    } else {
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
        val text = option.text().trim()
        val pageNum = text.toIntOrNull()
        if (pageNum != null && pageNum > maxPage) {
            maxPage = pageNum
        }
    }

    if (maxPage <= 1) {
        val pageLinks = doc.select(".page a, .pagelist a, .pagination a, .paginator a, .index-container-btn, .directoryArea .page a")
        for (link in pageLinks) {
            val text = link.text().trim()
            val pageNum = text.toIntOrNull()
            if (pageNum != null && pageNum > maxPage) {
                maxPage = pageNum
            }
        }
    }

    if (maxPage <= 1) {
        val nextLink = doc.select("a.index-container-btn:contains(下一页)").firstOrNull()
            ?: doc.select("a:contains(下一页)").firstOrNull()
            ?: doc.select("a:contains(下页)").firstOrNull()
        if (nextLink != null) {
            val href = nextLink.attr("href")
            val match = Regex("""/(\d+)/?$""").find(href)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null && num > maxPage) {
                    maxPage = num
                }
            }
        }
    }

    if (maxPage <= 1) {
        val lastLink = doc.select("a:contains(末页)").firstOrNull()
            ?: doc.select("a:contains(尾页)").firstOrNull()
        if (lastLink != null) {
            val href = lastLink.attr("href")
            val match = Regex("""/(\d+)/?$""").find(href)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null && num > maxPage) {
                    maxPage = num
                }
            }
        }
    }

    return maxPage
}

private fun buildPageUrl(bookUrl: String, page: Int): String? {
    val trimmedUrl = bookUrl.trimEnd('/')
    if (page <= 1) return null
    return "$trimmedUrl/$page/"
}

    suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val allContent = StringBuilder()
            var pageNum = 1
            var currentUrl = chapterUrl

            while (true) {
                val doc = fetchHtmlDocument(currentUrl)
                doc.select("script, style, noscript, iframe, link[rel=stylesheet]").remove()

                val pageContent = extractContentFromDoc(doc)

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
            return@withContext allContent.toString()
        } catch (e: Exception) {
            "【章节URL】\n$chapterUrl\n\n【错误】\n${e.message}"
        }
    }

    private fun buildSubPageUrl(baseUrl: String, pageNum: Int): String {
        if (pageNum <= 1) return baseUrl
        return baseUrl.replace(".html", "_$pageNum.html")
    }

    private fun extractContentFromDoc(doc: org.jsoup.nodes.Document): String {
        val contentSelectors = listOf(
            "#chaptercontent", "#content", "#nr1", "#BookText",
            ".ReadAjax_content", ".showtxt", ".content", ".txt",
            "article", ".article", ".read-content", ".book-content"
        )
        for (selector in contentSelectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                val bodyText = cleanHtmlContent(element.html())
                if (bodyText.isNotBlank() && !looksLikeJavaScript(bodyText)) {
                    return bodyText
                }
            }
        }

        val bodyText = doc.body()?.wholeText()?.trim() ?: ""
        if (bodyText.isNotBlank() && !looksLikeJavaScript(bodyText) && bodyText.length > 100) {
            val paragraphs = bodyText.split("\n").filter { it.isNotBlank() }
            return paragraphs.joinToString("\n\n") { "　　$it" }
        }

        return ""
    }

    private fun cleanHtmlContent(html: String): String {
        var content = html
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace("</p>", "\n\n")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        content = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(content, "")
        content = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(content, "")
        content = Regex("<[^>]+>").replace(content, " ")
        content = content.replace("&nbsp;", " ")
        content = content.replace("\\s{2,}".toRegex(), "\n\n").trim()
        return content
    }

    private fun looksLikeJavaScript(text: String): Boolean {
        if (text.isBlank()) return false
        val jsKeywords = listOf("function(", "var ", "jQuery", "typeof ", "=== ", "!== ",
            "return ", "document.", "window.", "module.exports", "hasOwnProperty")
        var matchCount = 0
        for (kw in jsKeywords) {
            if (text.contains(kw)) matchCount++
        }
        if (matchCount >= 5) return true
        if (text.contains("function(") && text.length > 2000) return true
        return false
    }

    suspend fun getPopularBooks(): List<Book> = withContext(Dispatchers.IO) {
        try {
            val htmlBooks = getPopularBooksFallback()
            if (htmlBooks.isNotEmpty() && htmlBooks.any { it.title.isNotBlank() }) {
                return@withContext htmlBooks.take(20)
            }
        } catch (_: Exception) { }
        fallbackBooks.map { it.copy(coverUrl = generateCoverUrl(it.url)) }
    }

    private suspend fun getPopularBooksFallback(): List<Book> = withContext(Dispatchers.IO) {
        val doc = fetchHtmlDocument(BASE_URL)
        val books = mutableListOf<Book>()
        val items = doc.select(".hot_sale a, .slide-item a")
        for (item in items) {
            val title = item.selectFirst("p.title, span.title")?.text()?.trim()
                ?: item.ownText().trim()
            if (title.length < 2 || title.length > 50) continue
            val url = resolveUrl(item.attr("href"))
            if (!url.contains(BASE_URL)) continue
            val author = item.selectFirst("p.author")?.text()?.trim()
                ?.replace("作者：", "")?.trim() ?: ""
            val cover = item.selectFirst("img")?.attr("src") ?: ""
            val coverUrl = resolveUrl(cover)
            if (books.none { it.url == url }) {
                books.add(Book(url = url, title = title, author = author, coverUrl = coverUrl))
            }
            if (books.size >= 20) break
        }
        if (books.isNotEmpty()) return@withContext books
        fallbackBooks.map { it.copy(coverUrl = generateCoverUrl(it.url)) }
    }

    private fun extractBookId(url: String): String {
        val regex = Regex("/([^/]+)/\\d*\\.html")
        val match = regex.find(url)
        if (match != null) return match.groupValues[1]

        val regex2 = Regex("/([^/]+)/?$")
        val match2 = regex2.find(url.trimEnd('/'))
        if (match2 != null) {
            val id = match2.groupValues[1]
            if (id != BASE_URL.substringAfter("//").split("/").firstOrNull()) {
                return id
            }
        }
        return ""
    }

    private fun extractChapterNum(url: String): Int {
        val regex = Regex("/(\\d+)\\.html")
        return regex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun estimateChapterCount(doc: org.jsoup.nodes.Document): Int {
        val lastLink = doc.select(".directoryArea p a").lastOrNull()
        if (lastLink != null) {
            val match = Regex("/(\\d+)\\.html").find(lastLink.attr("href"))
            if (match != null) return match.groupValues[1].toInt()
        }
        return 100
    }

    private fun generateCoverUrl(bookUrlOrId: String): String {
        return ""
    }

    fun resolveUrl(path: String): String {
        if (path.isBlank()) return ""
        if (path.startsWith("http")) return path
        if (path.startsWith("//")) return "https:$path"
        if (path.contains(".") && path.contains("/") && !path.startsWith("/")) {
            return "https://$path"
        }
        return BASE_URL.trimEnd('/') + path
    }

    fun getFallbackBooks(): List<Book> = fallbackBooks.map { book ->
        if (book.coverUrl.isBlank()) book.copy(coverUrl = generateCoverUrl(book.url))
        else book
    }

    private suspend fun fetchHtmlDocument(url: String): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        val fetchUrl = url.replace("/#/", "/")
        val request = Request.Builder()
            .url(fetchUrl)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", COOKIE)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.bytes() ?: throw Exception("Empty response")
        val charset = detectCharset(response)
        Jsoup.parse(String(body, java.nio.charset.Charset.forName(charset)), url)
    }

    private fun detectCharset(response: okhttp3.Response): String {
        val contentType = response.header("Content-Type", "text/html") ?: "text/html"
        return if (contentType.contains("charset=")) {
            contentType.substringAfter("charset=").trim()
        } else {
            "UTF-8"
        }
    }

    private val fallbackBooks = listOf(
        Book(url = "$BASE_URL/biqu6586/", title = "乱世书", author = "姬叉", coverUrl = "", description = "仗剑江湖载酒行，楚腰纤细掌中轻。少年肩扛长刀，腰间挂酒，大步前行。", category = "玄幻魔法"),
        Book(url = "$BASE_URL/biqu2446/", title = "宇宙职业选手", author = "我吃西红柿", coverUrl = "", description = "2036年，人类第一次登上了荧火星。", category = "玄幻魔法"),
        Book(url = "$BASE_URL/biqu5456/", title = "请公子斩妖", author = "裴不了", coverUrl = "", description = "巍巍人间，九天十地。花花世界，快乐修仙。", category = "玄幻魔法"),
        Book(url = "$BASE_URL/biqu5251/", title = "宿命之环", author = "爱潜水的乌贼", coverUrl = "", description = "蒸汽与机械的浪潮中，谁能触及非凡？", category = "玄幻魔法"),
        Book(url = "$BASE_URL/biqu3969/", title = "混沌幽莲空间", author = "未知", coverUrl = "", description = "", category = "玄幻魔法"),
        Book(url = "$BASE_URL/biqu57875/", title = "重生商纣", author = "未知", coverUrl = "", description = "", category = "玄幻魔法"),
        Book(url = "$BASE_URL/biqu5708/", title = "刚成仙神，子孙求我出山", author = "未知", coverUrl = "", description = "", category = "玄幻魔法")
    )
}