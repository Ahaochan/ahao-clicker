package com.ahao.clicker

import android.app.Application
import com.ahao.clicker.time.TimeSources

class ClickerApp : Application() {
    val timeSources by lazy { TimeSources() }
    val timeSync get() = timeSources.selected
    private val observers = mutableSetOf<Any>()
    private val comparisonObservers = mutableSetOf<Any>()
    private val manualObservers = mutableSetOf<Any>()
    fun observeTime(owner: Any, active: Boolean) {
        if (active) {
            observers.add(owner)
        } else {
            observers.remove(owner)
        }
        refreshTimeSources()
    }
    fun observeAllTimes(owner: Any, active: Boolean) {
        if (active) comparisonObservers.add(owner) else comparisonObservers.remove(owner)
        refreshTimeSources()
    }
    fun refreshTimeSources() {
        timeSources.observe(SettingsStore(this).source(), observers.isNotEmpty(), comparisonObservers.isNotEmpty(), manualObservers.isNotEmpty())
    }
    fun syncAllTimes(owner: Any, intervalMillis: Long) {
        manualObservers.add(owner)
        refreshTimeSources()
        timeSources.syncAll(intervalMillis)
    }
    fun releaseManualTimes(owner: Any) {
        if (manualObservers.remove(owner)) refreshTimeSources()
    }
}
