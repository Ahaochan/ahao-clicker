package com.ahao.clicker.time

import com.ahao.clicker.core.BuiltinTimeSource
import com.ahao.clicker.core.TimeSourceConfig

internal enum class TimeSettingsResult { SAVED, ACTIVE_TASK, INVALID_INTERVAL }

internal class TimeSettingsController(private val active: () -> Boolean,
    private val save: (Map<String, String>) -> Unit, private val apply: () -> Unit,
    private val syncAll: (Long) -> Unit, private val syncSelected: () -> Unit) {
    fun select(source: BuiltinTimeSource): Boolean {
        if (active()) return false
        save(mapOf("source" to source.name))
        apply()
        syncSelected()
        return true
    }
    fun saveAndSync(text: String): TimeSettingsResult {
        if (active()) return TimeSettingsResult.ACTIVE_TASK
        val interval = text.toLongOrNull()?.takeIf(TimeSourceConfig::validInterval)
            ?: return TimeSettingsResult.INVALID_INTERVAL
        save(mapOf("syncInterval" to interval.toString()))
        syncAll(interval)
        return TimeSettingsResult.SAVED
    }
}
