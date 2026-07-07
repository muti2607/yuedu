package com.example.yuedu.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.yuedu.YueduApplication
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.network.HtmlParser
import com.example.yuedu.data.repository.BookRepository
import kotlinx.coroutines.launch

class DashboardViewModel(private val repository: BookRepository) : ViewModel() {

    private val _books = MutableLiveData<List<Book>>(emptyList())
    val books: LiveData<List<Book>> = _books

    private val _searchResults = MutableLiveData<List<Book>>(emptyList())
    val searchResults: LiveData<List<Book>> = _searchResults

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData("")
    val error: LiveData<String> = _error

    private val _isSearching = MutableLiveData(false)
    val isSearching: LiveData<Boolean> = _isSearching

    init {
        loadPopularBooks()
    }

    fun loadPopularBooks() {
        _searchResults.value = emptyList()
        _isSearching.value = false

        viewModelScope.launch {
            val cached = repository.getCachedPopularBooks()
            if (cached.isNotEmpty()) {
                _books.value = cached
            } else {
                _books.value = HtmlParser.getFallbackBooks()
            }
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = ""
            try {
                val result = HtmlParser.getPopularBooks()
                if (result.isNotEmpty()) {
                    val cached = repository.getCachedPopularBooks()
                    val cachedMap = cached.associateBy { it.url }
                    val merged = result.map { book ->
                        val cachedBook = cachedMap[book.url]
                        if (cachedBook != null && cachedBook.coverUrl.isNotBlank()) {
                            book.copy(coverUrl = cachedBook.coverUrl)
                        } else {
                            book
                        }
                    }
                    _books.value = merged
                    repository.cachePopularBooks(merged)
                    preCacheCovers(merged)
                    fetchMissingCovers(merged)
                }
            } catch (e: Exception) {
                if (_books.value.isNullOrEmpty()) {
                    _error.value = "加载失败：${e.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun preCacheCovers(books: List<Book>) {
        val context = YueduApplication.instance
        val imageLoader = ImageLoader(context)
        for (book in books) {
            if (book.coverUrl.isNotBlank()) {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(book.coverUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    imageLoader.enqueue(request)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun fetchMissingCovers(books: List<Book>) {
        viewModelScope.launch {
            val booksWithoutCover = books.filter { it.coverUrl.isBlank() }
            if (booksWithoutCover.isEmpty()) return@launch
            val updatedBooks = booksWithoutCover.mapNotNull { book ->
                try {
                    val detail = repository.getBookDetail(book.url)
                    if (detail.coverUrl.isNotBlank()) {
                        book.copy(coverUrl = detail.coverUrl)
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            if (updatedBooks.isNotEmpty()) {
                repository.cachePopularBooks(updatedBooks)
                preCacheCovers(updatedBooks)
                val updatedMap = updatedBooks.associateBy { it.url }
                val currentList = _books.value?.map { book ->
                    updatedMap[book.url] ?: book
                } ?: _books.value
                _books.value = currentList
            }
        }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = ""
            _isSearching.value = true
            try {
                val result = repository.searchBooks(keyword)
                _searchResults.value = result.books
                _books.value = emptyList()
                if (result.books.isEmpty()) {
                    _error.value = "未找到相关小说"
                }
            } catch (e: Exception) {
                _error.value = "搜索失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}

class DashboardViewModelFactory(private val repository: BookRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(repository) as T
    }
}