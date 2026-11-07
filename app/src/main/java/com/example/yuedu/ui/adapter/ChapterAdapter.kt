package com.example.yuedu.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.yuedu.R
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.databinding.ItemChapterBinding

class ChapterAdapter(
    private val onItemClick: (Chapter) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.ViewHolder>() {

    private var chapters = listOf<Chapter>()
    private var currentIndex = -1
    private var isNightMode = false

    fun submitList(list: List<Chapter>) {
        chapters = list
        notifyDataSetChanged()
    }

    fun setCurrentIndex(index: Int) {
        currentIndex = index
        notifyDataSetChanged()
    }

    fun setNightMode(night: Boolean) {
        isNightMode = night
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChapterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(chapters[position], position)
    }

    override fun getItemCount(): Int = chapters.size

    inner class ViewHolder(
        private val binding: ItemChapterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chapter: Chapter, position: Int) {
            binding.textChapterTitle.text = chapter.title

            val isCurrent = position == currentIndex
            val isLatest = position == chapters.size - 1

            // 用主题色，跟随日/夜模式，不再写死 #FF6B3A / #333333
            val highlight = ContextCompat.getColor(binding.root.context, R.color.orange_500)
            val normalNight = ContextCompat.getColor(binding.root.context, R.color.text_primary_dark)
            val normalDay = ContextCompat.getColor(binding.root.context, R.color.text_primary)
            binding.textChapterTitle.setTextColor(
                when {
                    isCurrent -> highlight
                    isNightMode -> normalNight
                    else -> normalDay
                }
            )

            binding.textLatestBadge.visibility = if (isLatest) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onItemClick(chapter) }
        }
    }
}