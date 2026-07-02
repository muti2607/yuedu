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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}