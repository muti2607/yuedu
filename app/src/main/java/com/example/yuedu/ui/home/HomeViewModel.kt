package com.example.yuedu.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * 书架页 ViewModel。
 *
 * 修复：原来「下拉刷新 / 返回书架自动刷新」其实是无效的 ——
 * refreshTrigger 只被写入，从未被任何逻辑消费，而书架数据来自 Room 的 Flow，
 * 只有数据库内容变化才会推新值，所以用户下拉后界面上什么都不会发生。
 * 现在把「数据流 + 刷新信号」组合起来，刷新会真正重新读取。
 */
class HomeViewModel(private val repository: BookRepository) : ViewModel() {

    /** 刷新信号：值变化即触发重新读取 */
    private val refreshSignal = MutableStateFlow(0L)

    val books: LiveData<List<Book>> =
        combine(repository.getBookshelfBooks(), refreshSignal) { list, _ -> list }
            .asLiveData()

    fun refresh() {
        refreshSignal.value = System.currentTimeMillis()
    }

    class Factory(private val repository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}

class HomeViewModelFactory(private val repository: BookRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(repository) as T
    }
}
