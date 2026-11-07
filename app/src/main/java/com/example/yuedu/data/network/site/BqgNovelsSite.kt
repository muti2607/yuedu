package com.example.yuedu.data.network.site

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import org.jsoup.nodes.Document

/**
 * 书源：新笔趣阁 www.bqgnovels.com（Nuxt SSR 站点）
 *
 * 结构（实测）：
 * - 详情页 /book/34983 ：
 *   .detail h1 书名，.detail_bt .author 作者（“作者: xxx”），
 *   .des .des_content 简介，.detail img 封面，.state 完结状态，
 *   .detail_bt .last 里的“最新章节”链接
 *   章节列表：.chapter 之后 .list 里的 a.list_item，href 形如 /book/34983/1
 * - 正文页 /book/34983/1 ：
 *   h1.text_tit 章节名，div.text 正文（用 <br> 换行）
 * - 目录页只渲染前 100 章左右，完整目录需要靠 /book/{id}/{chapterId} 递增，
 *   所以这里额外读取页面里 window.__NUXT__ 的章节总数（update_id 即最新章节号）。
 * - 站点没有可用的搜索页（/search 返回 404），[supportsSearch] = false。
 */
class BqgNovelsSite : BookSite() {

    override val name = "新笔趣阁(bqgnovels)"
    override val baseUrl = "https://www.bqgnovels.com"

    override fun parseBookDetail(doc: Document, bookUrl: String): Book {
        val title = doc.selectFirst(".detail h1")?.text()?.trim()
            ?: doc.title().substringBefore("最新章节").substringBefore("_").trim()

        val author = doc.selectFirst(".detail_bt .author")?.text()
            ?.replace("作者:", "")?.replace("作者：", "")?.trim()
            ?: doc.title().split("_").getOrNull(1)?.trim()
            ?: ""

        val cover = doc.selectFirst(".detail img")?.attr("src") ?: ""
        val description = doc.selectFirst(".des .des_content")?.text()?.trim()
            ?: metaContent(doc, "description")
        // 面包屑第 2 项是分类：笔趣阁 > 穿越历史 > 书名
        val category = doc.select(".v-breadcrumbs a.v-breadcrumbs__item")
            .getOrNull(1)?.text()?.trim() ?: ""
        val lastChapter = doc.selectFirst(".detail_bt .last a")?.text()?.trim() ?: ""

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

    private fun metaContent(doc: Document, name: String): String =
        doc.selectFirst("meta[name=$name]")?.attr("content")?.trim() ?: ""

    override fun parseChapterList(doc: Document, bookUrl: String): List<Chapter> {
        val links = doc.select("a.list_item[href]")
        if (links.isEmpty()) return emptyList()
        return links.mapNotNull { a ->
            val href = a.attr("href").trim()
            val title = a.selectFirst(".v-list-item__title")?.text()?.trim()
                ?: a.text().trim()
            if (href.isEmpty() || title.isEmpty()) return@mapNotNull null
            Chapter(bookUrl = bookUrl, title = title, url = absolute(href))
        }
    }

    /**
     * 目录只渲染一屏，这里用 __NUXT__ 里的 update_id 推出最后一章的地址，
     * 让调用方可以按 id 递增补齐（见 [chapterUrlForIndex]）。
     */
    override fun chapterListPageUrl(bookUrl: String, page: Int): String? = null

    /** 书籍数字 id，例如 /book/34983 -> 34983 */
    fun bookId(bookUrl: String): String? =
        Regex("""/book/(\d+)""").find(bookUrl)?.groupValues?.get(1)

    /** 章节地址按章节号拼：/book/{id}/{chapterNo} */
    fun chapterUrlForIndex(bookUrl: String, chapterNo: Int): String? {
        val id = bookId(bookUrl) ?: return null
        return "${baseUrl.trimEnd('/')}/book/$id/$chapterNo"
    }

    /** 解析最新章节号，用于确定章节目录的长度 */
    fun lastChapterNumber(doc: Document): Int? {
        // 详情页里的“最新章节”链接形如 /book/34983/1262
        doc.selectFirst(".detail_bt .last a[href]")?.attr("href")?.let { href ->
            Regex("""/book/\d+/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { return it }
        }
        // 兜底：__NUXT__ 数据里的 update_id
        val nuxt = doc.select("script").map { it.data() }.firstOrNull { it.contains("update_id") }
            ?: return null
        return Regex("""update_id:\s*(\d+)""").find(nuxt)?.groupValues?.get(1)?.toIntOrNull()
    }

    override fun parseChapterContent(doc: Document, chapterUrl: String): String {
        val body = doc.selectFirst("div.text")
            ?: doc.selectFirst(".text_tit")?.nextElementSibling()
            ?: return ""
        return cleanContent(body.html())
    }

    private fun absolute(href: String): String =
        if (href.startsWith("http")) href else baseUrl.trimEnd('/') + "/" + href.trimStart('/')
}
