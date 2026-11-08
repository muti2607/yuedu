package com.example.yuedu.data.network.site

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 所有书源共用的 HTTP 客户端与工具方法。
 *
 * OkHttpClient 必须复用：每次新建都会新建连接池和线程池，
 * 翻章时会不断堆积连接，最终报 “Too many open files”。
 */
object SharedHttp {

    /**
     * 桌面版 UA。
     *
     * 部分站点的反爬只放行桌面浏览器（移动端 UA 会被直接 403 或断开连接），
     * 这里统一用桌面 UA，比移动端兼容性更好。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 带状态码的 HTTP 异常，便于上层区分“可重试”与“真失败” */
    class HttpException(val code: Int, url: String) :
        IllegalStateException("HTTP $code for $url")

    /**
     * 进程内 cookie 会话。
     *
     * 香网等站点靠 Set-Cookie + 302 做反爬：详情页种 cookie 后跳转，
     * 后续请求不带这个 cookie 就会陷入无限重定向。OkHttp 默认不带 cookie，
     * 这里挂一个内存 CookieJar 维持会话。
     */
    private val cookieJar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            store[url.host] = cookies
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store[url.host].orEmpty()
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 超时必须短：站点卡住时 15s 连接超时会让用户干等
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            // 显式给出连接配置：TLS + 明文。
            // 部分书源（香网/天地中文）仍是 HTTP，若只配 TLS，OkHttp 会抛
            // "CLEARTEXT communication not enabled for client" 直接失败。
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )
            .build()
    }

    /** 浏览器常规请求头，缺失时很多站点会返回 403 */
    fun htmlHeaders(referer: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        if (referer != null) headers["Referer"] = referer
        return headers
    }

    /**
     * 执行请求并保证响应被关闭。
     */
    fun <T> execute(client: OkHttpClient, request: Request, block: (Response, ByteArray) -> T): T {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.code, request.url.toString())
            }
            val body = response.body?.bytes() ?: throw IOException("Empty response")
            return block(response, body)
        }
    }

    /**
     * 是否值得重试：CDN 偶发的 5xx / 429 / 连接超时都是瞬时故障，
     * 截图里那种 HTTP 502 大多数情况下退避后重试一次就能过。
     */
    fun isRetryable(e: Throwable): Boolean = when (e) {
        is HttpException -> e.code == 408 || e.code == 429 || e.code >= 500
        is IOException -> true
        else -> false
    }

    // ==================== 页面级短时缓存 ====================

    /**
     * 60 秒内同一 URL 复用同一份解析结果。
     *
     * 打开一本书时“详情页 → 阅读页”会连续两次请求同一个目录页，
     * 缓存直接省掉一次完整网络往返。
     */
    private const val PAGE_CACHE_TTL_MS = 60_000L
    private const val PAGE_CACHE_MAX = 24

    private class CacheEntry(val at: Long, val doc: Document)

    private val pageCache: MutableMap<String, CacheEntry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean =
                    size > PAGE_CACHE_MAX
            }
        )

    fun cachedDocument(url: String): Document? {
        val entry = pageCache[url] ?: return null
        if (System.currentTimeMillis() - entry.at > PAGE_CACHE_TTL_MS) {
            pageCache.remove(url)
            return null
        }
        return entry.doc
    }

    fun putCachedDocument(url: String, doc: Document) {
        pageCache[url] = CacheEntry(System.currentTimeMillis(), doc)
    }

    /**
     * 探测编码：优先响应头，其次 HTML 里的 meta charset，最后 UTF-8。
     */
    fun detectCharset(response: Response, body: ByteArray): String {
        val contentType = response.header("Content-Type", "") ?: ""
        val fromHeader = if (contentType.contains("charset=", ignoreCase = true)) {
            contentType.substringAfter("charset=", "").substringBefore(";").trim().trim('"')
        } else ""
        val candidate = fromHeader.ifEmpty {
            val head = String(body, 0, minOf(body.size, 2048), Charsets.ISO_8859_1)
            Regex("""charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.get(1)?.trim().orEmpty()
        }
        return try {
            when (candidate.lowercase()) {
                "", "utf8" -> "UTF-8"
                "gb2312", "gbk", "gb-2312" -> "GBK"
                else -> {
                    // 校验一下名字是否被 JVM 识别，避免非法值直接抛异常
                    Charset.forName(candidate)
                    candidate
                }
            }
        } catch (_: Exception) {
            "UTF-8"
        }
    }
}
