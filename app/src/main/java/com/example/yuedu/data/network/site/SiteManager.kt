package com.example.yuedu.data.network.site

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 多书源调度器。
 *
 * 职责：
 * - 根据书籍 URL 选出对应书源
 * - 把“目录分页 / 正文分页”的循环收敛到一处（各书源只描述规则）
 * - 章节按页顺序拼接并编号，保证阅读进度不会因为并发而错位
 */
object SiteManager {

    /** 目录最多抓多少页，防止站点分页标记异常时无限请求 */
    private const val MAX_CATALOG_PAGES = 60

    /** 正文最多多少页 */
    private const val MAX_CONTENT_PAGES = 30

    /**
     * 单本书允许的最大章节数。
     *
     * 这是个安全阀：部分站点能把章节 ID（千万级）误解析成「章节总数」，
     * 一旦据此循环生成章节，会瞬间耗尽内存并崩溃（OutOfMemoryError）。
     * 正常网文几千章已属极端，这里给到 5000 足够，超出即视为解析错误并丢弃。
     */
    private const val MAX_CHAPTERS = 5000

    /** 目录并发抓取的并行度。
     *
     * 原来是一页一页串行请求，一本 600 章的书要翻 7 页目录，
     * 每页 1~3 秒，光目录就要十几秒。这里改成并发，按索引归位保证顺序不变。
     */
    private const val CATALOG_PARALLELISM = 4

    /**
     * 单章正文的总时限。
     *
     * 站点正文页正常响应 0.25 秒，20 秒已是 80 倍余量。
     * 原来是 45 秒，配合 3 次重试 = 最长 2.3 分钟，用户等得崩溃。
     */
    private const val CHAPTER_TIMEOUT_MS = 20_000L

    /** 详情 */
    suspend fun getBookDetail(bookUrl: String): Book = withContext(Dispatchers.IO) {
        val site = BookSites.forUrl(bookUrl)

        // biquge.mom：优先走 apibi 接口（www 域在部分网络不可达，
        // 而 apibi.cc 可达且站内 id 直接就是接口书号）。失败再回退 HTML。
        if (site is BiqugeMomSite) {
            val id = site.bookId(bookUrl)
            if (id != null) {
                val apiBook = site.fetchApiBook(id)
                if (apiBook != null && apiBook.title.isNotBlank()) return@withContext apiBook
            }
            // apibi 挂了就别再请求不可达的 www 域（会再卡一个 15 秒超时）。
            // 列表页已经把书名/作者传进 UI 了，这里返回最小可用对象即可。
            return@withContext Book(url = bookUrl, title = "", author = "")
        }

        val doc = site.fetchDocument(bookUrl)
        val book = site.parseBookDetail(doc, bookUrl)
        // 部分站点的作者/书名只在列表页给出，详情页可能为空，这里兜底成 URL 的末段
        if (book.title.isBlank()) {
            book.copy(title = bookUrl.trim('/').substringAfterLast('/'))
        } else {
            book
        }
    }

