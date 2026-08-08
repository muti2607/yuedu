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
import com.example.yuedu.data.network.source.BookSource
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

    private val _currentSourceName = MutableLiveData<String>()
    val currentSourceName: LiveData<String> = _currentSourceName

    private val _allSources = MutableLiveData<List<BookSource>>(emptyList())
    val allSources: LiveData<List<BookSource>> = _allSources

    init {
        loadBookSources()
        loadPopularBooks()
    }

    /** 加载书源列表和当前书源信息 */
    private fun loadBookSources() {
        _allSources.value = repository.getAllBookSources()
        _currentSourceName.value = repository.getCurrentBookSource().name
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
                    _error.value = "未找到相关小说，请尝试切换书源"
                }
            } catch (e: Exception) {
                _error.value = "搜索失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 切换当前默认书源 */
    fun switchBookSource(sourceId: String) {
        repository.setCurrentBookSource(sourceId)
        _currentSourceName.value = repository.getCurrentBookSource().name
        // 刷新书源列表状态
        _allSources.value = repository.getAllBookSources()
    }

    /** 切换书源启用状态 */
    fun toggleSourceEnabled(sourceId: String, enabled: Boolean) {
        repository.toggleBookSourceEnabled(sourceId, enabled)
        _allSources.value = repository.getAllBookSources()
    }

    /** 获取当前书源名称 */
    fun getCurrentSourceName(): String? = _currentSourceName.value
}

class DashboardViewModelFactory(private val repository: BookRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(repository) as T
    }
}
