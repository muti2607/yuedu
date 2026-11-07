package com.example.yuedu.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.request.CachePolicy
import com.example.yuedu.R
import com.example.yuedu.YueduApplication
import com.example.yuedu.data.network.HtmlParser
import com.example.yuedu.databinding.ActivityBookDetailBinding
import com.example.yuedu.ui.adapter.ChapterAdapter
import com.example.yuedu.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

class BookDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookDetailBinding
    private lateinit var viewModel: BookDetailViewModel
    private lateinit var chapterAdapter: ChapterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repository = (application as YueduApplication).repository
        val bookUrl = intent.getStringExtra("book_url") ?: return

        viewModel = BookDetailViewModel(repository, bookUrl)

        setupToolbar()
        setupChapterList()
        setupControls()
        observeData()
        loadData()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupChapterList() {
        chapterAdapter = ChapterAdapter { chapter ->
            viewModel.book.value?.let { book ->
                val intent = Intent(this, ReaderActivity::class.java)
                intent.putExtra("book_url", book.url)
                intent.putExtra("chapter_index", chapter.index)
                startActivity(intent)
            }
        }
        binding.recyclerChapters.adapter = chapterAdapter
    }

    private fun setupControls() {
        binding.btnBookshelf.setOnClickListener {
            lifecycleScope.launch {
                val inBookshelf = viewModel.isInBookshelf.value ?: false
                if (inBookshelf) {
                    viewModel.removeFromBookshelf()
                } else {
                    viewModel.addToBookshelf()
                }
            }
        }

        binding.btnRead.setOnClickListener {
            viewModel.book.value?.let { book ->
                val intent = Intent(this, ReaderActivity::class.java)
                intent.putExtra("book_url", book.url)
                intent.putExtra("chapter_index", book.lastReadChapterIndex)
                startActivity(intent)
            }
        }
    }

    private fun observeData() {
        // 只注册一次观察者：同一个 LiveData 注册两次 observer 会让 UI 回调执行两遍，
        // 阅读进度更新时容易重复触发
        viewModel.book.observe(this) { book ->
            book?.let {
                binding.textTitle.text = it.title
                binding.textAuthor.text = "作者：${it.author}"
                binding.textDescription.text = it.description
                // 分类 + 来源书源，直观看出这本书是从哪个站点抓的
                val source = HtmlParser.siteNameOf(it.url)
                binding.textCategory.text = if (it.category.isNotBlank()) {
                    "${it.category} · " + getString(R.string.source_site, source)
                } else {
                    getString(R.string.source_site, source)
                }
                binding.textLastChapter.text = "最新章节：${it.lastChapterTitle}"

                binding.toolbar.title = it.title

                // 有阅读进度时按钮文案改成“继续阅读”
                binding.btnRead.text = if (it.lastReadChapterIndex > 0) {
                    getString(R.string.continue_reading)
                } else {
                    getString(R.string.start_reading)
                }

                if (it.coverUrl.isNotBlank()) {
                    binding.imgCover.load(it.coverUrl) {
                        diskCachePolicy(CachePolicy.ENABLED)
                        memoryCachePolicy(CachePolicy.ENABLED)
                        placeholder(R.drawable.book_placeholder)
                        error(R.drawable.book_placeholder)
                    }
                }
            }
        }

        viewModel.chapters.observe(this) { chapters ->
            chapterAdapter.submitList(chapters)
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.isInBookshelf.observe(this) { inBookshelf ->
            if (inBookshelf) {
                binding.btnBookshelf.text = "移除书架"
            } else {
                binding.btnBookshelf.text = "加入书架"
            }
        }
    }

    private fun loadData() {
        viewModel.loadBookDetail()
        viewModel.loadChapterList()
        viewModel.checkBookshelfStatus()
    }
}