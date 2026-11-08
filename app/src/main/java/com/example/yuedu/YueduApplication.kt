package com.example.yuedu

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.example.yuedu.data.YueduPrefs
import com.example.yuedu.data.db.AppDatabase
import com.example.yuedu.data.network.HtmlParser
import com.example.yuedu.data.repository.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YueduApplication : Application(), ImageLoaderFactory {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: BookRepository
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_images"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        YueduPrefs.init(this)
        com.example.yuedu.data.network.site.SiteSettings.ensureInitialized()
        database = AppDatabase.getInstance(this)
        repository = BookRepository(database)

        preCacheBookStore()
    }

    private fun preCacheBookStore() {
        applicationScope.launch {
            try {
                val books = HtmlParser.getPopularBooks()
                if (books.isNotEmpty()) {
                    repository.cachePopularBooks(books)
                    // ImageLoader 单例只取一次：原来在循环里每本书 new 一个，
                    // 每次都新建线程池和缓存目录句柄，启动时明显卡顿
                    val imageLoader = this@YueduApplication.imageLoader
                    for (book in books) {
                        if (book.coverUrl.isNotBlank()) {
                            try {
                                val request = coil.request.ImageRequest.Builder(this@YueduApplication)
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
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        lateinit var instance: YueduApplication
            private set
    }
}