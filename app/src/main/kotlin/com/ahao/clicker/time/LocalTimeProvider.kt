package com.ahao.clicker.time

import com.ahao.clicker.core.TimeReference
import com.ahao.clicker.core.TimeSourceConfig
import com.ahao.clicker.core.BuiltinTimeSource

internal class LocalTimeProvider(private val elapsed: () -> Long, private val deviceEpoch: () -> Long) : TimeProvider {
    override val state = SyncState()
    private var cached: TimeReference? = null
    @Synchronized override fun reference(): TimeReference {
        val at = elapsed(); val epoch = deviceEpoch()
        // Reuse a steady reference so status polls do not continually requeue a waiting task.
        val previous = cached
        if (previous != null && at >= previous.elapsedMillis && previous.now(at) - epoch in -1L..1L) return previous
        return TimeReference(epoch, at, true).also { cached = it }
    }
    override fun configure(value: TimeSourceConfig, periodic: Boolean) { require(value.builtin == BuiltinTimeSource.LOCAL) }
    override fun pause() = Unit
    override fun syncNow(value: TimeSourceConfig, periodic: Boolean) = configure(value, periodic)
    override fun hasFreshSample() = true
}
