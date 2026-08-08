package com.example.yuedu.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.data.repository.BookRepository
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

    fun loadBookDetail() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val detail = repository.getBookDetail(bookUrl)
                _book.value = detail
            } catch (e: Exception) {
                // handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadChapterList() {
        viewModelScope.launch {
            try {
                val chapters = repository.getChapterList(bookUrl)
                _chapters.value = chapters
            } catch (e: Exception) {
                // handle error
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
            repository.addToBookshelf(bookUrl)
            _isInBookshelf.value = true
        }
    }

    fun removeFromBookshelf() {
        viewModelScope.launch {
            repository.removeFromBookshelf(bookUrl)
            _isInBookshelf.value = false
        }
    }

    /**
     * 获取当前书籍匹配的书源名称
     */
    fun getBookSourceName(): String {
        return repository.getBookSourceNameByUrl(bookUrl)
    }
}