package com.example.yuedu.ui.adapter

import kotlin.math.abs

/**
 * 无封面书籍的配色。
 *
 * 用书名做稳定哈希选色，保证同一本书每次看到的颜色一致；
 * 配合书名首字，构成一张「永远和书名对得上」的兜底封面——
 * 比统一的灰色占位图更容易辨认，也不会出现串图。
 */
object CoverPalette {

    // 深色系：保证白色首字有足够对比度
    private val COLORS = intArrayOf(
        0xFF5B6C8F.toInt(), // 蓝灰
        0xFF8F5B6C.toInt(), // 玫瑰
        0xFF5B8F6C.toInt(), // 松绿
        0xFF8F7B5B.toInt(), // 驼色
        0xFF6C5B8F.toInt(), // 紫
        0xFF8F5B5B.toInt(), // 砖红
        0xFF5B7F8F.toInt(), // 青灰
        0xFF7A6A4F.toInt()  // 棕
    )

    fun colorFor(key: String): Int {
        if (key.isEmpty()) return COLORS[0]
        // 用绝对值取模；先转 long 避免 Integer.MIN_VALUE 溢出
        val h = abs(key.hashCode().toLong()) % COLORS.size
        return COLORS[h.toInt()]
    }
}
