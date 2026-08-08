package com.example.yuedu.data.network.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 书籍数据源基类
 *
 * 提供通用的HTTP请求、HTML解析、URL处理等功能。
 * 所有具体书源实现应继承此类以复用公共逻辑。
 */
abstract class BaseBookSource : BookSource {

    /** 默认User-Agent */
    protected val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 默认Cookie */
    protected open val cookie: String = ""

    /** HTTP客户端，带超时配置 */
    protected val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 发起HTTP GET请求并获取HTML文档
     */
    protected suspend fun fetchHtmlDocument(url: String): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Cookie", cookie)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.bytes() ?: throw Exception("Empty response from $url")
            val charset = detectCharset(response)
            Jsoup.parse(String(body, charset(charset)), url)
        }
    }

    /**
     * 发起HTTP POST请求并获取HTML文档
     */
    protected suspend fun fetchHtmlDocumentPost(
        url: String,
        formParams: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        val bodyBuilder = okhttp3.FormBody.Builder()
        formParams.forEach { (key, value) ->
            bodyBuilder.add(key, value)
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Cookie", cookie)
            .post(bodyBuilder.build())
        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.bytes() ?: throw Exception("Empty response from $url")
            val charset = detectCharset(response)
            Jsoup.parse(String(body, charset(charset)), url)
        }
    }

    /**
     * 检测响应字符集
     */
    private fun detectCharset(response: Response): String {
        val contentType = response.header("Content-Type", "text/html; charset=UTF-8") ?: "text/html; charset=UTF-8"
        return if (contentType.contains("charset=", ignoreCase = true)) {
            contentType.substringAfter("charset=", "")
                .substringBefore(";")
                .substringBefore(",")
                .trim()
                .ifEmpty { "UTF-8" }
        } else {
            "UTF-8"
        }
    }

    /**
     * 默认的URL解析实现
     */
    override fun resolveUrl(path: String): String {
        return when {
            path.isBlank() -> ""
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> baseUrl.trimEnd('/') + path
            else -> "$baseUrl/$path"
        }
    }

    /**
     * 清理HTML内容，提取纯文本
     */
    protected fun cleanHtmlContent(html: String): String {
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
        content = content.replace("\\s{2,}".toRegex(), "\n\n").trim()

        return content
    }

    /**
     * 检测内容是否为JavaScript代码而非正文
     */
    protected fun looksLikeJavaScript(text: String): Boolean {
        if (text.isBlank()) return false
        val jsKeywords = listOf(
            "function(", "var ", "jQuery", "typeof ", "=== ", "!== ",
            "return ", "document.", "window.", "module.exports", "hasOwnProperty",
            "CryptoJS", "eval(", ".setAttribute", "addEventListener"
        )
        var matchCount = 0
        for (kw in jsKeywords) {
            if (text.contains(kw)) matchCount++
        }
        if (matchCount >= 5) return true
        if (text.contains("function(") && text.length > 2000) return true
        return false
    }

    /**
     * 从文档中使用多个选择器尝试提取内容
     */
    protected fun extractContentBySelectors(
        doc: org.jsoup.nodes.Document,
        selectors: List<String>
    ): String {
        for (selector in selectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                val bodyText = cleanHtmlContent(element.html())
                if (bodyText.isNotBlank() && !looksLikeJavaScript(bodyText) && bodyText.length > 50) {
                    return formatContent(bodyText)
                }
            }
        }
        return ""
    }

    /**
     * 格式化正文内容，添加段落缩进
     */
    protected fun formatContent(text: String): String {
        val paragraphs = text.split("\n").filter { it.isNotBlank() }
        return paragraphs.joinToString("\n\n") { paragraph ->
            val trimmed = paragraph.trim()
            if (trimmed.startsWith("　　") || trimmed.startsWith("  ")) {
                trimmed
            } else {
                "　　$trimmed"
            }
        }
    }
}
