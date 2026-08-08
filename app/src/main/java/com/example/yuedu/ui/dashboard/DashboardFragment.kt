package com.example.yuedu.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
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

        setupRecyclerView()
        setupSearchView()
        setupSourceSwitcher()
        setupObservers()
    }

    private fun setupRecyclerView() {
        adapter = BookGridAdapter { book ->
            val intent = Intent(requireContext(), BookDetailActivity::class.java)
            intent.putExtra("book_url", book.url)
            startActivity(intent)
        }
        binding.recyclerBooks.adapter = adapter
        binding.recyclerBooks.layoutManager = GridLayoutManager(requireContext(), 3)
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    if (it.isNotBlank()) {
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
    }

    private fun setupSourceSwitcher() {
        // 点击"切换书源"按钮显示书源选择对话框
        binding.btnSwitchSource.setOnClickListener {
            showBookSourceDialog()
        }
    }

    /**
     * 显示书源选择对话框
     * 支持选择默认书源和启用/禁用各书源
     */
    private fun showBookSourceDialog() {
        val sources = viewModel.allSources.value ?: return
        val currentSource = viewModel.getCurrentSourceName()

        val options = sources.map { source ->
            val status = if (source.isEnabled) "✓" else "✗"
            val marker = if (source.name == currentSource) " ★" else ""
            "${status} [${source.group}] ${source.name}$marker"
        }.toTypedArray()

        val enabledFlags = sources.map { it.isEnabled }.toBooleanArray()

        AlertDialog.Builder(requireContext())
            .setTitle("选择默认书源（长按启用/禁用）")
            .setItems(options) { _, which ->
                // 单击切换为默认书源
                val selected = sources[which]
                if (selected.isEnabled) {
                    viewModel.switchBookSource(selected.id)
                    updateCurrentSourceDisplay()
                    // 切换后重新加载推荐
                    if (!binding.searchView.query.isNullOrBlank()) {
                        viewModel.search(binding.searchView.query.toString())
                    } else {
                        viewModel.loadPopularBooks()
                    }
                } else {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "该书源已禁用，请先启用",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setMultiChoiceItems(
                sources.map {
                    "${it.name}  (${it.baseUrl})"
                }.toTypedArray(),
                enabledFlags
            ) { dialog, which, isChecked ->
                val source = sources[which]
                viewModel.toggleSourceEnabled(source.id, isChecked)
            }
            .setPositiveButton("确定", null)
            .setNeutralButton("刷新推荐") { _, _ ->
                viewModel.loadPopularBooks()
            }
            .show()
    }

    private fun updateCurrentSourceDisplay() {
        val current = viewModel.getCurrentSourceName() ?: "全部书源"
        val enabledCount = viewModel.allSources.value?.count { it.isEnabled } ?: 0
        binding.textCurrentSource.text = "$current (已启用$enabledCount 个书源)"
    }

    private fun setupObservers() {
        viewModel.books.observe(viewLifecycleOwner) { bookList ->
            if (bookList.isNotEmpty()) {
                binding.progressLoading.visibility = View.GONE
                binding.textEmpty.visibility = View.GONE
                binding.recyclerBooks.visibility = View.VISIBLE
                adapter.submitList(bookList)
            }
        }

        viewModel.searchResults.observe(viewLifecycleOwner) { result ->
            if (viewModel.isSearching.value == true) {
                binding.progressLoading.visibility = View.GONE
                if (result.isEmpty()) {
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
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            binding.progressLoading.visibility = View.GONE
            if (error.isNotEmpty()) {
                binding.textEmpty.text = error
                binding.textEmpty.visibility = View.VISIBLE
            }
        }

        // 监听书源变化
        viewModel.currentSourceName.observe(viewLifecycleOwner) {
            updateCurrentSourceDisplay()
        }

        viewModel.allSources.observe(viewLifecycleOwner) {
            updateCurrentSourceDisplay()
        }

        // 初始化显示
        updateCurrentSourceDisplay()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
