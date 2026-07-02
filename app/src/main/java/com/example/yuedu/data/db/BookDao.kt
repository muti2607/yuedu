package com.example.yuedu.data.db

import androidx.room.*
import com.example.yuedu.data.model.Book
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<Book>)

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("SELECT * FROM books WHERE isInBookshelf = 1 ORDER BY addTime DESC")
    fun getBookshelfBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY addTime DESC")
    suspend fun getAllCachedBooksSync(): List<Book>

    @Query("SELECT * FROM books WHERE url = :url")
    suspend fun getBookByUrl(url: String): Book?

    @Query("SELECT * FROM books WHERE url = :url")
    fun getBookByUrlFlow(url: String): Flow<Book?>

    @Query("UPDATE books SET lastReadChapterIndex = :chapterIndex, lastReadPosition = :position WHERE url = :bookUrl")
    suspend fun updateReadingProgress(bookUrl: String, chapterIndex: Int, position: Int)

    @Query("UPDATE books SET isInBookshelf = :inBookshelf WHERE url = :bookUrl")
    suspend fun updateBookshelfStatus(bookUrl: String, inBookshelf: Boolean)
}