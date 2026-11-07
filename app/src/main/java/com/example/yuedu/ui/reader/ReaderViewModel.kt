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
                loadChapter(safeIndex)
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
        if (index < 0 || index >= currentChapters.size) return

        val previous = loadJob
        loadJob = viewModelScope.launch {
            // 在新协程里取消旧任务，避免取消到调用者自己
            previous?.cancel()
            doLoadChapter(index)
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
                val content = repository.getChapterContent(chapter)
                // 空串 = 抓取失败。绝不能写进 currentChapters，
                // 否则后面每一章都会命中这段「缓存」，表现为翻章永远停在同一段文字。
                if (content.isNotBlank()) {
                    currentChapters[index] = chapter.copy(content = content)
                }
                _chapterContent.value = content.ifBlank { "本章内容暂时获取失败，请稍后重试或切换章节" }
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

    private fun prefetchAdjacentChapters(currentIndex: Int) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val chaptersToFetch = mutableListOf<Int>()

            for (i in (currentIndex + 1)..minOf(currentChapters.size - 1, currentIndex + prefetchCount)) {
                if (currentChapters[i].content.isEmpty() && !prefetchingSet.contains(i)) {
                    chaptersToFetch.add(i)
                }
            }
            for (i in maxOf(0, currentIndex - prefetchCount) until currentIndex) {
                if (currentChapters[i].content.isEmpty() && !prefetchingSet.contains(i)) {
                    chaptersToFetch.add(i)
                }
            }

            for (i in chaptersToFetch) {
                prefetchingSet.add(i)
                try {
                    val content = repository.getChapterContent(currentChapters[i])
                    // 失败(空串)不写缓存，下次还能重试
                    if (content.isNotBlank()) {
                        currentChapters[i] = currentChapters[i].copy(content = content)
                    }
                } catch (_: Exception) { }
                prefetchingSet.remove(i)
            }
        }
    }

    fun nextChapter() {
        val current = _chapterIndex.value ?: 0
        if (current < currentChapters.size - 1) {
            loadChapter(current + 1)
        }
    }

    fun prevChapter() {
        val current = _chapterIndex.value ?: 0
        if (current > 0) {
            loadChapter(current - 1)
        }
    }

    fun jumpToChapter(index: Int) {
        loadChapter(index)
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
     * 批量下载所有章节。
     *
     * 原来的实现只把内容写进内存列表 currentChapters，既不落库也不刷新 UI，
     * 退出阅读页后全部丢失，等于“每次都重新下载”。
     * 现在通过 repository.persistChapterContent 真正写入 Room。
     */
    fun downloadAllChapters() {
        if (_isDownloading.value == true) return
        viewModelScope.launch {
            _isDownloading.value = true
            val total = currentChapters.size
            var downloaded = 0
            var failed = 0
            try {
                for (i in currentChapters.indices) {
                    _downloadProgress.value = "${downloaded + 1}/$total"
                    val chapter = currentChapters[i]
                    try {
                        if (chapter.content.isEmpty()) {
                            val content = repository.getChapterContent(chapter)
                            currentChapters[i] = chapter.copy(content = content)
                        } else {
                            // 已在内存里但库里可能还没写，统一走 repository 落库
                            repository.persistChapterContent(chapter, chapter.content)
                        }
                        downloaded++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed++
                    }
                }
                _downloadProgress.value = if (failed > 0) {
                    "完成 $downloaded/$total（失败 $failed）"
                } else {
                    "完成 $downloaded/$total"
                }
            } finally {
                _isDownloading.value = false
            }
        }
    }
}