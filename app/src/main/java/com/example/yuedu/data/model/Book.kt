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
    val isInBookshelf: Boolean = false,
    val lastReadChapterIndex: Int = 0,
    val lastReadPosition: Int = 0,
    val addTime: Long = System.currentTimeMillis()
)