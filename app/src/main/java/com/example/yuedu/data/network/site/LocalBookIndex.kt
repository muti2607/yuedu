package com.example.yuedu.data.network.site

import android.content.Context
import com.example.yuedu.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * 内置书库索引（19 万本），App 本地搜索的**主源**。
 *
 * 数据来源：biquge.mom 公开的静态书名索引 /data/search-docs-{i}.json，
 * 在构建期抽取 id/书名/作者，gzip 压缩后打包进 assets（约 4.6 MB）。
 *
 * 为什么这么做：
 *  - 第三方站点的搜索接口要么纯前端渲染（HTML 里没结果），要么书库小、
 *    要么对 App 直连超时。这个索引覆盖 19 万本，且**完全本地、0 网络、毫秒级**。
 *  - 命中后生成 biquge.mom 的书籍 URL，详情页/目录/正文沿用已有的书源实现。
 *
 * 索引首次使用时解压到内存（约 8.5 MB 文本），之后常驻，同会话内搜索不再读文件。
 */
object LocalBookIndex {

    private const val BASE = "https://www.biquge.mom"
    private const val ASSET = "book_index.gz"
    private const val MAX_RESULTS = 40

    /** 已解压的索引行：id \t 书名 \t 作者 */
    @Volatile
    private var lines: List<String>? = null
    @Volatile
    private var loadError = false

    data class Hit(val id: Int, val title: String, val author: String)

    /**
     * 按书名/作者关键词搜索。
     * @return 按「完全匹配 > 前缀 > 包含」排序的结果
     */
    suspend fun search(context: Context, keyword: String): List<Book> = withContext(Dispatchers.IO) {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return@withContext emptyList()

        val data = ensureLoaded(context)
        if (data.isEmpty()) return@withContext emptyList()

        val exact = mutableListOf<Book>()
        val prefix = mutableListOf<Book>()
        val contains = mutableListOf<Book>()

        for (line in data) {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 2) continue
            val title = parts[1]
            val author = parts.getOrElse(2) { "" }
            val tl = title.lowercase()
            val matchedInTitle = tl.contains(kw)
            val matchedInAuthor = author.lowercase().contains(kw)
            if (!matchedInTitle && !matchedInAuthor) continue

            val id = parts[0].toIntOrNull() ?: continue
            val book = Book(
                url = "$BASE/book/$id/",
                title = title,
                author = author
            )
            when {
                tl == kw || author.lowercase() == kw -> exact.add(book)
                tl.startsWith(kw) -> prefix.add(book)
                else -> contains.add(book)
            }
            if (exact.size + prefix.size + contains.size >= MAX_RESULTS * 3) break
        }

        (exact + prefix + contains).take(MAX_RESULTS)
    }

    /** 是否可用（assets 里有没有索引） */
    fun isAvailable(context: Context): Boolean = try {
        context.assets.open(ASSET).use { true }
    } catch (_: Exception) {
        false
    }

    /** 首次调用时把 gzip 索引解压成行列表，带缓存 */
    private fun ensureLoaded(context: Context): List<String> {
        lines?.let { return it }
        if (loadError) return emptyList()
        synchronized(this) {
            lines?.let { return it }
            try {
                val list = mutableListOf<String>()
                context.assets.open(ASSET).use { raw ->
                    GZIPInputStream(raw).use { gz ->
                        BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isNotEmpty()) list.add(line)
                            }
                        }
                    }
                }
                lines = list
                return list
            } catch (_: Exception) {
                loadError = true
                return emptyList()
            }
        }
    }
}
