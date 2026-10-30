package com.ahao.clicker.time

import com.ahao.clicker.core.BuiltinTimeSource
import com.ahao.clicker.core.TimeSourceConfig

/** Owns independent samples. Only the visible comparison page observes all providers. */
class TimeSources {
    private val providers = Array(BuiltinTimeSource.entries.size) { TimeSyncManager() }
    var selectedSource = BuiltinTimeSource.ALIYUN
        private set
    val selected get() = manager(selectedSource)
    fun manager(source: BuiltinTimeSource) = providers[source.ordinal]

    fun observe(config: TimeSourceConfig, selectedVisible: Boolean, allVisible: Boolean) {
        selectedSource = checkNotNull(config.builtin)
        for (source in BuiltinTimeSource.entries) {
            val manager = manager(source)
            if (allVisible || (selectedVisible && source == selectedSource)) {
                manager.configure(TimeSourceConfig.builtin(source, config.intervalMillis), true)
            } else manager.pause()
        }
    }

    fun syncAll(intervalMillis: Long) {
        for (source in BuiltinTimeSource.entries) manager(source).syncNow(TimeSourceConfig.builtin(source, intervalMillis), true)
    }
}
