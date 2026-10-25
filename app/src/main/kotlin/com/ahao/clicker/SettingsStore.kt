package com.ahao.clicker

import android.content.Context
import androidx.core.content.edit
import com.ahao.clicker.core.TimeFormat
import com.ahao.clicker.core.TimeSourceConfig

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("clicker", Context.MODE_PRIVATE)
    fun get(key: String, fallback: String = ""): String = prefs.getString(key, fallback) ?: fallback
    fun source() = TimeSourceConfig(get("url"), TimeFormat.valueOf(get("format", "HTTP_DATE")), get("field", "timestamp"), get("interval", "60000").toLong())
    fun save(values: Map<String, String>) {
        prefs.edit { values.forEach { (key, value) -> putString(key, value.take(64_000)) } }
    }
}
