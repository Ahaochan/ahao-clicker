package com.ahao.clicker.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockRefreshTest {
    @Test fun `ordinary and distant tasks use bounded service status checks`() {
        assertEquals(100L, ClockRefresh.delayMillis(true))
        assertEquals(100L, ClockRefresh.delayMillis(true, 5_001))
        assertEquals(100L, ClockRefresh.delayMillis(true, Long.MAX_VALUE))
    }

    @Test fun `independent deadline does not accelerate lifecycle polling in final five seconds`() {
        assertEquals(100L, ClockRefresh.delayMillis(true, 5_000))
        assertEquals(100L, ClockRefresh.delayMillis(true, 1))
        assertEquals(100L, ClockRefresh.delayMillis(true, 0))
        assertEquals(100L, ClockRefresh.delayMillis(true, -1))
        assertEquals(100L, ClockRefresh.delayMillis(true, Long.MIN_VALUE))
    }

    @Test fun `hidden clocks keep only bounded lifecycle checks even near the deadline`() {
        assertEquals(250L, ClockRefresh.delayMillis(false))
        assertEquals(250L, ClockRefresh.delayMillis(false, 1))
        assertEquals(250L, ClockRefresh.delayMillis(false, 5_001))
    }

    @Test fun `one second wait needs only eleven lifecycle observations without delaying dispatch`() {
        data class Entry(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var elapsed = 0L
        var observations = 0
        var dispatchedAt = -1L
        val queue = mutableListOf<Entry>()
        val scheduler = DelayScheduler { delay, action ->
            val entry = Entry(elapsed + delay, action).also(queue::add)
            CancelToken { entry.cancelled = true }
        }
        val runner = OneShotRunner({ elapsed }, scheduler, GestureDriver { _, complete ->
            dispatchedAt = elapsed
            complete(true)
            true
        }, pressMillis = 1)
        runner.arm(TapPoint(10f, 20f), 11_000, TimeReference(10_000, 0, true))
        fun observe() {
            observations++
            scheduler.after(ClockRefresh.delayMillis(true, 1_000 - elapsed), ::observe)
        }
        observe()
        while (true) {
            val next = queue.filter { !it.cancelled && it.at <= 1_000 }.minByOrNull { it.at } ?: break
            queue.remove(next)
            elapsed = next.at
            next.action()
        }
        assertEquals(11, observations) // Includes the initial observation at t=0.
        assertEquals(1_000L, dispatchedAt)
        assertEquals(RunPhase.COMPLETED, runner.status.phase)
    }
}
