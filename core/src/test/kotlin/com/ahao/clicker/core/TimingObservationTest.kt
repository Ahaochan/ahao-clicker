package com.ahao.clicker.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TimingObservationTest {
    @After fun detach() { TimingObservation.observer = null; TimingObservation.failureObserver = null }

    private class Harness {
        data class Work(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        var taps = 0
        val work = mutableListOf<Work>()
        val events = mutableListOf<Int>()
        var complete: ((Boolean) -> Unit)? = null
        val reference = TimeReference(1_000_000, 0, true)
        init { TimingObservation.observer = TimingObserver { code, _, _, _, _ -> events.add(code) } }
        val runner = OneShotRunner({ now }, DelayScheduler { delay, action ->
            val item = Work(now + delay, action).also(work::add)
            CancelToken { item.cancelled = true }
        }, GestureDriver { _, callback -> taps++; complete = callback; true }, preparationMillis = 250, pressMillis = 1)
        fun arm(wait: Long = 1_000) = runner.arm(TapPoint(2f, 3f), reference.epochMillis + wait, reference)
        fun advance(to: Long) {
            while (true) {
                val next = work.filter { !it.cancelled && it.at <= to }.minByOrNull { it.at } ?: break
                work.remove(next); now = next.at; next.action()
            }
            now = to
        }
    }

    @Test fun `measurement separates arm preparation dispatch and real callback without dispatching early`() {
        val h = Harness(); h.arm()
        assertTrue(h.events.contains(TimingObservation.ARM))
        h.advance(750)
        assertTrue(h.events.contains(TimingObservation.PREPARE_END)); assertEquals(0, h.taps)
        h.advance(999); assertEquals(0, h.taps)
        h.advance(1_000); assertEquals(1, h.taps)
        assertTrue(h.events.contains(TimingObservation.DRIVER_RETURN))
        assertFalse(h.events.contains(TimingObservation.DRIVER_CALLBACK))
        h.complete!!(true)
        assertTrue(h.events.contains(TimingObservation.DRIVER_CALLBACK))
        assertEquals(RunPhase.COMPLETED, h.runner.status.phase)
        assertTrue(h.events.indexOf(TimingObservation.POINT_BEGIN) < h.events.indexOf(TimingObservation.POINT_END))
        assertTrue(h.events.indexOf(TimingObservation.DRIVER_BEGIN) < h.events.indexOf(TimingObservation.DRIVER_RETURN))
    }

    @Test fun `old wakeup stays observable but cannot click after rebase or stop`() {
        val h = Harness(); h.arm(60_000)
        val old = h.work.first().action
        h.now = 10_000
        assertTrue(h.runner.rebase(TimeReference(1_010_100, 10_000, true)))
        assertTrue(h.events.contains(TimingObservation.REBASE_ACCEPT))
        h.runner.stop(); h.now = 60_000; old()
        assertEquals(0, h.taps)
        assertTrue(h.events.contains(TimingObservation.STALE_CALLBACK))
    }

    @Test fun `disabled observation has no events while task result is unchanged`() {
        val h = Harness(); TimingObservation.observer = null
        h.arm(); h.advance(1_000); h.complete!!(true)
        assertTrue(h.events.isEmpty()); assertEquals(1, h.taps)
        assertEquals(RunPhase.COMPLETED, h.runner.status.phase)
    }

    @Test fun `preparation failure records its stage and cause without retrying`() {
        val events = mutableListOf<Int>()
        val details = mutableListOf<String>()
        TimingObservation.observer = object : TimingObserver {
            override fun record(event: Int, a: Long, b: Long, c: Long, d: Long) { events.add(event) }
            override fun text(event: Int, value: String) { details.add("$event:$value") }
        }
        val actions = mutableListOf<() -> Unit>()
        var taps = 0
        var now = 0L
        val runner = OneShotRunner({ now }, DelayScheduler { _, action ->
            actions.add(action); CancelToken {}
        }, GestureDriver { _, _ -> taps++; true }, preparationMillis = 250,
            prepare = { throw IllegalStateException("prepare_test_reason") })
        runner.arm(TapPoint(2f, 3f), 2_000, TimeReference(1_000, 0, true))
        now = 750
        actions.first()()
        actions.first()()
        assertEquals(RunPhase.FAILED, runner.status.phase)
        assertEquals(0, taps)
        assertTrue(events.contains(32))
        assertEquals(1, details.size)
        assertTrue(details.single().contains("prepare_test_reason"))
    }

    @Test fun `diagnostic sink failure cannot replace a driver failure or trigger another tap`() {
        TimingObservation.observer = object : TimingObserver {
            override fun record(event: Int, a: Long, b: Long, c: Long, d: Long) = Unit
            override fun text(event: Int, value: String) { error("sink_test_failure") }
        }
        val actions = mutableListOf<() -> Unit>()
        var now = 0L
        var taps = 0
        val runner = OneShotRunner({ now }, DelayScheduler { _, action ->
            actions.add(action); CancelToken {}
        }, GestureDriver { _, _ -> taps++; error("driver_test_failure") })
        runner.arm(TapPoint(2f, 3f), 1_100, TimeReference(1_000, 0, true))
        now = 100
        actions.first()()
        actions.first()()
        assertEquals(1, taps)
        assertEquals(RunPhase.FAILED, runner.status.phase)
        assertNull(TimingObservation.observer)
    }

    @Test fun `failure only observer sees no success events and can be detached`() {
        val events = mutableListOf<Int>()
        val details = mutableListOf<String>()
        TimingObservation.observer = null
        TimingObservation.failureObserver = object : TimingObserver {
            override fun record(event: Int, a: Long, b: Long, c: Long, d: Long) { events.add(event) }
            override fun text(event: Int, value: String) { details.add(value) }
        }
        TimingObservation.emit(TimingObservation.ARM)
        assertTrue(events.isEmpty())
        TimingObservation.failed(32, IllegalStateException("off_mode_failure"))
        TimingObservation.rejected(4)
        assertEquals(listOf(32, 107), events)
        assertEquals(1, details.size)
        assertTrue(details.single().contains("off_mode_failure"))
        TimingObservation.failureObserver = null
        TimingObservation.rejected(5)
        assertEquals(2, events.size)
    }
}
