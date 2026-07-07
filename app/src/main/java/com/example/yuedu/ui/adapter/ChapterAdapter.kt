package com.example.yuedu.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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

            if (isCurrent) {
                binding.textChapterTitle.setTextColor(Color.parseColor("#FF6B3A"))
            } else {
                binding.textChapterTitle.setTextColor(
                    if (isNightMode) Color.parseColor("#CCCCCC") else Color.parseColor("#333333")
                )
            }

            binding.textLatestBadge.visibility = if (isLatest) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onItemClick(chapter) }
        }
    }
}