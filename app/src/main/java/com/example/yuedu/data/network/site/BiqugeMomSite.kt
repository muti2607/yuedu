package com.example.yuedu.data.network.site

import android.util.Base64
import android.util.Log
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 书源：笔趣阁 www.biquge.mom
 *
 * 结构（实测）：
 * - 书库/分类页 /categories ：a.book-card[href]，内含 .book-title 与 .book-meta（“作者 · 分类 · N 章”）
 * - 详情页 /book/{id}/ ：.head h1 书名、#info-line 作者与分类、.desc 简介、
 *   #catalog-body .catalog a 章节（服务端只渲染前 12 + 后 6 章）、
 *   #book-data 里带 chapter_count 与 source_book_id
 * - 阅读页 /reader?novelId=&chapter= 服务端只是个骨架，正文由 data.js 去 apibi.cc 取：
 *   GET https://apibi.cc/api/chapter?token=...
 *   token = Base64(AES-128-CBC(JSON参数, key=md5[16..32], iv=md5[0..16]))
 *   其中 md5 = md5("book@token.html")，是站点前端公开协议。
 *   拿到的是 JSON：{"title","chaptername","cs","txt",...}
 *
 * 因此这个书源的正文走 [SiteManager] 的专用分支，而不是解析 HTML。
 */
class BiqugeMomSite : BookSite() {

    override val name = "笔趣阁(biquge.mom)"
    override val baseUrl = "https://www.biquge.mom"

    /** 正文接口域名 */
    private val apiBase = "https://apibi.cc"

    // ==================== 详情 ====================

    override fun parseBookDetail(doc: Document, bookUrl: String): Book {
        val ldJson = doc.select("script#ld-book").firstOrNull()?.data().orEmpty()
        val title = doc.selectFirst(".head h1")?.text()
            ?.replace("《", "")?.replace("》全文免费阅读", "")?.trim()
            ?.ifBlank { null }
            ?: jsonString(ldJson, "name")
            ?: doc.title().substringBefore("免费阅读").trim()

        val author = doc.selectFirst("#info-line .author-link")?.text()?.trim()
            ?.ifBlank { null }
            ?: jsonString(ldJson, "name", after = "\"author\"")
            ?: ""

        val cover = doc.selectFirst(".head .cover img")?.attr("src")
            ?: jsonString(ldJson, "image")
            ?: ""

        val description = doc.selectFirst(".desc p")?.text()?.trim()
            ?: jsonString(ldJson, "description")
            ?: ""

        // #info-line 里第二个链接是分类
        val category = doc.select("#info-line a").getOrNull(1)?.text()?.trim() ?: ""
        val lastChapter = doc.selectFirst("#btn-latest[href]")?.text()?.trim() ?: ""

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

    /** 书籍数字 id：/book/2/ 或 /novel?id=2 */
    fun bookId(bookUrl: String): Int? {
        Regex("""/book/(\d+)""").find(bookUrl)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""[?&]id=(\d+)""").find(bookUrl)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /**
     * 正文接口用的书号：优先 #book-data 里的 source_book_id，
     * 它才是 apibi 的书号；为空时才回退到站内 id。
     */
    fun apiBookId(doc: Document, bookUrl: String): Int {
        val json = doc.select("script#book-data").firstOrNull()?.data().orEmpty()
        val sourceId = Regex(""""source_book_id"\s*:\s*"?(\d+)?"?""")
            .find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return sourceId ?: bookId(bookUrl) ?: 0
    }

    /** 阅读页地址（正文由接口提供，这里只用它承载"第几章"这个信息） */
    fun readerUrl(bookId: Int, chapterNo: Int): String =
        "${baseUrl.trimEnd('/')}/reader?novelId=$bookId&chapter=$chapterNo"

    /** 章节数（#book-data 里的 chapter_count） */
    fun chapterCount(doc: Document): Int? {
        val json = doc.select("script#book-data").firstOrNull()?.data() ?: return null
        return Regex(""""chapter_count"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ==================== 目录 ====================

    override fun parseChapterList(doc: Document, bookUrl: String): List<Chapter> {
        val links = doc.select("#catalog-body .catalog a[href]")
        if (links.isEmpty()) return emptyList()
        return links.mapNotNull { a ->
            val href = a.attr("href").trim()
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (href.isEmpty() || title.isEmpty()) return@mapNotNull null
            Chapter(bookUrl = bookUrl, title = title, url = resolveUrl(href, bookUrl))
        }.distinctBy { it.url }
    }

    // ==================== 正文（走 apibi 接口） ====================

    override fun parseChapterContent(doc: Document, chapterUrl: String): String {
        // 只有拿到“已渲染”的页面时才会走到这里
        val content = doc.selectFirst("#ch-content") ?: return ""
        return cleanContent(content.html())
    }

    /**
     * 直接调正文接口。
     *
     * @param apiBookId apibi 书号
     * @param chapterNo 章节号（1 开始）
     * @return 正文；失败返回 null
     */
    suspend fun fetchChapterText(apiBookId: Int, chapterNo: Int): String? = withContext(Dispatchers.IO) {
        if (apiBookId <= 0 || chapterNo <= 0) return@withContext null
        val params = """{"id":$apiBookId,"chapterid":$chapterNo}"""
        val token = try {
            aesToken(params)
        } catch (e: Exception) {
            Log.w(TAG, "token 生成失败", e)
            return@withContext null
        }
        val request = Request.Builder()
            .url("$apiBase/api/chapter?token=" + java.net.URLEncoder.encode(token, "UTF-8"))
            .header("User-Agent", SharedHttp.USER_AGENT)
            .header("Referer", "$baseUrl/")
            .build()
        try {
            SharedHttp.execute(SharedHttp.client, request) { _, bytes ->
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val txt = json.optString("txt")
                if (txt.length < 50) null else tidy(txt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "章节接口失败 id=$apiBookId no=$chapterNo", e)
            null
        }
    }

    /** 正文里用换行分段，少量 HTML 实体需要还原 */
    private fun tidy(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace("&nbsp;", " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * 生成接口 token：Base64(AES-128-CBC(明文, key, iv))
     * key = md5[16..32]，iv = md5[0..16]，md5 = md5("book@token.html")
     */
    private fun aesToken(plain: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest("book@token.html".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val iv = hex.substring(0, 16).toByteArray(Charsets.UTF_8)
        val key = hex.substring(16, 32).toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    // ==================== 辅助 ====================

    private fun jsonString(json: String, key: String, after: String = ""): String? {
        val scope = if (after.isNotEmpty()) json.substringAfter(after, "") else json
        if (scope.isEmpty()) return null
        return Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(scope)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")
            ?.trim()
            ?.ifBlank { null }
    }

    companion object {
        private const val TAG = "BiqugeMomSite"
    }
}
