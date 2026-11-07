package com.example.yuedu.ui.reader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ReaderViewModel(
    val repository: BookRepository,
    private val bookUrl: String,
    initialChapterIndex: Int
) : ViewModel() {

    private val _chapterContent = MutableLiveData("")
    val chapterContent: LiveData<String> = _chapterContent

    private val _chapterTitle = MutableLiveData("")
    val chapterTitle: LiveData<String> = _chapterTitle

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _chapterIndex = MutableLiveData(initialChapterIndex)
    val chapterIndex: LiveData<Int> = _chapterIndex

    private val _chapterList = MutableLiveData<List<Chapter>>(emptyList())
    val chapterList: LiveData<List<Chapter>> = _chapterList

    val book = MutableLiveData<Book>()

    private val _isInBookshelf = MutableLiveData(false)
    val isInBookshelf: LiveData<Boolean> = _isInBookshelf

    private val _downloadProgress = MutableLiveData("")
    val downloadProgress: LiveData<String> = _downloadProgress

    private val _isDownloading = MutableLiveData(false)
    val isDownloading: LiveData<Boolean> = _isDownloading

    private var currentChapters = mutableListOf<Chapter>()
    private val prefetchingSet = mutableSetOf<Int>()
    private var prefetchJob: Job? = null
    private var loadJob: Job? = null
    /** 目录到头时「顺着正文页链接翻章」的任务，避免重复触发 */
    private var navJob: Job? = null
    /** 批量下载任务，支持中途取消 */
    private var downloadJob: Job? = null
    private val prefetchCount = 10

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val book = repository.getBook(bookUrl)
                book?.let { this@ReaderViewModel.book.value = it }
                _isInBookshelf.value = repository.isInBookshelf(bookUrl)

                val chapters = repository.getChapterList(bookUrl)
                currentChapters = chapters.toMutableList()
                _chapterList.value = currentChapters

                if (currentChapters.isEmpty()) {
                    // 目录为空时必须明确报错，否则界面上只有一个转不完的圈
                    _chapterContent.value = "目录加载失败：未获取到章节列表"
                    _isLoading.value = false
                    return@launch
                }
                // 目标章节索引可能越界（进度来自旧数据），这里夹紧范围避免直接不出内容
                val safeIndex = (_chapterIndex.value ?: 0).coerceIn(0, currentChapters.size - 1)
                // 注意：这里必须「不经过 loadJob」直接加载。
                // loadChapter() 会先取消 loadJob，而当前协程正是被 viewModelScope 启动的，
                // 若把 loadJob 指到本协程再取消，会把调用者自己取消掉 → 永远不出正文。
                loadJob?.cancel()
                loadJob = viewModelScope.launch {
                    try {
                        doLoadChapter(safeIndex)
                    } finally {
                        _isLoading.value = false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chapterContent.value = "加载失败：${e.message}"
                _isLoading.value = false
            }
        }
    }

    /**
     * 加载指定章节（启动新任务，立即返回）。
     *
     * 关键修复：这里不能先 `loadJob?.cancel()` 再在**当前协程**里跑加载逻辑。
     * 因为 nextChapter()/prevChapter()/jumpToChapter()/loadData() 都会在
     * `viewModelScope.launch { ... }` 里调用本方法，此时 `loadJob` 可能就是
     * 调用者自己所在的协程——取消它就等于取消调用者，
     * 结果 doLoadChapter 永远不执行、_isLoading 永远停在 true，
     * 表现为“一直卡在加载中”。
     *
     * 正确做法：先把要取消的任务取出来，再启动新任务，由新任务自己去做取消。
     */
    fun loadChapter(index: Int) {
        if (index < 0 || index >= currentChapters.size) {
            // 索引非法时必须清掉加载态，否则界面会一直转圈
            _isLoading.value = false
            return
        }

        val previous = loadJob
        loadJob = viewModelScope.launch {
            previous?.cancel()
            try {
                doLoadChapter(index)
            } finally {
                // 无论正常结束还是被取消，都要保证加载态被复位；
                // 否则界面会永远停在「加载中...」
                _isLoading.value = false
            }
        }
    }

    private suspend fun doLoadChapter(index: Int) {
        _chapterIndex.value = index
        _isLoading.value = true
        try {
            val chapter = currentChapters[index]
            val cached = chapter.content
            // 兼容旧数据：之前版本可能把「加载失败/获取失败」这类提示写进过缓存，
            // 这类内容不能当正文显示，必须重新抓取
            val cachedIsErrorHint = cached.isNotBlank() &&
                (cached.startsWith("加载失败") || cached.startsWith("本章内容") || cached.startsWith("【章节URL】"))
            if (cached.isNotEmpty() && !cachedIsErrorHint) {
                _chapterContent.value = cached
                _chapterTitle.value = chapter.title
            } else {
                // 网络偶发抖动会导致单次抓取返回空串，直接把「获取失败」甩给用户
                // 体验很差（实测复现：第一次进入偶尔空白）。这里自动重试，
                // 间隔递增（0.8s / 1.6s），三次都失败才显示占位提示。
                var content = ""
                var lastError: Exception? = null
                for (attempt in 1..CONTENT_FETCH_ATTEMPTS) {
                    try {
                        content = repository.getChapterContent(chapter)
                        if (content.isNotBlank()) break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                    }
                    if (attempt < CONTENT_FETCH_ATTEMPTS) {
                        delay(CONTENT_RETRY_DELAY_MS * attempt)
                    }
                }
                // 空串 = 抓取失败。绝不能写进 currentChapters，
                // 否则后面每一章都会命中这段「缓存」，表现为翻章永远停在同一段文字。
                if (content.isNotBlank()) {
                    currentChapters[index] = chapter.copy(content = content)
                    _chapterContent.value = content
                } else {
                    _chapterContent.value = buildString {
                        append("本章内容暂时获取失败")
                        lastError?.message?.takeIf { it.isNotBlank() }?.let { append("：$it") }
                        append("\n\n点按屏幕中间呼出菜单，可重试或切换章节。")
                    }
                }
                _chapterTitle.value = chapter.title
            }
            prefetchAdjacentChapters(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _chapterContent.value = "加载失败：${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    /** 正文抓取失败时的自动重试次数。
     *
     * 注意：每次重试都可能等满 CHAPTER_TIMEOUT_MS（20 秒），
     * 所以总耗时 = 重试次数 × 超时 + 重试间隔。
     * 原来 3 次 × 45 秒 = 最长 2.3 分钟，用户等得崩溃。
     * 现在 2 次 × 20 秒 = 最长 40 秒，且首次正常响应只要 0.25 秒。
     */
    private companion object {
        const val CONTENT_FETCH_ATTEMPTS = 2
        const val CONTENT_RETRY_DELAY_MS = 500L
        /** 翻章防抖：600ms 内的重复翻章请求直接忽略 */
        const val CHAPTER_NAV_DEBOUNCE_MS = 600L
    }

    /**
     * 预加载相邻章节正文。
     *
     * 修复三个性能问题：
     * 1. 原来每次翻章都 cancel 预加载任务 → 用户翻到哪预加载就被杀到哪，永远追不上。
     *    现在让预加载持续运行，翻章只在需要新章节时补充。
     * 2. 原来一次预加载前后各 10 章（20 章），串行执行跑不完。
     *    现在只预加载下一章（用户最可能读的），2 章并发。
     * 3. 原来预加载是串行的。现在改成并发（2 章同时抓）。
     */
    private fun prefetchAdjacentChapters(currentIndex: Int) {
        // 不要 cancel 已有任务！让它继续跑。
        // 只有在已有任务已完成时才启动新的补充。
        if (prefetchJob?.isActive == true) return

        prefetchJob = viewModelScope.launch {
            // 只预加载接下来 2 章（用户最可能读的）
            val next = (currentIndex + 1)..minOf(currentChapters.size - 1, currentIndex + 2)
            val toFetch = next.filter { currentChapters[it].content.isEmpty() && !prefetchingSet.contains(it) }
            if (toFetch.isEmpty()) return@launch

            // 2 章并发抓取
            toFetch.map { i ->
                async {
                    prefetchingSet.add(i)
                    try {
                        val content = repository.getChapterContent(currentChapters[i])
                        if (content.isNotBlank()) {
                            currentChapters[i] = currentChapters[i].copy(content = content)
                        }
                    } catch (_: Exception) { }
                    prefetchingSet.remove(i)
                }
            }.awaitAll()
        }
    }

    /** 上一章 / 下一章的防抖时间戳，避免连点导致加载被反复取消 */
    private var lastChapterNavAt = 0L

    /**
     * 翻章防抖：上一次翻章还在加载时忽略新的翻章请求。
     *
     * 之前没有这个保护，连着点「下一章」会不断 cancel 上一次加载、
     * 又开新的加载，表现就是「点了没反应」或者停在原章。
     */
    private fun canNavigateChapter(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastChapterNavAt < CHAPTER_NAV_DEBOUNCE_MS) return false
        lastChapterNavAt = now
        return true
    }

    fun nextChapter() {
        if (!canNavigateChapter()) return
        val current = _chapterIndex.value ?: 0
        if (current < currentChapters.size - 1) {
            loadChapter(current + 1)
        } else {
            // 目录到头了：尝试顺着正文页的「下一章」链接继续翻，
            // 避免「目录只有 100 章，但书有 600 章」时彻底翻不动
            followPageNavigation(forward = true)
        }
    }

    fun prevChapter() {
        if (!canNavigateChapter()) return
        val current = _chapterIndex.value ?: 0
        if (current > 0) {
            loadChapter(current - 1)
        } else {
            followPageNavigation(forward = false)
        }
    }

    /**
     * 重试当前章节：清掉内存里的失败缓存后重新加载。
     * 用于自动重试仍失败时，用户手动触发。
     */
    fun retryCurrentChapter() {
        val index = _chapterIndex.value ?: return
        if (index in currentChapters.indices) {
            currentChapters[index] = currentChapters[index].copy(content = "")
        }
        loadChapter(index)
    }

    fun jumpToChapter(index: Int) {        loadChapter(index)
    }

    /**
     * 目录到头时的兜底翻章：顺着正文页自带的「下一章 / 上一章」链接走。
     *
     * 解决「目录只有前 100 章，读到第 100 章就翻不动」的问题。
     * 找到的新章节会追加/插入到 currentChapters，后续翻页重新走正常索引逻辑。
     */
    private fun followPageNavigation(forward: Boolean) {
        val current = _chapterIndex.value ?: 0
        val currentUrl = currentChapters.getOrNull(current)?.url ?: return
        if (navJob?.isActive == true) return

        navJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val adjacent = repository.findAdjacentChapter(currentUrl, forward)
                if (adjacent == null) {
                    _chapterContent.value = if (forward) "已经是最后一章了" else "已经是第一章了"
                    return@launch
                }
                // 去重后接到列表对应位置
                if (currentChapters.none { it.url == adjacent.url }) {
                    if (forward) {
                        currentChapters.add(adjacent)
                    } else {
                        currentChapters.add(0, adjacent)
                    }
                }
                val targetIndex = currentChapters.indexOfFirst { it.url == adjacent.url }
                if (targetIndex >= 0) {
                    _chapterList.value = currentChapters
                    doLoadChapter(targetIndex)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chapterContent.value = "翻章失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleBookshelf() {
        viewModelScope.launch {
            val inShelf = _isInBookshelf.value ?: false
            if (inShelf) {
                repository.removeFromBookshelf(bookUrl)
                _isInBookshelf.value = false
            } else {
                // 书架上只会显示存在于 books 表里的记录，所以加入前必须有书的信息，
                // 否则书架是空的（BookRepository.addToBookshelf 内部会兜底请求详情）
                repository.ensureBookExists(bookUrl)
                repository.addToBookshelf(bookUrl)
                _isInBookshelf.value = true
            }
        }
    }

    /**
     * 批量下载所有章节（离线缓存）。
     *
     * 改进点：
     * 1. 真正落库（原来只写内存，退出阅读页就丢）
     * 2. 可取消：600 章的书下载很久，用户必须能中途停止
     * 3. 跳过已缓存的章节，重复下载不会重头再来
     */
    fun downloadAllChapters() {
        if (_isDownloading.value == true) return
        downloadJob = viewModelScope.launch {
            _isDownloading.value = true
            val total = currentChapters.size
            var done = 0
            var failed = 0
            var skipped = 0
            try {
                for (i in currentChapters.indices) {
                    val chapter = currentChapters[i]
                    // 已有内容就直接跳过，避免重复请求
                    if (chapter.content.isNotEmpty()) {
                        repository.persistChapterContent(chapter, chapter.content)
                        skipped++
                        done++
                        _downloadProgress.value = "$done/$total"
                        continue
                    }
                    _downloadProgress.value = "$done/$total"
                    try {
                        val content = repository.getChapterContent(chapter)
                        if (content.isNotBlank()) {
                            currentChapters[i] = chapter.copy(content = content)
                            done++
                        } else {
                            failed++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed++
                    }
                }
                _downloadProgress.value = buildString {
                    append("完成 $done/$total")
                    if (skipped > 0) append("（已存在 $skipped）")
                    if (failed > 0) append("（失败 $failed）")
                }
            } catch (e: CancellationException) {
                // 用户主动取消：保留已下载的部分
                _downloadProgress.value = "已取消，已缓存 $done/$total"
                throw e
            } finally {
                _isDownloading.value = false
            }
        }
    }

    /** 取消正在进行的批量下载 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }
}