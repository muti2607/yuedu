package com.example.yuedu.data.network.site

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.nio.charset.Charset

/**
 * 书源（站点）抽象。
 *
 * 每个站点只需要回答三件事：
 * 1. 这个 URL 归不归我管（[matches]）
 * 2. 书的信息长什么样（[parseBookDetail]）
 * 3. 目录和正文怎么取（[parseChapterList] / [parseChapterContent]）
 *
 * URL 解析、章节分页拼接、编码探测这类公共逻辑放在本类里，
 * 具体站点只覆盖自己特殊的部分，避免每接一个站就复制一大段网络代码。
 */
abstract class BookSite {

    /** 站点名称，用于展示与日志 */
    abstract val name: String

    /** 站点根地址，例如 https://www.biquga.com */
    abstract val baseUrl: String

    /** 判断某个 URL 是否属于本站点 */
    fun matches(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val base = hostOf(baseUrl) ?: return false
        return host == base || host.endsWith(".$base") || base.endsWith(".$host")
    }

    /** 解析书籍详情页 */
    abstract fun parseBookDetail(doc: Document, bookUrl: String): Book

    /** 解析目录页里的章节（单页） */
    abstract fun parseChapterList(doc: Document, bookUrl: String): List<Chapter>

    /** 解析章节正文页 */
    abstract fun parseChapterContent(doc: Document, chapterUrl: String): String

    /**
     * 目录是否分页，以及第 [page] 页的地址。
     * 返回 null 表示没有更多页。
     */
    open fun chapterListPageUrl(bookUrl: String, page: Int): String? = null

    /**
     * 正文是否分页，以及第 [page] 页的地址（page 从 1 开始）。
     * 返回 null 表示不需要/没有下一页。
     */
    open fun contentPageUrl(chapterUrl: String, page: Int): String? = null

    /** 总目录页地址（供“全部章节”入口使用） */
    open fun catalogUrl(bookUrl: String): String = bookUrl

    // ==================== 公共网络能力 ====================

    protected val client: OkHttpClient get() = SharedHttp.client

    /**
     * 拉取并解析一个页面。
     *
     * 两层加速：
     * 1. 60 秒页面缓存——详情页/阅读页连续打开同一 URL 时不再重复请求；
     * 2. 瞬时故障快速重试——站点偶发 502/超时，退避 400ms 重试一次，
     *    大多数情况直接成功，避免把一次抖动放大成整页“加载失败”。
     */
    suspend fun fetchDocument(url: String): Document {
        val target = normalize(url)
        SharedHttp.cachedDocument(target)?.let { return it }

        var lastError: Throwable? = null
        for (attempt in 0..1) {
            try {
                val builder = Request.Builder().url(target)
                for ((k, v) in SharedHttp.htmlHeaders(referer = baseUrl + "/")) {
                    builder.header(k, v)
                }
                val doc = SharedHttp.execute(client, builder.build()) { response, body ->
                    val charset = SharedHttp.detectCharset(response, body)
                    Jsoup.parse(String(body, Charset.forName(charset)), url)
                }
                SharedHttp.putCachedDocument(target, doc)
                return doc
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                // 只对 5xx/429/网络抖动重试一次；404、403 重试没有意义
                if (attempt == 0 && SharedHttp.isRetryable(e)) {
                    kotlinx.coroutines.delay(400L)
                    continue
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException("fetch failed: $url")
    }

    /** 把站内相对地址补全成绝对地址 */
    fun resolveUrl(path: String, referer: String = baseUrl): String {
        val raw = path.trim()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) return "https:" + raw
        if (raw.startsWith("/")) return baseUrl.trimEnd('/') + raw
        // 相对当前页面的地址
        val base = referer.trimEnd('/')
        return "$base/$raw"
    }

    private fun normalize(url: String): String = url.replace("/#/", "/")

    private fun hostOf(url: String): String? {
        return try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /** 清理正文：去标签、去广告、统一空行 */
    protected fun cleanContent(html: String): String {
        var text = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("<[^>]+>"), "")
        text = Jsoup.parse(text).text().let { htmlUnescape(text) }
        val lines = text.split('\n')
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.isAdLine() }
        return lines.joinToString("\n")
    }

    /** HTML 实体还原（&nbsp; 等） */
    private fun htmlUnescape(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    /** 常见广告/噪声行，避免混进正文 */
    private fun String.isAdLine(): Boolean {
        val noise = listOf(
            "请关闭浏览器阅读模式",
            "本站所有内容来源于互联网",
            "笔趣阁",
            "最新章节请记住",
            "手机用户请浏览",
            "加入书签",
            "上一章",
            "下一章",
            "章节目录"
        )
        if (length > 60) return false
        // 只有短语构成的行才过滤，正文长句不误伤
        return noise.any { this == it || (length <= 20 && contains(it) && !this.matches(Regex(".*[，。！？…].*"))) }
    }
}
