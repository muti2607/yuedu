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

    /** 完整章节列表；详情页只渲染前 [PREVIEW_COUNT] 条，避免长书打开卡顿 */
    private var allChapters: List<com.example.yuedu.data.model.Chapter> = emptyList()

    companion object {
        private const val PREVIEW_COUNT = 30
    }

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

    /**
     * 只渲染前 [PREVIEW_COUNT] 章。
     *
     * 详情页的列表禁用滚动并嵌在 NestedScrollView 里，
     * 一次提交 600+ 项会全部创建 ViewHolder，打开详情页会明显卡顿。
     * 想看完整目录可以在阅读页点「目录」。
     */
    private fun renderChapterPreview() {
        chapterAdapter.submitList(allChapters.take(PREVIEW_COUNT))
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
                // 分类 · 状态 · 来源书源
                val source = HtmlParser.siteNameOf(it.url)
                val parts = mutableListOf<String>()
                if (it.category.isNotBlank()) parts.add(it.category)
                if (it.status.isNotBlank()) parts.add(it.status)
                parts.add(getString(R.string.source_site, source))
                binding.textCategory.text = parts.joinToString(" · ")
                binding.textLastChapter.text = when {
                    it.lastChapterTitle.isNotBlank() -> "最新章节：${it.lastChapterTitle}"
                    it.lastUpdateTime.isNotBlank() -> "最后更新：${it.lastUpdateTime}"
                    // 两个都拿不到就隐藏，避免出现「最新章节：」这样的空标签
                    else -> ""
                }
                binding.textLastChapter.visibility =
                    if (binding.textLastChapter.text.isNullOrBlank()) View.GONE else View.VISIBLE

                binding.toolbar.title = it.title

                // 有阅读进度时按钮文案改成"继续阅读"，并提示读到哪
                binding.btnRead.text = if (it.lastReadChapterIndex > 0) {
                    "继续阅读 · 第 ${it.lastReadChapterIndex + 1} 章"
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
            // 详情页的章节列表嵌在 NestedScrollView 里且不滚动，
            // 一次塞 600+ 项会全部实例化，打开详情页明显卡顿。
            // 这里只展示前若干章，其余引导到阅读页的目录抽屉。
            allChapters = chapters
            renderChapterPreview()

            binding.textChapterCount.text = if (chapters.isEmpty()) {
                getString(R.string.no_chapters)
            } else if (chapters.size > PREVIEW_COUNT) {
                // 明确告知只展示了一部分，完整目录在阅读页里
                "共 ${chapters.size} 章 · 完整目录请进入阅读"
            } else {
                "共 ${chapters.size} 章"
            }
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