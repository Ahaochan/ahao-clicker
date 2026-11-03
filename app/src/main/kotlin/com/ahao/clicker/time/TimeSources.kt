package com.ahao.clicker.time

import com.ahao.clicker.core.BuiltinTimeSource
import com.ahao.clicker.core.TimeSourceConfig
import android.os.SystemClock

/** Page entry requests one sample; only an overlay/task owns periodic selected-source demand. */
class TimeSources(create: (BuiltinTimeSource) -> TimeProvider = { source ->
    if (source == BuiltinTimeSource.LOCAL) LocalTimeProvider(SystemClock::elapsedRealtime, System::currentTimeMillis)
    else TimeSyncManager()
}) {
    private val providers = Array<TimeProvider>(BuiltinTimeSource.entries.size) { index ->
        create(BuiltinTimeSource.entries[index])
    }
    var selectedSource = BuiltinTimeSource.ALIYUN
        private set
    val selected get() = manager(selectedSource)
    fun manager(source: BuiltinTimeSource) = providers[source.ordinal]
    private var comparisonVisible = false
    private var overlayVisible = false

    fun observe(config: TimeSourceConfig, selectedVisible: Boolean, allVisible: Boolean, manualVisible: Boolean = false) {
        val entered = allVisible && !comparisonVisible
        comparisonVisible = allVisible
        overlayVisible = selectedVisible
        selectedSource = checkNotNull(config.builtin)
        for (source in BuiltinTimeSource.entries) {
            val manager = manager(source)
            val value = TimeSourceConfig.builtin(source, config.intervalMillis)
            val periodic = selectedVisible && source == selectedSource
            when {
                entered -> manager.syncNow(value, periodic)
                allVisible || periodic || manualVisible -> manager.configure(value, periodic)
                else -> manager.pause()
            }
        }
    }

    fun syncAll(intervalMillis: Long) {
        for (source in BuiltinTimeSource.entries) manager(source).syncNow(
            TimeSourceConfig.builtin(source, intervalMillis), overlayVisible && source == selectedSource)
    }

    fun syncSelected(intervalMillis: Long) {
        selected.syncNow(TimeSourceConfig.builtin(selectedSource, intervalMillis), overlayVisible)
    }
}
