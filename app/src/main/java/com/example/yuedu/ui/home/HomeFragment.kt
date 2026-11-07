package com.example.yuedu.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import coil.request.CachePolicy
import com.example.yuedu.R
import com.example.yuedu.YueduApplication
import com.example.yuedu.data.model.Book
import com.example.yuedu.databinding.FragmentHomeBinding
import com.example.yuedu.ui.adapter.BookGridAdapter
import com.example.yuedu.ui.detail.BookDetailActivity
import com.example.yuedu.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory((requireActivity().application as YueduApplication).repository)
    }

    /** 直接取仓库查询「正在读的章节名」 */
    private val repository by lazy {
        (requireActivity().application as YueduApplication).repository
    }

    private lateinit var adapter: BookGridAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = BookGridAdapter { book ->
            val intent = Intent(requireContext(), BookDetailActivity::class.java)
            intent.putExtra("book_url", book.url)
            startActivity(intent)
        }

        binding.recyclerBookshelf.adapter = adapter
        binding.recyclerBookshelf.layoutManager = GridLayoutManager(requireContext(), 3)

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        viewModel.books.observe(viewLifecycleOwner) { books ->
            binding.swipeRefresh.isRefreshing = false
            render(books)
        }
    }

    /**
     * 渲染书架：顶部「继续阅读」卡片 + 下方书架网格。
     *
     * 「继续阅读」取最近有阅读进度的那本；没有阅读记录时整块隐藏，不占空间。
     */
    private fun render(books: List<Book>) {
        val hasShelf = books.isNotEmpty()
        binding.textEmpty.visibility = if (hasShelf) View.GONE else View.VISIBLE
        binding.recyclerBookshelf.visibility = if (hasShelf) View.VISIBLE else View.GONE
        binding.headerShelf.visibility = if (hasShelf) View.VISIBLE else View.GONE
        binding.textShelfCount.text = if (hasShelf) "共 ${books.size} 本" else ""

        if (hasShelf) {
            adapter.submitList(books)
        }

        val recent = books.filter { it.lastReadChapterIndex > 0 }
            .maxByOrNull { it.lastReadChapterIndex }
        if (recent != null) {
            binding.cardContinue.visibility = View.VISIBLE
            binding.textContinueTitle.text = recent.title
            // 先显示章节序号，随后异步换成真实章节名
            binding.textContinueChapter.text = "读到第 ${recent.lastReadChapterIndex + 1} 章"

            binding.cardContinue.setOnClickListener {
                val intent = Intent(requireContext(), ReaderActivity::class.java)
                intent.putExtra("book_url", recent.url)
                intent.putExtra("chapter_index", recent.lastReadChapterIndex)
                startActivity(intent)
            }

            loadChapterTitle(recent)
        } else {
            binding.cardContinue.visibility = View.GONE
        }
    }

    /** 封面与真实章节名异步加载，避免阻塞列表渲染 */
    private fun loadChapterTitle(book: Book) {
        if (book.coverUrl.isNotBlank()) {
            binding.imgContinueCover.load(book.coverUrl) {
                crossfade(true)
                placeholder(R.drawable.book_placeholder)
                error(R.drawable.book_placeholder)
                diskCachePolicy(CachePolicy.ENABLED)
                memoryCachePolicy(CachePolicy.ENABLED)
            }
        } else {
            binding.imgContinueCover.setImageDrawable(null)
            binding.imgContinueCover.setBackgroundResource(R.drawable.book_placeholder)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val title = repository.getReadingChapterTitle(book.url, book.lastReadChapterIndex)
            // 期间列表可能已刷新，确认仍是同一本书再写入
            if (title != null && _binding != null) {
                binding.textContinueChapter.text = "读到 $title"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 阅读进度可能已变化，回到书架时刷新一次
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
