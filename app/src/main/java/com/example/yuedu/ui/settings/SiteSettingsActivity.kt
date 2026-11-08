package com.example.yuedu.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.yuedu.R
import com.example.yuedu.data.network.site.BookSite
import com.example.yuedu.data.network.site.BookSites
import com.example.yuedu.data.network.site.SiteSettings
import com.example.yuedu.databinding.ActivitySiteSettingsBinding
import kotlinx.coroutines.launch

/**
 * 书源管理页。
 *
 * - 列出全部书源及实测速度
 * - 点击某一行 → 设为首选源（搜索时优先用它）
 * - 开关 → 停用后不参与搜索
 * - 「测速」→ 并发探测各源往返耗时
 */
class SiteSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySiteSettingsBinding
    private var measuring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySiteSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMeasure.setOnClickListener { measure() }

        renderList()
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        val container = binding.listSources
        container.removeAllViews()
        val primary = SiteSettings.primaryId()

        BookSites.all.forEach { site ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_site_source, container, false)

            val nameView = row.findViewById<TextView>(R.id.text_site_name)
            val hostView = row.findViewById<TextView>(R.id.text_host)
            val latencyView = row.findViewById<TextView>(R.id.text_latency)
            val starView = row.findViewById<TextView>(R.id.text_star)
            val switch = row.findViewById<SwitchCompat>(R.id.switch_enabled)

            nameView.text = site.name
            hostView.text = site.hostText()
            starView.text = if (primary == site.id) "★" else ""
            applyLatency(latencyView, SiteSettings.latencyMs(site.id))

            // 顺序很重要：先设状态，再挂监听，避免初始化时把默认值写坏
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = SiteSettings.isEnabled(site.id)
            switch.setOnCheckedChangeListener { _, checked ->
                SiteSettings.setEnabled(site.id, checked)
                // 停用了当前首选源 → 清掉首选，回退自动
                if (!checked && SiteSettings.primaryId() == site.id) {
                    SiteSettings.setPrimaryId("")
                }
            }

            // 点开关不应同时触发「设为首选」，这里拦截点击不让它冒泡到行
            switch.setOnClickListener { /* 消费掉，由上面的 listener 处理 */ }

            // 点击行的其它区域 → 设为首选
            row.setOnClickListener {
                SiteSettings.setPrimaryId(site.id)
                renderList()
            }

            container.addView(row)
        }

        // 最近测速时间
        val lastTime = BookSites.all.maxOfOrNull { SiteSettings.latencyTime(it.id) } ?: 0L
        binding.textMeasuredAt.text = if (lastTime > 0) {
            "上次测速：${formatTime(lastTime)}"
        } else {
            "尚未测速，点右上角「测速」"
        }

        binding.latencyLocal.text = "0 ms"
    }

    private fun BookSite.hostText(): String = id

    private fun applyLatency(view: TextView, ms: Long) {
        when {
            ms == -1L -> {
                view.text = "超时·已跳过"
                view.setTextColor(getColor(R.color.text_secondary))
                view.setBackgroundResource(0)
            }
            ms < 0 -> {
                view.text = "未测速"
                view.setTextColor(getColor(R.color.text_secondary))
                view.setBackgroundResource(0)
            }
            else -> {
                view.text = "$ms ms"
                view.setTextColor(getColor(R.color.orange_500))
                view.setBackgroundResource(R.drawable.bg_latency_fast)
            }
        }
    }

    private fun measure() {
        if (measuring) return
        measuring = true
        binding.btnMeasure.text = "测速中…"
        binding.btnMeasure.isEnabled = false

        lifecycleScope.launch {
            try {
                SiteSettings.measureAll()
            } catch (_: Exception) {
                // 单源失败已在其内部处理，这里兜底
            } finally {
                measuring = false
                binding.btnMeasure.text = "测速"
                binding.btnMeasure.isEnabled = true
                renderList()
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }
}
