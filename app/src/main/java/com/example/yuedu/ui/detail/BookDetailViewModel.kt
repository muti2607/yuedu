package com.example.yuedu.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BookDetailViewModel(
    private val repository: BookRepository,
    private val bookUrl: String
) : ViewModel() {

    private val _book = MutableLiveData<Book>()
    val book: LiveData<Book> = _book

    private val _chapters = MutableLiveData<List<Chapter>>(emptyList())
    val chapters: LiveData<List<Chapter>> = _chapters

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isInBookshelf = MutableLiveData(false)
    val isInBookshelf: LiveData<Boolean> = _isInBookshelf

    /** 加载失败提示；空串表示无错误 */
    private val _error = MutableLiveData("")
    val error: LiveData<String> = _error

    /**
     * 加载书籍详情。
     *
     * 原来异常被空 catch 吞掉，失败时页面一片空白、用户完全不知道发生了什么。
     * 现在把错误暴露成 LiveData，由界面提示并允许重试。
     */
    fun loadBookDetail() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = ""
            try {
                _book.value = repository.getBookDetail(bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "书籍信息加载失败：${e.message ?: "网络异常"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadChapterList() {
        viewModelScope.launch {
            try {
                _chapters.value = repository.getChapterList(bookUrl)
                prefetchFirstChapters()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 目录失败不覆盖书籍信息错误，单独提示
                if (_error.value.isNullOrEmpty()) {
                    _error.value = "目录加载失败：${e.message ?: "网络异常"}"
                }
            }
        }
    }

    fun checkBookshelfStatus() {
        viewModelScope.launch {
            _isInBookshelf.value = repository.isInBookshelf(bookUrl)
        }
    }

    fun addToBookshelf() {
        viewModelScope.launch {
            try {
                repository.addToBookshelf(bookUrl)
                _isInBookshelf.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "加入书架失败：${e.message ?: "网络异常"}"
            }
        }
    }

    fun removeFromBookshelf() {
        viewModelScope.launch {
            try {
                repository.removeFromBookshelf(bookUrl)
                _isInBookshelf.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "移出书架失败：${e.message ?: "网络异常"}"
            }
        }
    }

    /**
     * 预取前 3 章正文。
     *
     * 详情页打开后立刻在后台抓前几章正文，用户点「开始阅读」时第一屏秒出。
     * 站点正文页本身快（0.25s/章），预取 3 章只需不到 1 秒。
     */
    fun prefetchFirstChapters() {
        viewModelScope.launch {
            val chapters = _chapters.value ?: return@launch
            for (ch in chapters.take(3)) {
                try {
                    if (ch.content.isEmpty()) {
                        repository.getChapterContent(ch)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            }
        }
    }
}
