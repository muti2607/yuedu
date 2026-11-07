package com.example.yuedu.ui.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.yuedu.YueduApplication
import com.example.yuedu.R
import com.example.yuedu.data.model.Chapter
import com.example.yuedu.databinding.ActivityReaderBinding
import com.example.yuedu.ui.adapter.ChapterAdapter
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小说阅读界面 Activity
 * 负责展示章节内容、处理用户交互、管理分页、夜模式、字体大小等阅读功能
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel
    private var isNightMode = false
    private var currentFontSize = 18f
    // 布局里顶/底栏默认 gone（沉浸式阅读），这里必须同步为 false，
    // 否则第一次点屏幕中间只会「隐藏」而不是「唤出」
    private var isControlsVisible = false
    private var pages = listOf<String>()
    private var isNavigatingChapter = false
    private var currentPageIndex = 0
    private var isCatalogVisible = false
    private var catalogAnimator: ValueAnimator? = null
    private lateinit var catalogAdapter: ChapterAdapter

    /** 行距倍数，与分页计算保持一致，避免排版和显示对不上 */
    private val lineSpacing = 1.8f

    /**
     * Activity 创建时初始化
     * 设置视图绑定、获取传递参数、初始化 ViewModel、配置视图和观察者
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        val repository = (application as YueduApplication).repository
        val bookUrl = intent.getStringExtra("book_url") ?: return
        val initialChapterIndex = intent.getIntExtra("chapter_index", 0)

        lifecycleScope.launch {
            val savedChapterIndex = repository.getBook(bookUrl)?.lastReadChapterIndex ?: initialChapterIndex
            viewModel = ReaderViewModel(repository, bookUrl, if (savedChapterIndex >= 0) savedChapterIndex else initialChapterIndex)

            setupViewPager()
            setupControls()
            setupCatalogSheet()
            observeData()
        }
    }

    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 80f

    /**
     * 配置 ViewPager2
     * 设置预加载页数、翻页动效、页面切换回调
     */
    private fun setupViewPager() {
        binding.viewpagerReader.offscreenPageLimit = 1
        binding.viewpagerReader.setPageTransformer(DepthPageTransformer())

        binding.viewpagerReader.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPageIndex = position
                updateProgressBar()
                updateReaderFooter()
            }
        })
    }

    /**
     * 分发触摸事件，处理手势交互
     * - 左右滑动超过阈值时，跨章节跳转
     * - 轻触点击分三区处理：左侧上一页，右侧下一页，中间切换导航栏显示
     * - 如果导航栏可见且点击落在顶栏/底栏区域，放行事件让按钮响应
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isCatalogVisible) {
            val touchY = event.y
            if (event.action == MotionEvent.ACTION_UP && touchY < binding.root.height - binding.layoutCatalogSheet.height) {
                hideCatalogSheet()
                return true
            }
            return super.dispatchTouchEvent(event)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - touchStartX
                val deltaY = abs(event.y - touchStartY)
                if (abs(deltaX) > swipeThreshold && abs(deltaX) > deltaY) {
                    val currentPos = binding.viewpagerReader.currentItem
                    if (deltaX < 0 && currentPos >= pages.size - 1) {
                        viewModel.nextChapter()
                        return true
                    } else if (deltaX > 0 && currentPos <= 0) {
                        viewModel.prevChapter()
                        return true
                    }
                } else if (abs(deltaX) < swipeThreshold / 2 && abs(deltaY) < swipeThreshold / 2) {
                    if (isControlsVisible) {
                        val topBarHeight = binding.layoutTopBar.height
                        val bottomBarHeight = binding.layoutBottomBar.height
                        val tapY = event.y
                        val rootHeight = binding.root.height
                        if (tapY < topBarHeight || tapY > rootHeight - bottomBarHeight) {
                            return super.dispatchTouchEvent(event)
                        }
                    }
                    val screenWidth = resources.displayMetrics.widthPixels
                    val tapX = event.x
                    if (tapX > screenWidth * 2 / 3) {
                        val currentPos = binding.viewpagerReader.currentItem
                        if (currentPos < pages.size - 1) {
                            binding.viewpagerReader.setCurrentItem(currentPos + 1, true)
                        } else {
                            viewModel.nextChapter()
                        }
                        return true
                    } else if (tapX < screenWidth / 3) {
                        val currentPos = binding.viewpagerReader.currentItem
                        if (currentPos > 0) {
                            binding.viewpagerReader.setCurrentItem(currentPos - 1, true)
                        } else {
                            viewModel.prevChapter()
                        }
                        return true
                    } else {
                        toggleControls()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * 设置底部导航栏所有按钮的点击监听
     * 包括收藏、下载、评论、分享、更多、目录、上下章切换、夜模式、字体设置、进度条
     */
    private fun setupControls() {
        binding.btnAddShelf.setOnClickListener {
            viewModel.toggleBookshelf()
        }

        binding.btnDownload.setOnClickListener {
            val total = viewModel.chapterList.value?.size ?: 0
            if (total == 0) {
                Toast.makeText(this, "章节列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "开始下载 $total 章...", Toast.LENGTH_SHORT).show()
            viewModel.downloadAllChapters()
        }

        binding.layoutComment.setOnClickListener {
            Toast.makeText(this, "Comment", Toast.LENGTH_SHORT).show()
        }

        binding.btnShare.setOnClickListener {
            Toast.makeText(this, R.string.share, Toast.LENGTH_SHORT).show()
        }

        binding.btnMore.setOnClickListener {
            Toast.makeText(this, R.string.more, Toast.LENGTH_SHORT).show()
        }

        binding.btnCatalog.setOnClickListener {
            showCatalogSheet()
        }

        binding.btnPrevChapter.setOnClickListener {
            viewModel.prevChapter()
        }

        binding.btnNextChapter.setOnClickListener {
            viewModel.nextChapter()
        }

        binding.btnNightMode.setOnClickListener {
            toggleNightMode()
        }

        binding.btnSettings.setOnClickListener {
            showFontSizeDialog()
        }

        binding.seekbarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var fromUserDrag = false

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) fromUserDrag = true
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                fromUserDrag = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (fromUserDrag) {
                    val chapterCount = viewModel.chapterList.value?.size ?: return
                    if (chapterCount > 0) {
                        val targetChapter = (seekBar?.progress?.toFloat()?.div(100f)?.times(chapterCount - 1))?.toInt() ?: return
                        viewModel.jumpToChapter(targetChapter)
                    }
                }
            }
        })
    }

    /**
     * 观察 ViewModel 数据变化，更新 UI
     * 监听章节内容、标题、加载状态、章节索引、章节列表、收藏状态、下载进度等
     */
    private fun observeData() {
        // 记录上一次渲染的章节序号，用来区分「换章」和「改字号重排」
        var lastRenderedChapter = -1
        viewModel.chapterContent.observe(this) { content ->
            binding.progressLoading.visibility = View.GONE
            val chapter = viewModel.chapterIndex.value ?: 0
            val isNewChapter = chapter != lastRenderedChapter
            lastRenderedChapter = chapter
            binding.viewpagerReader.post {
                paginateAndDisplay(content, resetToFirstPage = isNewChapter)
            }
        }

        viewModel.chapterTitle.observe(this) { title ->
            // 章节名交给每页顶部的标题栏渲染（displayPages 会重建 adapter），
            // 底部提示条只用于下载/字号这类临时状态，不再重复显示章节名
            updatePageTitles(title)
        }

        viewModel.isLoading.observe(this) { loading ->
            // 原来只处理 true 不处理 false，加载圈一旦出现就再也不消失
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.chapterIndex.observe(this) { index ->
            viewModel.book.value?.let { book ->
                lifecycleScope.launch {
                    viewModel.repository.updateReadingProgress(book.url, index, currentPageIndex)
                }
            }
            updateProgressBar()
        }

        viewModel.chapterList.observe(this) { chapters ->
            updateProgressBar()
        }

        viewModel.isInBookshelf.observe(this) { inShelf ->
            binding.btnAddShelf.text = if (inShelf) "已收藏" else "加入书架"
        }

        viewModel.downloadProgress.observe(this) { progress ->
            if (progress.isNotEmpty()) {
                showReaderStatus("下载中: $progress")
            }
        }

        viewModel.isDownloading.observe(this) { downloading ->
            if (!downloading && viewModel.downloadProgress.value?.isNotEmpty() == true) {
                binding.textChapterTitle.postDelayed({
                    restoreChapterTitle()
                }, 2000)
            }
        }
    }

    /**
     * textChapterTitle 是底部的临时状态条（下载中 / 字号预览）。
     * 章节名由每页顶部的标题栏负责，这里不再重复显示章节名，
     * 状态结束后直接隐藏即可。
     */
    private fun restoreChapterTitle() {
        binding.textChapterTitle.visibility = View.GONE
    }

    /** 显示一条临时状态提示（下载进度、字号预览等） */
    private fun showReaderStatus(text: String) {
        binding.textChapterTitle.text = text
        binding.textChapterTitle.visibility = View.VISIBLE
    }

    /** 章节名变化时同步到每一页顶部的标题栏 */
    private fun updatePageTitles(title: String) {
        (binding.viewpagerReader.adapter as? ReaderPagerAdapter)?.updateChapterTitle(title)
    }

    /**
     * 更新底部进度条和页码信息
     * 根据当前章节索引和总章节数计算进度百分比
     */
    private fun updateProgressBar() {
        val chapterCount = viewModel.chapterList.value?.size ?: 0
        val currentChapter = (viewModel.chapterIndex.value ?: 0)
        if (chapterCount > 1) {
            val progress = (currentChapter.toFloat() / (chapterCount - 1) * 100).toInt()
            binding.seekbarProgress.progress = progress
        } else {
            binding.seekbarProgress.progress = 0
        }
        binding.textPageInfo.text = if (chapterCount > 0) {
            "${currentChapter + 1}/$chapterCount"
        } else {
            ""
        }
    }

    private fun updateReaderFooter() {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val batteryLevel = getBatteryLevel()
        val pageCount = binding.viewpagerReader.adapter?.itemCount ?: 0
        val currentPage = currentPageIndex + 1
        binding.textReaderFooter.text = "$currentPage/$pageCount  $timeStr  \uD83D\uDD0B$batteryLevel%"
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (scale > 0) (level * 100 / scale) else 100
    }

    /**
     * 将章节内容分页并显示到 ViewPager
     * 使用 StaticLayout 计算每行文字位置，根据可用高度切割内容
     * 减去半行高度让内容底部向上提半行，避免文字紧贴底部
     */
    private fun paginateAndDisplay(rawContent: String, resetToFirstPage: Boolean = false) {
        // 段首缩进 + 段间空行：主流阅读器的排版。
        // 必须在分页「之前」格式化，让 StaticLayout 计算与 TextView 显示用同一份文本，
        // 否则切页位置会错位。
        val content = formatParagraphs(rawContent)
        val paint = TextPaint()
        paint.textSize = spToPx(currentFontSize).toFloat()
        paint.isAntiAlias = true
        paint.typeface = Typeface.SERIF

        // 与 onCreateViewHolder 里的 padding 保持一致：左右各 24dp
        val pageWidth = binding.viewpagerReader.width - dpToPx(24f) * 2
        val fullPageHeight = binding.viewpagerReader.height
        // 标题区 22dp 上留白 + 13sp 文字 + 14dp 下留白，留出余量避免文字被裁
        val titleHeight = dpToPx(56f)

        if (pageWidth <= 0 || fullPageHeight <= 0) {
            pages = listOf(content)
            displayPages(resetToFirstPage)
            return
        }

        fun buildLayout(text: String): StaticLayout {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, pageWidth)
                // 行距用 “字号(px) × 1.8”，不能直接拿 sp 数值当 px 用，否则行距几乎为 0
                .setLineSpacing(spToPx(currentFontSize) * (lineSpacing - 1f), 1f)
                .setMaxLines(Int.MAX_VALUE)
                .build()
        }

        val pageList = mutableListOf<String>()
        var offset = 0
        val textLength = content.length
        val footerMargin = dpToPx(20f)
        val halfLineHeight = spToPx(currentFontSize * 0.5f)
        val usableHeight = fullPageHeight - titleHeight - footerMargin - halfLineHeight

        while (offset < textLength) {
            val layout = buildLayout(content.substring(offset))
            val totalLines = layout.lineCount
            if (totalLines == 0) break

            var endLine = 0
            while (endLine < totalLines && layout.getLineBottom(endLine) <= usableHeight) {
                endLine++
            }
            if (endLine == 0) endLine = 1

            val endOffset = offset + layout.getLineStart(endLine)
            val safeEnd = maxOf(endOffset, offset + 1).coerceAtMost(textLength)
            val pageText = content.substring(offset, safeEnd).trimEnd()
            pageList.add(pageText)
            offset = safeEnd
        }

        if (pageList.isEmpty()) {
            pageList.add(content)
        }

        pages = pageList
        displayPages(resetToFirstPage)
    }

    /**
     * 将分好页的内容更新到适配器并显示
     * 同时应用主题和字体大小
     */
    private fun displayPages(resetToFirstPage: Boolean = false) {
        isNavigatingChapter = true
        val chapterTitle = viewModel.chapterTitle.value ?: ""
        val adapter = ReaderPagerAdapter(pages, chapterTitle) { pageView ->
            // Click handling is done in dispatchTouchEvent
        }
        binding.viewpagerReader.adapter = adapter
        // 换章(resetToFirstPage=true)必须回到第 0 页；
        // 只有「改字号重排」才保留当前页位。
        // 之前一律保持 currentPageIndex，换章后被 clamp 到新章节末页，
        // 表现为「翻来翻去都是同一章」。
        val targetPage = if (resetToFirstPage) {
            0
        } else {
            currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        }
        binding.viewpagerReader.setCurrentItem(targetPage, false)
        currentPageIndex = targetPage
        isNavigatingChapter = false
        applyTheme()
        applyFontSize()
        updateProgressBar()
        updateReaderFooter()
    }

    /**
     * 应用日/夜主题颜色到所有元素
     */
    private fun applyTheme() {
        val adapter = binding.viewpagerReader.adapter as? ReaderPagerAdapter ?: return
        val textColor = if (isNightMode) {
            resources.getColor(R.color.reader_text_night, theme)
        } else {
            resources.getColor(R.color.reader_text, theme)
        }
        val bgColor = if (isNightMode) {
            resources.getColor(R.color.reader_bg_night, theme)
        } else {
            resources.getColor(R.color.reader_bg, theme)
        }
        val topBarBgNight = resources.getColor(R.color.reader_top_bar_bg_night, theme)
        val bottomBarBgNight = resources.getColor(R.color.reader_bottom_bar_bg_night, theme)
        val secondaryColor = if (isNightMode) {
            resources.getColor(R.color.reader_text_secondary_night, theme)
        } else {
            resources.getColor(R.color.reader_text_secondary, theme)
        }
        adapter.updateColors(textColor, bgColor)
        binding.readerContainer.setBackgroundColor(bgColor)
        // 用纯色覆盖会破坏圆角/渐变 drawable，这里只给夜间模式换深色背景资源，
        // 日间保留 XML 里的渐变与圆角卡片
        if (isNightMode) {
            binding.layoutTopBar.setBackgroundColor(topBarBgNight)
            binding.layoutBottomBar.setBackgroundColor(bottomBarBgNight)
        } else {
            binding.layoutTopBar.background = androidx.core.content.ContextCompat
                .getDrawable(this, R.drawable.reader_bar_gradient_top)
            binding.layoutBottomBar.background = androidx.core.content.ContextCompat
                .getDrawable(this, R.drawable.reader_toolbar_background)
        }
        binding.textReaderFooter.setTextColor(secondaryColor)
        binding.textReaderFooter.setBackgroundColor(bgColor)
        binding.textChapterTitle.setTextColor(secondaryColor)
        binding.textChapterTitle.setBackgroundColor(bgColor)
        binding.textPageInfo.setTextColor(secondaryColor)
        if (::catalogAdapter.isInitialized) {
            catalogAdapter.setNightMode(isNightMode)
        }
    }

    /**
     * 应用当前字体大小到页面适配器
     */
    private fun applyFontSize() {
        val adapter = binding.viewpagerReader.adapter as? ReaderPagerAdapter ?: return
        adapter.updateTextSize(spToPx(currentFontSize))
    }

    /**
     * 切换顶部和底部导航栏的显示/隐藏
     */
    private fun toggleControls() {
        if (isControlsVisible) {
            binding.layoutTopBar.visibility = View.GONE
            binding.layoutBottomBar.visibility = View.GONE
            // 章节名/下载提示条是临时状态，隐藏控制栏时一并收起
            binding.textChapterTitle.visibility = View.GONE
        } else {
            binding.layoutTopBar.visibility = View.VISIBLE
            binding.layoutBottomBar.visibility = View.VISIBLE
            restoreChapterTitle()
        }
        isControlsVisible = !isControlsVisible
    }

    /**
     * 切换日/夜间模式
     * 更新状态栏和导航栏颜色、亮度
     */
    private fun toggleNightMode() {
        isNightMode = !isNightMode
        applyTheme()
        val window = window
        if (isNightMode) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            window.statusBarColor = resources.getColor(R.color.reader_top_bar_bg_night, theme)
            window.navigationBarColor = resources.getColor(R.color.reader_bottom_bar_bg_night, theme)
        } else {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            window.statusBarColor = resources.getColor(R.color.reader_top_bar_bg, theme)
            window.navigationBarColor = resources.getColor(R.color.reader_bottom_bar_bg, theme)
        }
    }

    /**
     * 初始化悬浮目录抽屉
     * 配置 RecyclerView、适配器、遮罩和关闭按钮点击事件
     */
    private fun setupCatalogSheet() {
        catalogAdapter = ChapterAdapter { chapter ->
            val index = viewModel.chapterList.value?.indexOf(chapter) ?: -1
            if (index >= 0) {
                hideCatalogSheet()
                viewModel.jumpToChapter(index)
            }
        }

        binding.recyclerCatalog.layoutManager = LinearLayoutManager(this)
        binding.recyclerCatalog.adapter = catalogAdapter

        binding.scrimCatalog.setOnClickListener {
            hideCatalogSheet()
        }

        binding.btnCatalogClose.setOnClickListener {
            hideCatalogSheet()
        }
    }

    /**
     * 显示悬浮目录抽屉
     * 从底部以动画形式滑入，显示所有章节，自动滚动到当前章节
     */
    private fun showCatalogSheet() {
        viewModel.chapterList.value?.let { chapters ->
            if (chapters.isEmpty()) return
            val currentIndex = viewModel.chapterIndex.value ?: 0

            catalogAdapter.submitList(chapters)
            catalogAdapter.setCurrentIndex(currentIndex)
            catalogAdapter.setNightMode(isNightMode)

            val sheetHeight = (resources.displayMetrics.heightPixels * 0.7).toInt()

            binding.scrimCatalog.visibility = View.VISIBLE
            binding.layoutCatalogSheet.visibility = View.VISIBLE

            catalogAnimator?.cancel()
            catalogAnimator = ValueAnimator.ofInt(0, sheetHeight).apply {
                duration = 300
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Int
                    val params = binding.layoutCatalogSheet.layoutParams
                    params.height = value
                    binding.layoutCatalogSheet.layoutParams = params
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isCatalogVisible = true
                    }
                })
                start()
            }

            binding.recyclerCatalog.post {
                (binding.recyclerCatalog.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    /**
     * 隐藏悬浮目录抽屉
     * 以动画形式滑出到底部
     */
    private fun hideCatalogSheet() {
        catalogAnimator?.cancel()
        catalogAnimator = ValueAnimator.ofInt(binding.layoutCatalogSheet.height, 0).apply {
            duration = 250
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                val params = binding.layoutCatalogSheet.layoutParams
                params.height = value
                binding.layoutCatalogSheet.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.scrimCatalog.visibility = View.GONE
                    binding.layoutCatalogSheet.visibility = View.GONE
                    isCatalogVisible = false
                }
            })
            start()
        }
    }

    /**
     * 弹出字体大小设置对话框
     * 通过滑动条调整预览，确认后应用到阅读界面
     */
    private fun showFontSizeDialog() {
        showReaderStatus(getString(R.string.font_size) + ": ${currentFontSize.toInt()}sp")

        // 取消时要把字号还原，避免“拖了滑块又关掉”也生效
        val originalFontSize = currentFontSize

        val dialogView = layoutInflater.inflate(R.layout.dialog_font_size, null)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekbar_font_size)
        val textPreview = dialogView.findViewById<TextView>(R.id.text_preview)

        seekBar.max = 16
        seekBar.progress = (currentFontSize - 12).toInt()
        textPreview.textSize = currentFontSize

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = 12f + progress
                currentFontSize = size
                textPreview.textSize = size
                showReaderStatus(getString(R.string.font_size) + ": ${size.toInt()}sp")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 字号变了必须重新分页：原先只 applyFontSize() 放大字号，
                // 而分页结果是按旧字号切好的，结果文字被撑破页面 / 溢出屏幕
                repaginateWithCurrentFont()
                restoreChapterTitle()
            }
            .setOnCancelListener {
                currentFontSize = originalFontSize
                restoreChapterTitle()
            }
            .show()
    }

    /**
     * 用当前字号重新分页，并尽量停留在同一页。
     */
    private fun repaginateWithCurrentFont() {
        if (!::viewModel.isInitialized) return
        val content = viewModel.chapterContent.value ?: return
        val anchorPage = currentPageIndex
        paginateAndDisplay(content)
        if (pages.size > 1 && anchorPage > 0) {
            val target = anchorPage.coerceAtMost(pages.size - 1)
            binding.viewpagerReader.setCurrentItem(target, false)
            currentPageIndex = target
            updateReaderFooter()
        }
    }

    /**
     * dp 转 px 工具方法
     */
    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    /**
     * sp 转 px。
     *
     * 字号设置里用的是 sp，原先错误地用 dpToPx 换算，忽略了系统字体缩放；
     * 而页面 TextView 又把 px 除以 density 反算成“sp”，实际字号被缩小了约 1/2.75，
     * 表现为“调了字号正文几乎没变化、整体偏小”。
     */
    /**
     * 正文排版：每段首行缩进两个全角空格，段间留一个空行。
     * 站点返回的正文一般是一行一段，这里做统一美化。
     */
    private fun formatParagraphs(raw: String): String {
        if (raw.isBlank()) return raw
        return raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n") { "\u3000\u3000$it" }
    }

    private fun spToPx(sp: Float): Int {
        return (sp * resources.displayMetrics.scaledDensity + 0.5f).toInt()
    }

    /** px 转 sp，供 TextView.textSize 使用 */
    private fun pxToSp(px: Int): Float {
        return px / resources.displayMetrics.scaledDensity
    }

    override fun onBackPressed() {
        if (isCatalogVisible) {
            hideCatalogSheet()
            return
        }
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        if (::viewModel.isInitialized) {
            val bookUrl = viewModel.book.value?.url ?: return
            val chapterIndex = viewModel.chapterIndex.value ?: 0
            lifecycleScope.launch {
                viewModel.repository.updateReadingProgress(bookUrl, chapterIndex, currentPageIndex)
            }
        }
    }

    /**
     * ViewPager2 页面适配器
     * 每个 Item 对应一个分页，展示章节标题、正文内容、页脚时间和电量
     */
    inner class ReaderPagerAdapter(
        private val pageList: List<String>,
        private var chapterTitle: String,
        private val onPageCreated: (TextView) -> Unit
    ) : RecyclerView.Adapter<ReaderPagerAdapter.PageViewHolder>() {

        private var textColor = resources.getColor(R.color.reader_text, theme)
        private var bgColor = resources.getColor(R.color.reader_bg, theme)
        private var textSizePx = spToPx(18f)
        private val titleSizeSp = 13f

        /** 章节名变化时只刷新标题，不用重建整个分页 */
        fun updateChapterTitle(title: String) {
            chapterTitle = title
            notifyDataSetChanged()
        }

        /**
         * 更新文字和背景颜色，通知刷新
         */
        fun updateColors(textColor: Int, bgColor: Int) {
            this.textColor = textColor
            this.bgColor = bgColor
            notifyDataSetChanged()
        }

        /**
         * 更新文字大小，通知刷新
         */
        fun updateTextSize(sizePx: Int) {
            this.textSizePx = sizePx
            notifyDataSetChanged()
        }

        /**
         * 创建页面 ViewHolder
         * 构建标题、正文、页脚三个 TextView
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val container = LinearLayout(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(bgColor)
            }

            val titleView = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // 章节标题：上方留白更大，与正文拉开层次
                setPadding(dpToPx(24f), dpToPx(22f), dpToPx(24f), dpToPx(14f))
                textSize = titleSizeSp
                setTextColor(textColor)
                setLineSpacing(dpToPx(4f).toFloat(), 1f)
                typeface = Typeface.SERIF
                visibility = View.GONE
            }
            container.addView(titleView)

            val contentView = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                // 正文左右留白加大，阅读时视线更集中
                setPadding(dpToPx(24f), 0, dpToPx(24f), dpToPx(20f))
                setTextColor(textColor)
                setBackgroundColor(bgColor)
                textSize = pxToSp(textSizePx)
                setLineSpacing(spToPx(currentFontSize) * (lineSpacing - 1f), 1f)
                typeface = Typeface.SERIF
                gravity = Gravity.TOP or Gravity.START
                onPageCreated(this)
            }
            container.addView(contentView)

            return PageViewHolder(container, titleView, contentView, null)
        }

        /**
         * 绑定页面数据到 ViewHolder
         * 设置标题、正文、页脚（页码 + 时间 + 电量）
         */
        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.titleView.apply {
                if (chapterTitle.isNotEmpty()) {
                    text = chapterTitle
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
                setTextColor(textColor)
            }

            holder.contentView.apply {
                text = pageList[position]
                setTextColor(textColor)
                setBackgroundColor(bgColor)
                textSize = pxToSp(textSizePx)
                setLineSpacing(spToPx(currentFontSize) * (lineSpacing - 1f), 1f)
                typeface = Typeface.SERIF
            }

            holder.container.setBackgroundColor(bgColor)
        }

        /**
         * 返回分页总数
         */
        override fun getItemCount(): Int = pageList.size

        /**
         * 页面 ViewHolder，持有标题、正文、页脚引用
         */
        inner class PageViewHolder(
            val container: LinearLayout,
            val titleView: TextView,
            val contentView: TextView,
            @Suppress("unused") val footerView: TextView?
        ) : RecyclerView.ViewHolder(container)
    }

    /**
     * 覆盖式翻页动效（主流阅读器的滑动翻页）。
     *
     * 原来的 DepthPageTransformer 会把页面缩放成「卡片堆叠」，
     * 阅读时字忽大忽小，观感很怪。这里改成整页水平位移，
     * 当前页在上、下一页从右侧覆盖进来，配合一条细阴影分隔。
     */
    private class DepthPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val width = page.width.toFloat()
            when {
                position < -1f -> {
                    page.alpha = 0f
                    page.translationX = 0f
                }
                position <= 0f -> {
                    // 当前页：保持不动，随手势轻微左移形成视差
                    page.alpha = 1f
                    page.translationX = -width * position * 0.3f
                    page.translationZ = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
                position <= 1f -> {
                    // 下一页：从右侧平移覆盖，不缩放、不变暗
                    page.alpha = 1f
                    page.translationX = width * (1f - position)
                    page.translationZ = 1f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
                else -> {
                    page.alpha = 0f
                    page.translationX = 0f
                }
            }
        }
    }
}