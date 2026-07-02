package com.example.yuedu.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.yuedu.YueduApplication
import com.example.yuedu.databinding.FragmentHomeBinding
import com.example.yuedu.ui.adapter.BookGridAdapter
import com.example.yuedu.ui.detail.BookDetailActivity

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory((requireActivity().application as YueduApplication).repository)
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

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        viewModel.books.observe(viewLifecycleOwner) { books ->
            binding.swipeRefresh.isRefreshing = false
            if (books.isEmpty()) {
                binding.textEmpty.visibility = View.VISIBLE
                binding.recyclerBookshelf.visibility = View.GONE
            } else {
                binding.textEmpty.visibility = View.GONE
                binding.recyclerBookshelf.visibility = View.VISIBLE
                adapter.submitList(books)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}