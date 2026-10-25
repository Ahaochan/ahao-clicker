package com.ahao.clicker

import android.app.Application

class ClickerApp : Application() {
    val timeSync by lazy { TimeSyncManager() }
    override fun onCreate() {
        super.onCreate()
        val store = SettingsStore(this)
        if (store.get("periodic") == "true") runCatching { timeSync.configure(store.source(), true) }
    }
}
