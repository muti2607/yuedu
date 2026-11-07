package com.example.yuedu.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.yuedu.R
import com.example.yuedu.YueduApplication
import com.example.yuedu.databinding.FragmentDashboardBinding
import com.example.yuedu.ui.adapter.BookGridAdapter
import com.example.yuedu.ui.detail.BookDetailActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory((requireActivity().application as YueduApplication).repository)
    }

    private lateinit var adapter: BookGridAdapter
    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = BookGridAdapter { book ->
            val intent = Intent(requireContext(), BookDetailActivity::class.java)
            intent.putExtra("book_url", book.url)
            startActivity(intent)
        }

        binding.recyclerBooks.adapter = adapter
        binding.recyclerBooks.layoutManager = GridLayoutManager(requireContext(), 3)

        setupCategoryChips()

        binding.searchView.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    if (it.isNotBlank()) {
                        // 搜索会依次尝试多个书源，先给个提示避免以为卡住
                        binding.textEmpty.text = getString(R.string.searching_multiple_sites)
                        binding.textEmpty.visibility = View.VISIBLE
                        viewModel.search(it)
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                newText?.let { text ->
                    if (text.length >= 2) {
                        searchJob = lifecycleScope.launch {
                            delay(500)
                            viewModel.search(text)
                        }
                    }
                }
                return true
            }
        })

        binding.searchView.setOnCloseListener {
            viewModel.loadPopularBooks()
            false
        }

        viewModel.books.observe(viewLifecycleOwner) { bookList ->
            // 搜索时 books 会被清空；此时不能把列表藏起来，否则退出搜索后一片空白、
            // 也无法回到书城列表（原来只有 isNotEmpty() 才更新 UI，空列表等于卡死）
            if (viewModel.isSearching.value == true) return@observe
            if (bookList.isEmpty()) {
                binding.progressLoading.visibility = View.GONE
                binding.recyclerBooks.visibility = View.GONE
                binding.textEmpty.text = getString(R.string.bookstore_empty)
                binding.textEmpty.visibility = View.VISIBLE
            } else {
                binding.progressLoading.visibility = View.GONE
                binding.textEmpty.visibility = View.GONE
                binding.recyclerBooks.visibility = View.VISIBLE
                adapter.submitList(bookList)
                // 列表出来后立刻在后台预热前几本书的详情+目录，
                // 用户点进去时目录已落库，避免在详情页干等 6~9 秒
                viewModel.prefetchTopBooks(bookList)
            }
        }

        viewModel.searchResults.observe(viewLifecycleOwner) { result ->
            if (viewModel.isSearching.value == true) {
                binding.progressLoading.visibility = View.GONE
                if (result.isEmpty()) {
                    binding.textEmpty.text = getString(R.string.search_result_empty)
                    binding.textEmpty.visibility = View.VISIBLE
                    binding.recyclerBooks.visibility = View.GONE
                } else {
                    binding.textEmpty.visibility = View.GONE
                    binding.recyclerBooks.visibility = View.VISIBLE
                    adapter.submitList(result)
                }
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (loading) {
                binding.progressLoading.visibility = View.VISIBLE
            } else {
                binding.progressLoading.visibility = View.GONE
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            binding.progressLoading.visibility = View.GONE
            if (error.isNotEmpty()) {
                binding.textEmpty.text = error
                binding.textEmpty.visibility = View.VISIBLE
            }
        }

        viewModel.selectedCategory.observe(viewLifecycleOwner) { selected ->
            updateChipSelection(selected)
        }
    }

    /**
     * 生成分类胶囊：「推荐」+ 各分类。
     * 用代码生成而不是在 XML 里写死，分类有增减时只改数据层即可。
     */
    private fun setupCategoryChips() {
        val container = binding.layoutCategories
        container.removeAllViews()

        val items = listOf(0 to "推荐") + viewModel.categories
        for ((id, name) in items) {
            val chip = layoutInflater.inflate(R.layout.item_category_chip, container, false)
                as android.widget.TextView
            chip.text = name
            chip.tag = id
            chip.setOnClickListener {
                // 点到已选中的分类就不重复请求
                if (viewModel.selectedCategory.value != id) {
                    viewModel.selectCategory(id)
                }
            }
            container.addView(chip)
        }
        updateChipSelection(viewModel.selectedCategory.value ?: 0)
    }

    /** 更新胶囊选中态：选中项实心主色 + 白字 */
    private fun updateChipSelection(selectedId: Int) {
        val container = binding.layoutCategories
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? android.widget.TextView ?: continue
            val isSelected = (chip.tag as? Int) == selectedId
            chip.isSelected = isSelected
            chip.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (isSelected) R.color.white else R.color.text_primary
                )
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}