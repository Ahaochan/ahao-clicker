package com.ahao.clicker.time

import com.ahao.clicker.core.BuiltinTimeSource
import com.ahao.clicker.core.TimeReference
import com.ahao.clicker.core.TimeSourceConfig
import org.junit.Assert.*
import org.junit.Test

class TimeSourcesTest {
    private class Provider : TimeProvider {
        override val state = SyncState()
        var requests = 0
        var periodic = false
        var paused = true
        var interval = 0L
        override fun reference() = TimeReference(0, 0, false)
        override fun hasFreshSample() = false
        override fun configure(value: TimeSourceConfig, periodic: Boolean) {
            this.periodic = periodic; interval = value.intervalMillis; paused = false
        }
        override fun syncNow(value: TimeSourceConfig, periodic: Boolean) { requests++; configure(value, periodic) }
        override fun pause() { periodic = false; paused = true }
    }
    private val providers = BuiltinTimeSource.entries.associateWith { Provider() }
    private val sources = TimeSources { providers.getValue(it) }
    private fun observe(overlay: Boolean, page: Boolean, source: BuiltinTimeSource = BuiltinTimeSource.JD, interval: Long = 1000) =
        sources.observe(TimeSourceConfig.builtin(source, interval), overlay, page)

    @Test fun `entering page initializes once and staying does not create periodic requests`() {
        observe(false, true)
        assertTrue(providers.values.all { it.requests == 1 && !it.periodic && !it.paused })
        repeat(3) { observe(false, true) }
        assertTrue(providers.values.all { it.requests == 1 })
        observe(false, false)
        assertTrue(providers.values.all { it.paused })
        observe(false, true)
        assertTrue(providers.values.all { it.requests == 2 && !it.periodic })
    }
    @Test fun `only overlay selected source is periodic even while comparison page is visible`() {
        observe(true, true)
        assertEquals(setOf(BuiltinTimeSource.JD), providers.filterValues { it.periodic }.keys)
        observe(true, true, BuiltinTimeSource.TAOBAO, 5000)
        assertEquals(setOf(BuiltinTimeSource.TAOBAO), providers.filterValues { it.periodic }.keys)
        assertTrue(providers.values.all { it.requests == 1 && it.interval == 5000L })
        observe(true, false, BuiltinTimeSource.TAOBAO, 5000)
        assertTrue(providers.filterKeys { it != BuiltinTimeSource.TAOBAO }.values.all { it.paused })
        assertTrue(providers.getValue(BuiltinTimeSource.TAOBAO).periodic)
        observe(false, false)
        assertTrue(providers.values.all { it.paused })
    }
    @Test fun `manual all sync preserves only the overlay subscription`() {
        observe(true, true)
        sources.syncAll(2000)
        assertTrue(providers.values.all { it.requests == 2 && it.interval == 2000L })
        assertEquals(setOf(BuiltinTimeSource.JD), providers.filterValues { it.periodic }.keys)
        observe(false, true)
        assertTrue(providers.values.none { it.periodic || it.paused })
    }
    @Test fun `homepage without overlay never synchronizes`() {
        observe(false, false)
        assertTrue(providers.values.all { it.requests == 0 && it.paused })
        observe(true, false, BuiltinTimeSource.LOCAL)
        assertEquals(setOf(BuiltinTimeSource.LOCAL), providers.filterValues { it.periodic }.keys)
        assertTrue(providers.values.all { it.requests == 0 })
    }
}
