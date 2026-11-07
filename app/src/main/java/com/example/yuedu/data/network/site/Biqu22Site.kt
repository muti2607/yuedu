package com.example.yuedu.data.network.site

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import org.jsoup.nodes.Document

/**
 * 书源：m.22biqu.net（项目原有书源，保留以兼容已入库的旧 URL）
 *
 * 结构（沿用原有解析规则）：
 * - 详情/目录同页，目录在 .directoryArea 的 p a 里，分页用 #indexselect option
 * - 正文在 #chaptercontent / #content / #nr1 等容器里，多页为 xxx_2.html
 */
class Biqu22Site : BookSite() {

    override val name = "笔趣阁(m.22biqu.net)"
    override val baseUrl = "https://m.22biqu.net"

    override fun parseBookDetail(doc: Document, bookUrl: String): Book {
        val title = doc.title().replace(Regex("""\(.*"""), "").replace(Regex("""[（(].*"""), "").trim()
        val author = doc.select(".synopsisArea_detail b.author").firstOrNull()?.text()?.trim()
            ?.replace("作者：", "")?.trim() ?: ""
        val description = doc.select(".synopsisArea .review p").text().trim()
        val cover = doc.select(".synopsisArea_detail img").firstOrNull()?.attr("src")
            ?: doc.select(".bookimg img").firstOrNull()?.attr("src")
            ?: doc.select("#bookimg img").firstOrNull()?.attr("src")
            ?: doc.select("img[src*=/bookimg/]").firstOrNull()?.attr("src")
            ?: doc.select("meta[property=og:image]").firstOrNull()?.attr("content")
            ?: ""
        val lastChapter = doc.select(".synopsisArea_detail .lastchapter a").firstOrNull()?.text()?.trim() ?: ""
        val category = doc.select(".synopsisArea_detail p.sort").firstOrNull()?.text()?.trim()
            ?.replace("类别：", "")?.trim() ?: ""
        // 状态：页面里的「状态：连载中」
        val status = doc.select(".synopsisArea_detail p:contains(状态)").firstOrNull()?.text()?.trim()
            ?.substringAfter("：")?.trim() ?: ""
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
        val container = doc.select(".directoryArea").maxByOrNull { it.select("p a").size }
            ?: return emptyList()
        return container.select("p a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (href.isEmpty() || title.isEmpty()) return@mapNotNull null
            Chapter(bookUrl = bookUrl, title = title, url = resolveUrl(href, bookUrl))
        }
    }

    /**
     * 目录分页：详情页 #indexselect option 里给出其它页地址。
     *
     * 站点约定：目录第 2 页是 index_2.html（不是 /2/），
     * 若页面里没有下拉选项则按该规则推断。
     */
    override fun chapterListPageUrl(bookUrl: String, page: Int): String? {
        if (page <= 1) return null
        val trimmed = bookUrl.trimEnd('/')
        return "$trimmed/index_$page.html"
    }

    override fun contentPageUrl(chapterUrl: String, page: Int): String? {
        if (page <= 1) return null
        val suffix = "_$page.html"
        return if (chapterUrl.endsWith(".html")) {
            chapterUrl.dropLast(".html".length) + suffix
        } else {
            chapterUrl + suffix
        }
    }

    override fun parseChapterContent(doc: Document, chapterUrl: String): String {
        val selectors = listOf(
            "#chaptercontent", "#content", "#nr1", "#BookText",
            ".ReadAjax_content", ".showtxt", ".content", ".txt",
            "article", ".article", ".read-content", ".book-content"
        )
        for (selector in selectors) {
            val element = doc.selectFirst(selector) ?: continue
            val text = cleanContent(element.html())
            if (text.isNotBlank()) return text
        }
        return ""
    }
}
