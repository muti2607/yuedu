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
    /**
     * 作品状态：已完结 / 连载中（来自站点 og:novel:status 或页面文本）
     *
     * 这里刻意**不加** @ColumnInfo(defaultValue)：Room 期望的建表语句是
     * `status TEXT NOT NULL`（无 DEFAULT）。迁移通过「重建表」实现，
     * 复制旧数据时用 '' 填充，从而与 Room 的期望完全一致。
     * 若实体声明了 DEFAULT 而迁移没加（或反之），Room 校验失败会导致启动崩溃。
     */
    val status: String = "",
    val isInBookshelf: Boolean = false,
    val lastReadChapterIndex: Int = 0,
    val lastReadPosition: Int = 0,
    val addTime: Long = System.currentTimeMillis()
)