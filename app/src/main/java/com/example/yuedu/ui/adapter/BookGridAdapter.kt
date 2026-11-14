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

            // 封面错配的真正原因：
            // Coil 的 load() 是异步的，而 RecyclerView 会复用 ViewHolder。
            // 原来只在「没有封面」时才清空图片，有封面时直接 load 新地址——
            // 上一本书的请求晚一步返回，就会把上一张图盖到这本新书上，
            // 用户看到的就是「书名和封面对不上」。
            // 现在无论有没有封面，都先用 tag 标记本次绑定，并立刻清空旧图。
            val cover = book.coverUrl
            val fallback = binding.textCoverFallback
            // 没有真实封面时，用书名首字生成一张「永远不会串」的封面
            if (cover.isNullOrBlank()) {
                fallback.visibility = View.VISIBLE
                fallback.text = book.title.take(1)
                fallback.setBackgroundColor(CoverPalette.colorFor(book.title))
                fallback.setTextColor(android.graphics.Color.WHITE)
            } else {
                fallback.visibility = View.GONE
            }
            binding.imgCover.tag = cover
            binding.imgCover.setImageDrawable(null)
            binding.imgCover.setBackgroundResource(R.drawable.book_placeholder)

            if (!cover.isNullOrBlank()) {
                binding.imgCover.load(cover) {
                    // 关掉淡入：淡入会让旧图多停留几百毫秒，错配观感更明显
                    crossfade(false)
                    placeholder(R.drawable.book_placeholder)
                    error(R.drawable.book_placeholder)
                    transformations(RoundedCornersTransformation(4f))
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    listener(
                        onSuccess = { _, result ->
                            // 只有这次绑定的封面仍是当前 tag 时才落图，
                            // 防止迟到的旧请求覆盖新书封面
                            if (binding.imgCover.tag == result.request.data) {
                                binding.imgCover.setBackgroundResource(0)
                            }
                        },
                        onError = { _, _ ->
                            binding.imgCover.setBackgroundResource(R.drawable.book_placeholder)
                        }
                    )
                }
            }

            binding.root.setOnClickListener { onItemClick(book) }
        }
    }
}
