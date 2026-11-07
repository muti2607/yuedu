package com.example.yuedu.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.yuedu.YueduApplication
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.network.HtmlParser
import com.example.yuedu.data.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    /** 当前选中的分类；0 表示「推荐」（热门书单） */
    private val _selectedCategory = MutableLiveData(0)
    val selectedCategory: LiveData<Int> = _selectedCategory

    /** 可用分类（id、名称） */
    val categories: List<Pair<Int, String>> = HtmlParser.getCategories()

    /** 正在进行的搜索任务，用于防抖与竞态取消 */
    private var searchJob: Job? = null

    /** 分类切换任务，避免快速点击时旧结果覆盖新结果 */
    private var categoryJob: Job? = null

    init {
        loadPopularBooks()
    }

    /**
     * 切换分类。id = 0 回到推荐（热门书单）。
     */
    fun selectCategory(categoryId: Int) {
        _selectedCategory.value = categoryId
        _searchResults.value = emptyList()
        _isSearching.value = false

        if (categoryId == 0) {
            loadPopularBooks()
            return
        }

        categoryJob?.cancel()
        categoryJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = ""
            try {
                val list = HtmlParser.getBooksByCategory(categoryId)
                if (list.isEmpty()) {
                    _books.value = emptyList()
                    _error.value = "该分类暂时没有内容"
                } else {
                    _books.value = list
                    repository.cachePopularBooks(list)
                    preCacheCovers(list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "加载失败：${e.message}"
            } finally {
                if (isActive) _isLoading.value = false
            }
        }
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
        // 必须复用 Application 配置的 ImageLoader 单例。
        // 原来每调用一次就 new ImageLoader(context)：新建一套内存缓存、磁盘缓存和线程池，
        // 预热结果列表页根本用不到（列表用的是 Application 那个），白白下载一遍。
        val context = YueduApplication.instance
        val imageLoader = context.imageLoader
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

    /**
     * 给缺封面的书补详情页封面。
     *
     * 原来是串行逐本请求详情页，20 本书每本 1~2 秒就要半分钟，
     * 还和首屏渲染抢带宽。现在限并发 4，且整块挪到列表渲染之后再跑。
     */
    private fun fetchMissingCovers(books: List<Book>) {
        viewModelScope.launch {
            val booksWithoutCover = books.filter { it.coverUrl.isBlank() }
            if (booksWithoutCover.isEmpty()) return@launch
            val updatedBooks = booksWithoutCover
                .chunked(4)
                .flatMap { chunk ->
                    coroutineScope {
                        chunk.map { book ->
                            async {
                                try {
                                    val detail = repository.getBookDetail(book.url)
                                    if (detail.coverUrl.isNotBlank()) {
                                        book.copy(coverUrl = detail.coverUrl)
                                    } else null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }.awaitAll().filterNotNull()
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

        // 取消上一次搜索：逐字搜索时多个请求并发返回，旧关键词的结果可能后到并覆盖新结果
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = ""
            _isSearching.value = true
            try {
                // 输入框每变一次就发请求太频繁，做 300ms 防抖
                delay(300)
                val result = repository.searchBooks(keyword)
                _searchResults.value = result.books
                _books.value = emptyList()
                if (result.books.isEmpty()) {
                    _error.value = "未找到相关小说"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "搜索失败：${e.message}"
            } finally {
                if (isActive) {
                    _isLoading.value = false
                }
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