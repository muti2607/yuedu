package com.example.yuedu.data.network.source

import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter

/**
 * 书籍数据源接口
 *
 * 所有书源必须实现此接口，提供统一的操作方法。
 * 设计为可插拔架构，方便后续扩展新书源。
 */
interface BookSource {

    /** 书源唯一标识 */
    val id: String

    /** 书源显示名称 */
    val name: String

    /** 基础URL */
    val baseUrl: String

    /** 书源分组 */
    val group: String

    /** 是否启用 */
    var isEnabled: Boolean

    /**
     * 搜索书籍
     *
     * @param keyword 搜索关键词
     * @param page 页码，从1开始
     * @return 搜索结果，包含书籍列表和是否有更多
     */
    suspend fun searchBooks(keyword: String, page: Int = 1): SearchResult

    /**
     * 获取书籍详情
     *
     * @param bookUrl 书籍详情页URL（相对于书源的URL或完整URL）
     * @return 完整的书籍信息
     */
    suspend fun getBookDetail(bookUrl: String): Book

    /**
     * 获取章节列表
     *
     * @param bookUrl 书籍详情页URL
     * @return 章节列表
     */
    suspend fun getChapterList(bookUrl: String): List<Chapter>

    /**
     * 获取章节正文内容
     *
     * @param chapterUrl 章节URL
     * @return 章节正文文本
     */
    suspend fun getChapterContent(chapterUrl: String): String

    /**
     * 获取热门/推荐书籍
     *
     * @return 推荐书籍列表
     */
    suspend fun getPopularBooks(): List<Book>

    /**
     * 解析相对路径为完整URL
     *
     * @param path 相对路径或完整URL
     * @return 完整的URL
     */
    fun resolveUrl(path: String): String

    /**
     * 搜索结果封装
     */
    data class SearchResult(
        val books: List<Book>,
        val hasMore: Boolean = false
    )
}
