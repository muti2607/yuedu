package com.example.yuedu.ui.notifications

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.yuedu.data.network.site.BookSites
import com.example.yuedu.data.network.site.SiteSettings
import com.example.yuedu.databinding.FragmentNotificationsBinding
import com.example.yuedu.ui.settings.SiteSettingsActivity

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardSources.setOnClickListener {
            startActivity(Intent(requireContext(), SiteSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateSummary()
    }

    /** 书源入口卡片的一句话摘要：可用数量 + 首选源 */
    private fun updateSummary() {
        val b = _binding ?: return
        val enabled = BookSites.all.count { SiteSettings.isEnabled(it.id) }
        val primaryId = SiteSettings.primaryId()
        val primaryName = BookSites.all.firstOrNull { it.id == primaryId }?.name
        b.textSourcesSummary.text = if (primaryName != null) {
            "$enabled 个可用 · 首选 $primaryName"
        } else {
            "$enabled 个可用 · 自动选择"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
