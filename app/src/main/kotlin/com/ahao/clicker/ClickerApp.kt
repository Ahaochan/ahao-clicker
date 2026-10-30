package com.ahao.clicker

import android.app.Application
import com.ahao.clicker.time.TimeSources

class ClickerApp : Application() {
    val timeSources by lazy { TimeSources() }
    val timeSync get() = timeSources.selected
    private val observers = mutableSetOf<Any>()
    private val comparisonObservers = mutableSetOf<Any>()
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
        timeSources.observe(SettingsStore(this).source(), observers.isNotEmpty(), comparisonObservers.isNotEmpty())
    }
}
