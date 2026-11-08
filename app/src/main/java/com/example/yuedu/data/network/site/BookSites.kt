package com.example.yuedu.data.network.site

/**
 * 书源注册表。
 *
 * 新增站点时只需要：
 * 1. 写一个 [BookSite] 子类
 * 2. 在这里注册
 * 其余（详情/目录/正文的调度、缓存、分页拼接）由 SiteManager 统一处理。
 */
object BookSites {

    val all: List<BookSite> = listOf(
        BiqugaSite(),
        BqgNovelsSite(),
        BiqugeMomSite(),
        Biqu22Site(),
        Xiang5Site(),
        TiandiSite()
    )

    /** 默认书源（热门书单/搜索优先走它） */
    val primary: BookSite get() = all.first()

    /**
     * 按 URL 找到对应书源；找不到时回退到默认书源，
     * 保证旧库里已经存下的 URL 仍然能打开。
     */
    fun forUrl(url: String): BookSite {
        return all.firstOrNull { it.matches(url) } ?: fallbackFor(url)
    }

    private fun fallbackFor(url: String): BookSite {
        // 22biqu 的旧 URL 域名已经变了，别让它错误地交给 biquga 解析
        return if (url.contains("22biqu")) {
            all.filterIsInstance<Biqu22Site>().first()
        } else {
            primary
        }
    }

    /** 站点展示名 */
    fun nameFor(url: String): String = forUrl(url).name
}
