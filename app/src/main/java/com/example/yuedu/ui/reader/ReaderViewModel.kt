package com.example.yuedu.ui.reader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.repository.BookRepository
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

                loadChapter(_chapterIndex.value ?: 0)
            } catch (e: Exception) {
                _chapterContent.value = "加载失败：${e.message}"
                _isLoading.value = false
            }
        }
    }

    suspend fun loadChapter(index: Int) {
        if (index < 0 || index >= currentChapters.size) return

        _chapterIndex.value = index
        _isLoading.value = true
        try {
            val chapter = currentChapters[index]
            if (chapter.content.isNotEmpty()) {
                _chapterContent.value = chapter.content
                _chapterTitle.value = chapter.title
            } else {
                val content = repository.getChapterContent(chapter)
                currentChapters[index] = chapter.copy(content = content)
                _chapterContent.value = content
                _chapterTitle.value = chapter.title
            }
            prefetchAdjacentChapters(index)
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
                    currentChapters[i] = currentChapters[i].copy(content = content)
                } catch (_: Exception) { }
                prefetchingSet.remove(i)
            }
        }
    }

    fun nextChapter() {
        val current = _chapterIndex.value ?: 0
        if (current < currentChapters.size - 1) {
            viewModelScope.launch { loadChapter(current + 1) }
        }
    }

    fun prevChapter() {
        val current = _chapterIndex.value ?: 0
        if (current > 0) {
            viewModelScope.launch { loadChapter(current - 1) }
        }
    }

    fun jumpToChapter(index: Int) {
        viewModelScope.launch { loadChapter(index) }
    }

    fun toggleBookshelf() {
        viewModelScope.launch {
            val inShelf = _isInBookshelf.value ?: false
            if (inShelf) {
                repository.removeFromBookshelf(bookUrl)
                _isInBookshelf.value = false
            } else {
                repository.addToBookshelf(bookUrl)
                _isInBookshelf.value = true
            }
        }
    }

    fun downloadAllChapters() {
        if (_isDownloading.value == true) return
        viewModelScope.launch {
            _isDownloading.value = true
            val total = currentChapters.size
            var downloaded = 0
            for (i in currentChapters.indices) {
                _downloadProgress.value = "${downloaded + 1}/$total"
                try {
                    if (currentChapters[i].content.isEmpty()) {
                        val content = repository.getChapterContent(currentChapters[i])
                        currentChapters[i] = currentChapters[i].copy(content = content)
                    }
                    downloaded++
                } catch (_: Exception) {
                    downloaded++
                }
            }
            _downloadProgress.value = "完成 $downloaded/$total"
            _isDownloading.value = false
        }
    }
}