    /**
     * 章节列表。
     *
     * 先解析入口页；若有后续目录页，**并发**抓取（并发度 [CATALOG_PARALLELISM]），
     * 再按页序号归位合并——原来一页一页串行请求，几百章的书光目录就要十几秒。
     * 结果按出现顺序编号，重复章按 URL 去重。
     */
    suspend fun getChapterList(bookUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        val site = BookSites.forUrl(bookUrl)

        // biquge.mom：优先走 apibi 的 booklist 接口拿真实章节名，
        // 完全不依赖 www 域（在部分网络不可达），且一次请求拿到全部章节。
        if (site is BiqugeMomSite) {
            val id = site.bookId(bookUrl)
            if (id != null) {
                val titles = site.fetchApiChapterTitles(id)
                if (!titles.isNullOrEmpty()) {
                    return@withContext titles.mapIndexed { i, title ->
                        Chapter(
                            bookUrl = bookUrl,
                            title = title,
                            url = site.readerUrl(id, i + 1),
                            index = i
                        )
                    }
                }
            }
            // booklist 接口经常 TLS 挂死（实测 PC 上 20 秒也失败），
            // 但 book 接口给了章节总数，且 chapter 接口按序号取正文正常。
            // 因此降级为按序号生成目录，章节名先用「第N章」占位，
            // 正文里 chapter 接口会返回真实 chaptername。
            // 详情与目录是并行加载的，缓存可能还没写入，自己再取一次
            var count = BiqugeMomSite.cachedChapterCount(id ?: -1)
            if (count <= 0 && id != null) {
                site.fetchApiBook(id)
                count = BiqugeMomSite.cachedChapterCount(id)
            }
            if (count in 1..MAX_CHAPTERS) {
                return@withContext (1..count).map { i ->
                    Chapter(
                        bookUrl = bookUrl,
                        title = "第${i}章",
                        url = site.readerUrl(id!!, i),
                        index = i - 1
                    )
                }
            }

            // apibi 彻底不可用时 www.biquge.mom 的 HTML 在多数网络也不可达，
            // 再走 fetchDocument 只会再白等一个 15 秒超时。直接返回空，
            // 由 ViewModel 显示明确错误，用户可重试。
            return@withContext emptyList()
        }

        val firstDoc = site.fetchDocument(bookUrl)
        val firstPage = site.parseChapterList(firstDoc, bookUrl)

        // 目录入口页可能没章节（例如部分站点把目录单独放一页），换目录页再试一次
        val collected = if (firstPage.isEmpty()) {
            val catalog = site.catalogUrl(bookUrl)
            if (catalog != bookUrl) {
                runCatching { site.parseChapterList(site.fetchDocument(catalog), bookUrl) }
                    .getOrDefault(emptyList())
                    .toMutableList()
            } else {
                mutableListOf()
            }
        } else {
            firstPage.toMutableList()
        }
        if (collected.isEmpty()) return@withContext emptyList()

        // 目录总页数由书源自己决定（返回 null = 不分页）。
        // 绝不能在这里「猜测」页数：曾经把章节 ID 当成序号算出 69 万页，
        // 被截成 60 页后并发抓了 60 个无效页面，导致加载极慢。
        val remainingPages = (2..MAX_CATALOG_PAGES)
            .mapNotNull { p -> site.chapterListPageUrl(bookUrl, p)?.let { p to it } }

        if (remainingPages.isNotEmpty()) {
            // 并发抓目录页，按页号归位，保证章节顺序稳定
            val pages = coroutineScope {
                remainingPages.chunked(CATALOG_PARALLELISM).flatMap { chunk ->
                    chunk.map { (_, pageUrl) ->
                        async {
                            try {
                                site.parseChapterList(site.fetchDocument(pageUrl), bookUrl)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
            }
            val seen = collected.map { it.url }.toMutableSet()
            for (pageChapters in pages) {
                collected.addAll(pageChapters.filter { seen.add(it.url) })
            }
        }

        val unique = collected.distinctBy { it.url }

        // biquga 详情页只列前 100 章，但章节 ID 连续递增、meta 里有最新章节 ID，
        // 直接展开成完整目录（否则读到第 100 章就"翻不动"了）
        val expanded = if (site is BiqugaSite) {
            site.expandFullCatalog(firstDoc, bookUrl, unique)
        } else {
            unique
        }

        expanded
            .mapIndexed { index, chapter -> chapter.copy(index = index) }
            .let { merged ->
                // 部分站点（如 bqgnovels / biquge.mom）只渲染前后几十章，用总章节数补齐中间部分
                val total = totalChapterCount(site, firstDoc)
                if (total != null && total > merged.size) {
                    expandChaptersByIndex(bookUrl, merged, total)
                } else {
                    merged
                }
            }
    }

    /** 从入口页里读出“总章节数”，读不到返回 null */
    private fun totalChapterCount(site: BookSite, doc: org.jsoup.nodes.Document): Int? {
        val raw = when (site) {
            is BqgNovelsSite -> site.lastChapterNumber(doc)
            is BiqugeMomSite -> site.chapterCount(doc)
            else -> null
        } ?: return null
        // 站点若解析出离谱数字（例如把章节 ID 当成章节数），必须挡掉：
        // 否则后面按 index 补齐会生成海量对象直接把内存撑爆。
        return raw.takeIf { it in 1..MAX_CHAPTERS }
    }

    /**
     * 顺着正文页自带的「下一章 / 上一章」链接找相邻章节。
     *
     * 目录可能不完整（如 biquga 详情页只列 100 章），
     * 但每一章正文页一定带相邻章节导航，用它翻章最可靠。
     *
     * @return 相邻章节 URL；没有则 null
     */
    suspend fun findAdjacentChapterUrl(chapterUrl: String, forward: Boolean): String? =
        withContext(Dispatchers.IO) {
            val site = BookSites.forUrl(chapterUrl)
            val doc = try {
                site.fetchDocument(chapterUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext null
            }
            if (forward) site.nextChapterUrl(doc, chapterUrl) else site.prevChapterUrl(doc, chapterUrl)
        }

    /** 章节正文（自动拼接站内分页） */
    suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        val site = BookSites.forUrl(chapterUrl)

        // 兜底时限：单章最多等 45 秒。
        // 没有这个限制时，站点一直返回“下一页”会让翻页循环跑到 MAX_CONTENT_PAGES(30 页)，
        // 每页 15 秒读超时就是好几分钟，表现就是“一直卡在加载中”。
        val result = withTimeoutOrNull(CHAPTER_TIMEOUT_MS) {
            if (site is BiqugeMomSite) {
                fetchMomContent(site, chapterUrl)
            } else {
                fetchPagedContent(site, chapterUrl)
            }
        }
        result ?: "本章加载超时，请检查网络后重试"
    }

    /**
     * 普通站点的正文分页拼接。
     *
     * 关键修复（章节内容重复的真正原因）：
     * biquga 并不存在 `xxx_2.html`，但它**不返回 404**，而是把第 1 页内容
     * 原样再返回一遍。原来的循环只要 contentPageUrl() 不为 null 就一直翻，
     * 于是同一段正文被 append 了最多 MAX_CONTENT_PAGES(30) 次，
     * 用户看到的就是「一章内容重复几十遍」。
     *
     * 现在两道保险：
     *  1. 页码或标题没有前进（站点没换页）→ 立即停止
     *  2. 本页正文与已收集内容重复 → 丢弃并停止
     */
    private suspend fun fetchPagedContent(site: BookSite, chapterUrl: String): String {
        val builder = StringBuilder()
        /** 已收集的每页正文，用于去重 */
        val pages = mutableListOf<String>()
        /** 已见过的 (页码, 标题)，用来判断站点是否真的换页了 */
        val seenPageMarks = mutableSetOf<String>()
        var page = 1

        while (page <= MAX_CONTENT_PAGES) {
            val url = if (page == 1) chapterUrl else site.contentPageUrl(chapterUrl, page) ?: break
            val doc = try {
                site.fetchDocument(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (page == 1) throw e
                break
            }

            // 站点若不换页，<h3> 之类的标题会一模一样（形如「引子（第1页）」）。
            // 拿它做「是否真的翻页了」的判据，比只看页码可靠。
            val pageMark = buildString {
                append(doc.selectFirst("h3")?.text()?.trim().orEmpty())
                append('|')
                append(doc.title())
            }
            val isRepeatedPage = !seenPageMarks.add(pageMark)

            val text = site.parseChapterContent(doc, url)
            if (text.isBlank()) break

            // 与本页之前任一页内容相同 → 站点在重复返回同一页，直接结束
            val duplicated = pages.any { it == text || (it.length > 200 && text.contains(it)) }
            if (duplicated || isRepeatedPage) {
                break
            }

            pages.add(text)
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(text)

            // 正文页本身不跨页（例如单页小说），直接结束
            if (site.contentPageUrl(chapterUrl, 2) == null) break
            page++
        }
        return builder.toString()
    }

    /**
     * biquge.mom 正文：先拿详情页里的 api 书号，再调章节接口。
     */
    private suspend fun fetchMomContent(site: BiqugeMomSite, chapterUrl: String): String {
        val id = site.bookId(chapterUrl) ?: return ""
        val chapterNo = Regex("""[?&]chapter=(\d+)""").find(chapterUrl)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return ""

        // apibi 的书号就是站内 id（实测 id=325/583 均直接可用）。
        // 不再请求 www.biquge.mom 的详情页去取 source_book_id —— 该域在部分网络
        // 不可达，会白等 15 秒超时。直接用站内 id 调 apibi。
        return site.fetchChapterText(id, chapterNo) ?: ""
    }

    /**
     * 按章节序号补齐目录。
     *
     * bqgnovels 的目录页只渲染前后几十章，中间章节靠 /book/{id}/{n} 递增补齐；
     * 其它站点目录本来就是完整的，直接原样返回。
     */
    suspend fun expandChaptersByIndex(
        bookUrl: String,
        existing: List<Chapter>,
        total: Int
    ): List<Chapter> = withContext(Dispatchers.IO) {
        val site = BookSites.forUrl(bookUrl)
        val missing: List<Chapter> = when (site) {
            is BqgNovelsSite -> {
                val known = existing.mapNotNull { c ->
                    Regex("""/(\d+)$""").find(c.url)?.groupValues?.get(1)?.toIntOrNull()
                }.toSet()
                (1..total).filter { it !in known }.mapNotNull { no ->
                    site.chapterUrlForIndex(bookUrl, no)?.let { url ->
                        Chapter(bookUrl = bookUrl, title = "第${no}章", url = url)
                    }
                }
            }

            is BiqugeMomSite -> {
                val id = site.bookId(bookUrl) ?: return@withContext existing
                val known = existing.mapNotNull { c ->
                    Regex("""[?&]chapter=(\d+)""").find(c.url)?.groupValues?.get(1)?.toIntOrNull()
                }.toSet()
                (1..total).filter { it !in known }.map { no ->
                    Chapter(bookUrl = bookUrl, title = "第${no}章", url = site.readerUrl(id, no))
                }
            }

            else -> return@withContext existing
        }
        if (missing.isEmpty()) return@withContext existing

        // 合并后按章节号重排，保证阅读顺序正确
        val merged = (existing + missing)
            .distinctBy { it.url }
            .sortedBy { chapterOrder(it) }
        merged.mapIndexed { index, chapter -> chapter.copy(index = index) }
    }

    /** 章节 URL 里的数字序号，用于排序 */
    private fun chapterOrder(chapter: Chapter): Int {
        val patterns = listOf(
            Regex("""[?&]chapter=(\d+)"""),
            Regex("""/(\d+)$"""),
            Regex("""/(\d+)\.html""")
        )
        for (p in patterns) {
            val n = p.find(chapter.url)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null) return n
        }
        return chapter.index
    }
}
