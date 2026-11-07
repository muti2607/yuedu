package com.example.yuedu.ui.adapter

import android.view.LayoutInflater
import android.view.View
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
        // 内容完全相同就不用刷新，避免每次观察者回调都整表重绘（图片闪烁）
        if (books == list) return
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

            // 阅读进度：只在书架里、并且确实读过才显示
            val progress = book.lastReadChapterIndex
            if (book.isInBookshelf && progress > 0) {
                binding.textProgress.visibility = View.VISIBLE
                binding.textProgress.text = "读到 ${progress + 1} 章"
            } else {
                binding.textProgress.visibility = View.GONE
            }

            // 没有封面时必须先把上一张图清掉，否则 RecyclerView 复用 ViewHolder 时
            // 会继续显示别的书的封面（表现为“书名和封面不匹配”）
            if (book.coverUrl.isBlank()) {
                binding.imgCover.setImageDrawable(null)
                binding.imgCover.setBackgroundResource(R.drawable.book_placeholder)
                binding.root.setOnClickListener { onItemClick(book) }
                return
            }
            binding.imgCover.setBackgroundResource(0)
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