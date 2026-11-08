package com.example.yuedu.data.network.site

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import org.jsoup.nodes.Document

/**
 * 书源：天地中文网（www.tiandizw.com）
 *
 * 实测全链路免费可读（2026-11）：
 * - 搜索：search.php/newindex/{URL编码关键词}，结果里 a[href*=newbookinfo] 即书籍
 *   （该站搜索结果偏宽松，会混入通用书单，故搜索结果需按关键词再过滤）
 * - 详情：newbookinfo/{id}.html
 * - 目录：newbooklist/{id}.html，章节链接 /newcontent/{bid}/{cid}.html
 * - 正文：/newcontent/{bid}/{cid}.html，正文在 p 标签里，免费
 */
class TiandiSite : BookSite() {

    override val name = "天地中文网"
    override val baseUrl = "http://www.tiandizw.com"

    /** 搜索页 URL（供 HtmlParser 调用） */
    fun searchUrl(keyword: String): String =
        "$baseUrl/search.php/newindex/" + java.net.URLEncoder.encode(keyword, "UTF-8")

    private fun bookId(url: String): String? =
        Regex("""newbookinfo/(\d+)|newbooklist/(\d+)|newcontent/(\d+)""").find(url)?.let {
            it.groupValues.drop(1).firstOrNull { g -> g.isNotEmpty() }
        }

    override fun parseBookDetail(doc: Document, bookUrl: String): Book {
        val title = doc.title()
            .replace(Regex("""[（(,_|].*"""), "")
            .replace(Regex("""\s*最新章节.*"""), "")
            .trim()
            .ifBlank { doc.selectFirst("h1, .bookname, .book-title")?.text()?.trim().orEmpty() }
        val author = doc.select(".bookinfo span:contains(作者) a, .author a, a[href*=newauthor]")
            .firstOrNull()?.text()?.trim()
            ?: Regex("""作者[：:]\s*([^<\s]+)""").find(doc.html())?.groupValues?.get(1)?.trim()
            ?: ""
        val description = doc.selectFirst(".bookinfo .intro, .book_intro, #bookIntro, .desc")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst(".bookimg img, .book_info img, img[src*=bookimg]")?.attr("src").orEmpty()
        val category = doc.select(".bookinfo span:contains(类别), .bookinfo span:contains(类型)")
            .firstOrNull()?.text()?.trim()?.replace("类别：", "")?.replace("类型：", "")?.trim() ?: ""
        val lastChapter = doc.select("a[href*=/newcontent/]").lastOrNull()?.text()?.trim() ?: ""
        return Book(
            url = bookUrl,
            title = title,
            author = author,
            description = description,
            coverUrl = resolveUrl(cover),
            category = category,
            lastChapterTitle = lastChapter
        )
    }

    override fun parseChapterList(doc: Document, bookUrl: String): List<Chapter> {
        val seen = mutableSetOf<String>()
        val list = doc.select("a[href*=/newcontent/]").mapNotNull { a ->
            val url = resolveUrl(a.attr("href"), bookUrl)
            if (!Regex("""/newcontent/\d+/\d+\.html""").containsMatchIn(url)) return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            val title = a.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            Chapter(bookUrl = bookUrl, title = title, url = url)
        }
        // 详情页通常只有最新章节链接，完整目录在 newbooklist 页
        return if (list.size < 10) emptyList() else list
    }

    override fun catalogUrl(bookUrl: String): String {
        val bid = bookId(bookUrl) ?: return bookUrl
        return "$baseUrl/newbooklist/$bid.html"
    }

    override fun parseChapterContent(doc: Document, chapterUrl: String): String {
        val picked = extractParagraphs(doc)
        if (picked.isNotBlank()) return picked
        val body = doc.selectFirst("#booktext, .book-content, #content, .content") ?: doc.body() ?: return ""
        return cleanContent(body.html())
    }

    override fun nextChapterUrl(doc: Document, currentChapterUrl: String): String? {
        val a = doc.select("a:contains(下一章), a:contains(下一页)").firstOrNull() ?: return null
        val url = resolveUrl(a.attr("href"), currentChapterUrl)
        return url.takeIf { it.contains("/newcontent/") }
    }

    override fun prevChapterUrl(doc: Document, currentChapterUrl: String): String? {
        val a = doc.select("a:contains(上一章), a:contains(上一页)").firstOrNull() ?: return null
        val url = resolveUrl(a.attr("href"), currentChapterUrl)
        return url.takeIf { it.contains("/newcontent/") }
    }
}
