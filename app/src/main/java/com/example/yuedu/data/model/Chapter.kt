package com.example.yuedu.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["url"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookUrl")]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookUrl: String = "",
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val index: Int = 0
)