package com.example.yuedu.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量键值存储。
 *
 * 只用来记“目录上次抓取时间”这类小状态，
 * 不值得为它建一张 Room 表。
 */
object YueduPrefs {

    private const val FILE = "yuedu_prefs"

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    fun getLong(key: String, default: Long): Long = sp?.getLong(key, default) ?: default

    fun putLong(key: String, value: Long) {
        sp?.edit()?.putLong(key, value)?.apply()
    }

    fun getInt(key: String, default: Int): Int = sp?.getInt(key, default) ?: default

    fun putInt(key: String, value: Int) {
        sp?.edit()?.putInt(key, value)?.apply()
    }
}
