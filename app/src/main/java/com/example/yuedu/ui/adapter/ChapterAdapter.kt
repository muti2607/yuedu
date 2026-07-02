package com.example.yuedu.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.databinding.ItemChapterBinding

class ChapterAdapter(
    private val onItemClick: (Chapter) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.ViewHolder>() {

    private var chapters = listOf<Chapter>()

    fun submitList(list: List<Chapter>) {
        chapters = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChapterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(chapters[position])
    }

    override fun getItemCount(): Int = chapters.size

    inner class ViewHolder(
        private val binding: ItemChapterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chapter: Chapter) {
            binding.textChapterTitle.text = chapter.title
            binding.root.setOnClickListener { onItemClick(chapter) }
        }
    }
}