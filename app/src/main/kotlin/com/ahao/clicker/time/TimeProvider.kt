package com.ahao.clicker.time

import com.ahao.clicker.core.TimeReference
import com.ahao.clicker.core.TimeSourceConfig

/** A usable reference may come from the device clock or an external sample. */
interface TimeProvider {
    val state: SyncState
    fun reference(): TimeReference
    fun configure(value: TimeSourceConfig, periodic: Boolean)
    fun pause()
    fun syncNow(value: TimeSourceConfig, periodic: Boolean)
    fun hasFreshSample(): Boolean
}
