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

    /** 书城列表的目录预热任务 */
    private var prefetchJob: Job? = null

    /** 本地书库索引预热任务（扩大搜索覆盖面） */
    private var warmUpJob: Job? = null

    init {
        loadPopularBooks()
        // 后台静默填充本地书库索引，让搜索能即时命中更多热门书
        warmUpJob = viewModelScope.launch {
            repository.warmUpLocalBookIndex()
        }
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

    /**
     * 空闲时预热目录。
     *
     * biquga 的详情页本身极慢（单次 6~9 秒，页面仅 23KB，是站点侧建页慢）。
     * 这里在书城列表出来后，立刻在后台预热第 1 本书的详情+目录。
     *
     * 只预热第 1 本（最可能被点击）：并发预热 3 本会被站点限流反而更慢（13.5s/本），
     * 串行预热第 1 本只要 6~9 秒。
     * 允许列表更新时取消旧任务重新预热（旧列表可能不是用户看到的书）。
     */
    fun prefetchTopBooks(books: List<Book>) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val first = books.firstOrNull() ?: return@launch
            if (repository.hasFreshChapterList(first.url)) return@launch
            try {
                repository.getBookDetail(first.url)
                val chapters = repository.getChapterList(first.url)
                // 预取前 3 章正文：站点正文页快（0.25s/章），3 章不到 1 秒。
                // 这样用户点「开始阅读」时第一屏秒出，不用等 2-3 秒。
                for (ch in chapters.take(3)) {
                    try {
                        if (ch.content.isEmpty()) repository.getChapterContent(ch)
                    } catch (_: Exception) { }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 预热失败无所谓，用户点进去会重新加载
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

                // 1) 先出本地缓存结果（0 网络，毫秒级），让用户立刻看到内容
                val local = repository.searchLocalBooks(keyword)
                if (local.isNotEmpty()) {
                    _searchResults.value = local
                    _books.value = emptyList()
                    _isLoading.value = false
                }

                // 2) 再补网络结果，后到时合并去重；网络慢/失败也不影响已显示的本地结果
                val remote = repository.searchBooks(keyword)
                val merged = (local + remote.books).distinctBy { it.title + "|" + it.author }
                _searchResults.value = merged
                _books.value = emptyList()
                if (merged.isEmpty()) {
                    _error.value = "未找到相关小说，换个关键词试试"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "搜索失败：${e.message}"
            } finally {
                if (isActive) {
                    _isLoading.value = false
                    // 必须同时清掉搜索态：之前只重置了 isLoading，
                    // 「搜索小说…」的提示一直挂在界面上不消失。
                    _isSearching.value = false
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
