package com.example.yuedu.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.repository.BookRepository

class HomeViewModel(private val repository: BookRepository) : ViewModel() {

    private val _refreshTrigger = MutableLiveData(0L)
    val refreshTrigger: LiveData<Long> = _refreshTrigger

    val books: LiveData<List<Book>> = repository.getBookshelfBooks().asLiveData()

    fun refresh() {
        _refreshTrigger.value = System.currentTimeMillis()
    }

    class Factory(private val repository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}
