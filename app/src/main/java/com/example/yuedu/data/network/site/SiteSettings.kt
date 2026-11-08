package com.example.yuedu.data.network.site

import com.example.yuedu.data.YueduPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 书源的用户可配置偏好。
 *
 * - 首选源：搜索时优先走它（默认仍是内置索引）
 * - 每个源可单独开关（停用的源不参与搜索）
 * - 各源最近一次实测耗时（毫秒），在书源管理页展示
 */
object SiteSettings {

    private const val KEY_PRIMARY = "site_primary"
    private const val KEY_ENABLED_PREFIX = "site_enabled_"
    private const val KEY_LATENCY_PREFIX = "site_latency_"
    private const val KEY_LATENCY_TIME_PREFIX = "site_latency_time_"
    private const val KEY_INIT_DONE = "site_init_done"

    /**
     * 首次启动时把全部书源置为启用。
     *
     * 早期版本没有初始化，`getBoolean(..., true)` 的默认值一旦被误写成 false
     * 就再也回不来；这里显式写一次 true 兜底。
     */
    fun ensureInitialized() {
        if (YueduPrefs.getBoolean(KEY_INIT_DONE, false)) return
        BookSites.all.forEach { YueduPrefs.putBoolean(KEY_ENABLED_PREFIX + it.id, true) }
        YueduPrefs.putBoolean(KEY_INIT_DONE, true)
    }

    /** 首选源 id；空串 = 自动（内置索引优先） */
    fun primaryId(): String = YueduPrefs.getString(KEY_PRIMARY, "")

    fun setPrimaryId(id: String) = YueduPrefs.putString(KEY_PRIMARY, id)

    /** 是否启用某源（默认全启用） */
    fun isEnabled(id: String): Boolean = YueduPrefs.getBoolean(KEY_ENABLED_PREFIX + id, true)

    fun setEnabled(id: String, enabled: Boolean) =
        YueduPrefs.putBoolean(KEY_ENABLED_PREFIX + id, enabled)

    /** 最近一次测速结果（毫秒）；-1 表示超时；-2 表示从未测过 */
    fun latencyMs(id: String): Long = YueduPrefs.getLong(KEY_LATENCY_PREFIX + id, -2L)

    fun latencyTime(id: String): Long = YueduPrefs.getLong(KEY_LATENCY_TIME_PREFIX + id, 0L)

    /** 是否已确认该源不可用（测过且超时） */
    fun isKnownDead(id: String): Boolean = latencyMs(id) == -1L

    fun saveLatency(id: String, ms: Long) {
        YueduPrefs.putLong(KEY_LATENCY_PREFIX + id, ms)
        YueduPrefs.putLong(KEY_LATENCY_TIME_PREFIX + id, System.currentTimeMillis())
    }

    /** 某源是否可用于搜索（内置索引永远可用） */
    fun availableForSearch(): List<BookSite> =
        BookSites.all.filter { isEnabled(it.id) }

    /**
     * 测速：对每个源发一个轻量请求（优先用它的搜索页，没有就用首页），
     * 记录往返耗时。失败记为 -1（界面显示"超时"）。
     *
     * 全部并发，互不拖累；结果写进 YueduPrefs 供界面读取。
     */
    suspend fun measureAll(): Map<String, Long> = withContext(Dispatchers.IO) {
        coroutineScope {
            val results: List<Pair<String, Long>> = BookSites.all.map { site ->
                async {
                    val url = probeUrl(site)
                    val ms: Long = try {
                        val t0 = System.currentTimeMillis()
                        site.fetchDocument(url)
                        (System.currentTimeMillis() - t0).coerceAtLeast(1)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        -1L
                    }
                    saveLatency(site.id, ms)
                    site.id to ms
                }
            }.awaitAll()
            results.toMap()
        }
    }

    /** 每个源用于测速的 URL（尽量轻量） */
    private fun probeUrl(site: BookSite): String = when (site) {
        is Xiang5Site -> site.searchUrl("测速")
        is TiandiSite -> site.searchUrl("测速")
        else -> site.baseUrl
    }
}
