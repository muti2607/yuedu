package com.example.yuedu.ui.reader

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
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.yuedu.YueduApplication
import com.example.yuedu.R
import com.example.yuedu.databinding.ActivityReaderBinding
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel
    private var isNightMode = false
    private var currentFontSize = 18f
    private var isControlsVisible = true
    private var pages = listOf<String>()
    private var isNavigatingChapter = false
    private var currentPageIndex = 0

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
        observeData()
    }

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var swipeThreshold = 80f

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
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

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
            showChapterListDialog()
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
            val usableHeight = fullPageHeight - titleHeight - footerHeight
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

    private fun displayPages() {
        isNavigatingChapter = true
        val chapterTitle = viewModel.chapterTitle.value ?: ""
        val adapter = ReaderPagerAdapter(pages, chapterTitle) { pageView ->
            pageView.setOnClickListener {
                toggleControls()
            }
        }
        binding.viewpagerReader.adapter = adapter
        binding.viewpagerReader.setCurrentItem(0, false)
        isNavigatingChapter = false
        applyTheme()
        applyFontSize()
        updateProgressBar()
    }

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
    }

    private fun applyFontSize() {
        val adapter = binding.viewpagerReader.adapter as? ReaderPagerAdapter ?: return
        adapter.updateTextSize(dpToPx(currentFontSize))
    }

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

    private fun showChapterListDialog() {
        viewModel.chapterList.value?.let { chapters ->
            val currentIndex = viewModel.chapterIndex.value ?: 0
            val adapter = object : ArrayAdapter<String>(this, R.layout.item_chapter, chapters.map { it.title }) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val titleView = view.findViewById<TextView>(R.id.text_chapter_title)
                    val badgeView = view.findViewById<TextView>(R.id.text_latest_badge)

                    titleView.setTextColor(if (position == currentIndex)
                        resources.getColor(android.R.color.holo_blue_dark, theme)
                    else
                        resources.getColor(android.R.color.black, theme))

                    badgeView.visibility = if (position == chapters.size - 1) View.VISIBLE else View.GONE
                    return view
                }
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.chapter_list)
                .setAdapter(adapter) { _, which ->
                    viewModel.jumpToChapter(which)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

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

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

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

        fun updateColors(textColor: Int, bgColor: Int) {
            this.textColor = textColor
            this.bgColor = bgColor
            notifyDataSetChanged()
        }

        fun updateTextSize(sizePx: Int) {
            this.textSizePx = sizePx
            notifyDataSetChanged()
        }

        private fun getBatteryLevel(): Int {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            return if (scale > 0) (level * 100 / scale) else 100
        }

        private fun getCurrentTime(): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }

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

        override fun getItemCount(): Int = pageList.size

        inner class PageViewHolder(
            val container: LinearLayout,
            val titleView: TextView,
            val contentView: TextView,
            val footerView: TextView
        ) : RecyclerView.ViewHolder(container)
    }

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