package com.example.yuedu.data.network.site

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import org.jsoup.nodes.Document

/**
 * 书源：香网小说网（www.xiang5.com）
 *
 * 实测全链路免费可读（2026-11）：
 * - 搜索：search.php?keyword=xxx，结果里 a[href*=bookinfo] 即书籍
 * - 详情：bookinfo/{id}.html 会 302 到 book.php?bookid=xxx，并种 www_say/PHPSESSID
 *   cookie；**必须带 cookie 再请求**，否则无限重定向（该站反爬）。
 *   SharedHttp 的 client 已配置 CookieJar，会自动维持会话。
 * - 目录：booklist/{id}.html，章节链接 /content/{bid}/{cid}.html
 * - 正文：/content/{bid}/{cid}.html，正文在 p 标签里，免费
 */
class Xiang5Site : BookSite() {

    override val name = "香网小说"
    override val baseUrl = "http://www.xiang5.com"

    /** 搜索页 URL（供 HtmlParser 调用） */
    fun searchUrl(keyword: String): String =
        "$baseUrl/search.php?keyword=" + java.net.URLEncoder.encode(keyword, "UTF-8")

    /** 从书籍 URL 里取 bookId */
    private fun bookId(url: String): String? =
        Regex("""bookinfo/(\d+)|bookid=(\d+)|booklist/(\d+)|content/(\d+)""").find(url)?.let {
            it.groupValues.drop(1).firstOrNull { g -> g.isNotEmpty() }
        }

    override fun parseBookDetail(doc: Document, bookUrl: String): Book {
        val title = doc.title()
            .replace(Regex("""[（(].*"""), "")
            .replace(Regex("""[_|].*"""), "")
            .trim()
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
        val author = doc.select(".book_info span:contains(作者) a, .detail_author, .author a")
            .firstOrNull()?.text()?.trim()
            ?: Regex("""作者[：:]\s*([^<\s]+)""").find(doc.html())?.groupValues?.get(1)?.trim()
            ?: ""
        val description = doc.selectFirst(".book_intro, .intro, .book_dec, #bookIntro")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst(".book_img img, .bookcover img, img[src*=cover]")?.attr("src").orEmpty()
        val category = doc.select(".book_info span:contains(类别), .sort a").firstOrNull()?.text()?.trim()
            ?.replace("类别：", "")?.trim() ?: ""
        val status = doc.select(".book_info span:contains(状态)").firstOrNull()?.text()?.trim()
            ?.substringAfter("：")?.trim() ?: ""
        val lastChapter = doc.select("a[href*=/content/]").lastOrNull()?.text()?.trim() ?: ""
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
        val seen = mutableSetOf<String>()
        val list = doc.select("a[href*=/content/]").mapNotNull { a ->
            val url = resolveUrl(a.attr("href"), bookUrl)
            if (!Regex("""/content/\d+/\d+\.html""").containsMatchIn(url)) return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            val title = a.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            Chapter(bookUrl = bookUrl, title = title, url = url)
        }
        // 详情页只有「最新章节」等 1~2 个链接，完整目录在 booklist 页。
        // 章节太少时返回空，触发 SiteManager 换 catalogUrl 抓完整目录。
        return if (list.size < 10) emptyList() else list
    }

    /** 目录页单独一页 */
    override fun catalogUrl(bookUrl: String): String {
        val bid = bookId(bookUrl) ?: return bookUrl
        return "$baseUrl/booklist/$bid.html"
    }

    override fun parseChapterContent(doc: Document, chapterUrl: String): String {
        // 香网正文容器 class 在重定向前后不稳定，直接按段落特征从整页挑正文，
        // 天然跳过「作品/主页/目录」等导航短词。
        val picked = extractParagraphs(doc)
        if (picked.isNotBlank()) return picked
        val body = doc.selectFirst(".readDetailbody, #content, .content") ?: doc.body() ?: return ""
        return cleanContent(body.html())
    }

    override fun nextChapterUrl(doc: Document, currentChapterUrl: String): String? {
        val a = doc.select("a:contains(下一章), a:contains(下一页)").firstOrNull() ?: return null
        val url = resolveUrl(a.attr("href"), currentChapterUrl)
        return url.takeIf { it.contains("/content/") }
    }

    override fun prevChapterUrl(doc: Document, currentChapterUrl: String): String? {
        val a = doc.select("a:contains(上一章), a:contains(上一页)").firstOrNull() ?: return null
        val url = resolveUrl(a.attr("href"), currentChapterUrl)
        return url.takeIf { it.contains("/content/") }
    }
}
