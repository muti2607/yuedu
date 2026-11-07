package com.example.yuedu.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey
    val url: String,
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val category: String = "",
    val lastChapterTitle: String = "",
    val lastUpdateTime: String = "",
    /** 作品状态：已完结 / 连载中（来自站点 og:novel:status 或页面文本） */
    val status: String = "",
    val isInBookshelf: Boolean = false,
    val lastReadChapterIndex: Int = 0,
    val lastReadPosition: Int = 0,
    val addTime: Long = System.currentTimeMillis()
)