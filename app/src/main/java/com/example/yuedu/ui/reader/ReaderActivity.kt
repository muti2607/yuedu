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
    private var isControlsVisible = true
    private var pages = listOf<String>()
    private var isNavigatingChapter = false
    private var currentPageIndex = 0
    private var isCatalogVisible = false
    private var catalogAnimator: ValueAnimator? = null
    private lateinit var catalogAdapter: ChapterAdapter

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
        val chapterIndex = intent.getIntExtra("chapter_index", 0)

        viewModel = ReaderViewModel(repository, bookUrl, chapterIndex)

        setupViewPager()
        setupControls()
        setupCatalogSheet()
        observeData()
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
        viewModel.chapterContent.observe(this) { content ->
            binding.progressLoading.visibility = View.GONE
            binding.viewpagerReader.post {
                paginateAndDisplay(content)
            }
        }

        viewModel.chapterTitle.observe(this) { title ->
            binding.textChapterTitle.text = title
        }

        viewModel.isLoading.observe(this) { loading ->
            if (loading) {
                binding.progressLoading.visibility = View.VISIBLE
            }
        }

        viewModel.chapterIndex.observe(this) { index ->
            viewModel.book.value?.let { book ->
                lifecycleScope.launch {
                    viewModel.repository.updateReadingProgress(book.url, index, 0)
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
                binding.textChapterTitle.visibility = View.VISIBLE
                binding.textChapterTitle.text = "下载中: $progress"
            }
        }

        viewModel.isDownloading.observe(this) { downloading ->
            if (!downloading && viewModel.downloadProgress.value?.isNotEmpty() == true) {
                binding.textChapterTitle.postDelayed({
                    binding.textChapterTitle.visibility = View.GONE
                }, 2000)
            }
        }
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

    /**
     * 将章节内容分页并显示到 ViewPager
     * 使用 StaticLayout 计算每行文字位置，根据可用高度切割内容
     * 减去半行高度让内容底部向上提半行，避免文字紧贴底部
     */
    private fun paginateAndDisplay(content: String) {
        val paint = TextPaint()
        paint.textSize = dpToPx(currentFontSize).toFloat()
        paint.isAntiAlias = true
        paint.typeface = Typeface.SERIF

        val pageWidth = binding.viewpagerReader.width - dpToPx(20f) * 2
        val fullPageHeight = binding.viewpagerReader.height
        val titleHeight = dpToPx(36f)
        val footerHeight = dpToPx(26f)

        if (pageWidth <= 0 || fullPageHeight <= 0) {
            pages = listOf(content)
            displayPages()
            return
        }

        fun buildLayout(text: String, height: Int): StaticLayout {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, pageWidth)
                .setLineSpacing(dpToPx(1.8f * currentFontSize - currentFontSize).toFloat(), 1f)
                .setMaxLines(Int.MAX_VALUE)
                .build()
        }

        val pageList = mutableListOf<String>()
        var offset = 0
        val textLength = content.length

        while (offset < textLength) {
            val halfLineHeight = dpToPx(currentFontSize * 0.5f)
            val usableHeight = fullPageHeight - titleHeight - footerHeight - halfLineHeight
            val layout = buildLayout(content.substring(offset), usableHeight)
            val totalLines = layout.lineCount
            if (totalLines == 0) break

            var endLine = 0
            while (endLine < totalLines && layout.getLineBottom(endLine) <= usableHeight) {
                endLine++
            }
            if (endLine == 0) endLine = 1

            val endOffset = offset + layout.getLineStart(endLine)
            val pageText = content.substring(offset, minOf(endOffset, textLength)).trimEnd()
            pageList.add(pageText)
            offset = minOf(endOffset, textLength)
        }

        if (pageList.isEmpty()) {
            pageList.add(content)
        }

        pages = pageList
        currentPageIndex = 0
        displayPages()
    }

    /**
     * 将分好页的内容更新到适配器并显示
     * 同时应用主题和字体大小
     */
    private fun displayPages() {
        isNavigatingChapter = true
        val chapterTitle = viewModel.chapterTitle.value ?: ""
        val adapter = ReaderPagerAdapter(pages, chapterTitle) { pageView ->
            // Click handling is done in dispatchTouchEvent
        }
        binding.viewpagerReader.adapter = adapter
        binding.viewpagerReader.setCurrentItem(0, false)
        isNavigatingChapter = false
        applyTheme()
        applyFontSize()
        updateProgressBar()
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
        val topBarBg = if (isNightMode) {
            resources.getColor(R.color.reader_top_bar_bg_night, theme)
        } else {
            resources.getColor(R.color.reader_top_bar_bg, theme)
        }
        val bottomBarBg = if (isNightMode) {
            resources.getColor(R.color.reader_bottom_bar_bg_night, theme)
        } else {
            resources.getColor(R.color.reader_bottom_bar_bg, theme)
        }
        adapter.updateColors(textColor, bgColor)
        binding.readerContainer.setBackgroundColor(bgColor)
        binding.layoutTopBar.setBackgroundColor(topBarBg)
        binding.layoutBottomBar.setBackgroundColor(bottomBarBg)
        if (::catalogAdapter.isInitialized) {
            catalogAdapter.setNightMode(isNightMode)
        }
    }

    /**
     * 应用当前字体大小到页面适配器
     */
    private fun applyFontSize() {
        val adapter = binding.viewpagerReader.adapter as? ReaderPagerAdapter ?: return
        adapter.updateTextSize(dpToPx(currentFontSize))
    }

    /**
     * 切换顶部和底部导航栏的显示/隐藏
     */
    private fun toggleControls() {
        if (isControlsVisible) {
            binding.layoutTopBar.visibility = View.GONE
            binding.layoutBottomBar.visibility = View.GONE
            binding.textChapterTitle.visibility = View.GONE
        } else {
            binding.layoutTopBar.visibility = View.VISIBLE
            binding.layoutBottomBar.visibility = View.VISIBLE
            binding.textChapterTitle.visibility = View.VISIBLE
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
        binding.textChapterTitle.visibility = View.VISIBLE
        binding.textChapterTitle.text = getString(R.string.font_size) + ": ${currentFontSize.toInt()}sp"

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
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyFontSize()
                binding.textChapterTitle.visibility = View.GONE
            }
            .setOnCancelListener {
                binding.textChapterTitle.visibility = View.GONE
            }
            .show()
    }

    /**
     * dp 转 px 工具方法
     */
    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onBackPressed() {
        if (isCatalogVisible) {
            hideCatalogSheet()
            return
        }
        super.onBackPressed()
    }

    /**
     * ViewPager2 页面适配器
     * 每个 Item 对应一个分页，展示章节标题、正文内容、页脚时间和电量
     */
    inner class ReaderPagerAdapter(
        private val pageList: List<String>,
        private val chapterTitle: String,
        private val onPageCreated: (TextView) -> Unit
    ) : RecyclerView.Adapter<ReaderPagerAdapter.PageViewHolder>() {

        private var textColor = resources.getColor(R.color.reader_text, theme)
        private var bgColor = resources.getColor(R.color.reader_bg, theme)
        private var textSizePx = dpToPx(18f)
        private val titleSizeSp = 14f
        private val footerSizeSp = 10f

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
         * 获取当前电池电量百分比
         */
        private fun getBatteryLevel(): Int {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            return if (scale > 0) (level * 100 / scale) else 100
        }

        /**
         * 获取当前时间 HH:mm 格式
         */
        private fun getCurrentTime(): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
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
                setPadding(dpToPx(20f), dpToPx(12f), dpToPx(20f), dpToPx(4f))
                textSize = titleSizeSp
                setTextColor(textColor)
                typeface = Typeface.DEFAULT_BOLD
                visibility = View.GONE
            }
            container.addView(titleView)

            val contentView = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                setPadding(dpToPx(20f), 0, dpToPx(20f), 0)
                setTextColor(textColor)
                setBackgroundColor(bgColor)
                textSize = textSizePx.toFloat() / resources.displayMetrics.density
                setLineSpacing(dpToPx(1.8f * currentFontSize - currentFontSize).toFloat(), 1f)
                typeface = Typeface.SERIF
                gravity = Gravity.TOP or Gravity.START
                onPageCreated(this)
            }
            container.addView(contentView)

            val footerView = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(dpToPx(20f), dpToPx(4f), dpToPx(20f), dpToPx(8f))
                textSize = footerSizeSp
                setTextColor(textColor)
                gravity = Gravity.CENTER
            }
            container.addView(footerView)

            return PageViewHolder(container, titleView, contentView, footerView)
        }

        /**
         * 绑定页面数据到 ViewHolder
         * 设置标题、正文、页脚（页码 + 时间 + 电量）
         */
        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val timeStr = getCurrentTime()
            val batteryLevel = getBatteryLevel()
            val footerText = "${position + 1}/$itemCount  $timeStr  \uD83D\uDD0B$batteryLevel%"

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
                textSize = textSizePx.toFloat() / resources.displayMetrics.density
                setLineSpacing(dpToPx(1.8f * currentFontSize - currentFontSize).toFloat(), 1f)
                typeface = Typeface.SERIF
            }

            holder.footerView.apply {
                text = footerText
                setTextColor(textColor)
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
            val footerView: TextView
        ) : RecyclerView.ViewHolder(container)
    }

    /**
     * ViewPager2 深度页面切换动效
     * 实现卡片翻页时的缩放和透明度渐变效果
     */
    private class DepthPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val pageWidth = page.width
            when {
                position < -1 -> {
                    page.alpha = 0f
                }
                position <= 0 -> {
                    page.alpha = 1f
                    page.translationX = 0f
                    page.translationZ = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
                position <= 1 -> {
                    page.alpha = 1 - position
                    page.translationX = pageWidth * -position
                    page.translationZ = -1f
                    val scaleFactor = 0.85f + (1 - position) * 0.15f
                    page.scaleX = scaleFactor
                    page.scaleY = scaleFactor
                }
                else -> {
                    page.alpha = 0f
                }
            }
        }
    }
}