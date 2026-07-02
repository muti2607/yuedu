package com.example.yuedu.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import coil.transform.RoundedCornersTransformation
import com.example.yuedu.R
import com.example.yuedu.data.model.Book
import com.example.yuedu.databinding.ItemBookGridBinding

class BookGridAdapter(
    private val onItemClick: (Book) -> Unit
) : RecyclerView.Adapter<BookGridAdapter.ViewHolder>() {

    private var books = listOf<Book>()

    fun submitList(list: List<Book>) {
        books = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(books[position])
    }

    override fun getItemCount(): Int = books.size

    inner class ViewHolder(
        private val binding: ItemBookGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(book: Book) {
            binding.textTitle.text = book.title
            binding.textAuthor.text = book.author
            binding.imgCover.load(book.coverUrl) {
                crossfade(true)
                placeholder(R.drawable.book_placeholder)
                error(R.drawable.book_placeholder)
                transformations(RoundedCornersTransformation(4f))
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
            }
            binding.root.setOnClickListener { onItemClick(book) }
        }
    }
}