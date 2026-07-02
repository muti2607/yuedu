package com.example.yuedu.data.db

import androidx.room.*
import com.example.yuedu.data.model.Chapter
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<Chapter>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: Chapter)

    @Update
    suspend fun updateChapter(chapter: Chapter)

    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY `index` ASC")
    fun getChaptersByBookUrl(bookUrl: String): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY `index` ASC")
    suspend fun getChaptersByBookUrlSync(bookUrl: String): List<Chapter>

    @Query("SELECT * FROM chapters WHERE url = :url")
    suspend fun getChapterByUrl(url: String): Chapter?

    @Query("DELETE FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun deleteChaptersByBookUrl(bookUrl: String)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun getChapterCount(bookUrl: String): Int

    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY `index` ASC LIMIT 1 OFFSET :offset")
    suspend fun getChapterAtOffset(bookUrl: String, offset: Int): Chapter?
}