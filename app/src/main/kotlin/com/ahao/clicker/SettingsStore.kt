package com.ahao.clicker

import android.content.Context
import androidx.core.content.edit
import com.ahao.clicker.core.TimeSourceConfig
import com.ahao.clicker.core.BuiltinTimeSource
import com.ahao.clicker.core.TriggerTimeDefaults
import java.time.ZoneId

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("clicker", Context.MODE_PRIVATE)
    fun get(key: String, fallback: String = ""): String = prefs.getString(key, fallback) ?: fallback
    internal fun overlaySize(kind: OverlayPanelKind): OverlaySize? = OverlaySize.decode(get(kind.sizeKey))
    internal fun saveOverlaySize(kind: OverlayPanelKind, size: OverlaySize) = save(mapOf(kind.sizeKey to size.encode()))
    fun timedDefault(now: Long): Long = TriggerTimeDefaults.seed(now, get("triggerMinuteMillis"), ZoneId.systemDefault())
    fun saveTimedTarget(target: Long) = save(mapOf("target" to target.toString(),
        "triggerMinuteMillis" to TriggerTimeDefaults.remember(target, ZoneId.systemDefault())))
    fun source() = TimeSourceConfig.builtin(
        BuiltinTimeSource.entries.firstOrNull { it.name == get("source") } ?: BuiltinTimeSource.ALIYUN,
        get("syncInterval", "60000").toLongOrNull()?.takeIf { it in 1_000..86_400_000 } ?: 60_000,
    )
    fun save(values: Map<String, String>) {
        prefs.edit { values.forEach { (key, value) -> putString(key, value.take(64_000)) } }
    }
}
