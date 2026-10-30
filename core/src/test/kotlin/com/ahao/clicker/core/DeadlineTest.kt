package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class DeadlineTest {
    private class Runtime(preparationMillis: Long = 100) {
        data class Entry(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var elapsed = 0L
        var prepared = 0
        var taps = 0
        var press = 0L
        val queue = mutableListOf<Entry>()
        val clock = TimeReference(1_000_000, 0, true)
        val runner = OneShotRunner({ elapsed }, DelayScheduler { delay, action ->
            val item = Entry(elapsed + delay, action); queue.add(item)
            CancelToken { item.cancelled = true }
        }, GestureDriver { step, _ -> taps++; press = step.pressMillis; true },
            preparationMillis = preparationMillis, prepare = { prepared++ }, pressMillis = 1)
        fun arm(wait: Long = 60_000) = runner.arm(TapPoint(10f, 20f), clock.epochMillis + wait, clock)
        fun advance(to: Long) {
            while (true) {
                val next = queue.filter { !it.cancelled && it.at <= to }.minByOrNull { it.at } ?: break
                queue.remove(next); elapsed = next.at; next.action()
            }
            elapsed = to
        }
    }

    @Test fun `prepares before deadline and short press still cannot dispatch early`() {
        val env = Runtime(); env.arm(1_000)
        env.advance(899); assertEquals(0, env.prepared)
        env.advance(900); assertEquals(1, env.prepared); assertEquals(0, env.taps)
        val deadline = env.queue.last().action
        env.elapsed = 999; deadline(); assertEquals(0, env.taps)
        env.advance(1_000); deadline()
        assertEquals(1, env.taps); assertEquals(1, env.press)
    }

    @Test fun `larger platform preparation window still dispatches only at absolute deadline`() {
        val env = Runtime(250); env.arm(1_000)
        env.advance(749); assertEquals(0, env.prepared); assertEquals(0, env.taps)
        env.advance(750); assertEquals(1, env.prepared); assertEquals(0, env.taps)
        env.advance(999); assertEquals(0, env.taps)
        env.advance(1_000); assertEquals(1, env.taps)
    }

    @Test fun `cancel during preparation invalidates deadline and repeated early callbacks`() {
        val env = Runtime(); env.arm(1_000)
        val early = env.queue.first().action
        env.elapsed = 100; early(); early()
        assertEquals(0, env.prepared)
        env.advance(900); assertEquals(1, env.prepared)
        val stale = env.queue.last().action
        env.runner.stop(); env.elapsed = 1_000; stale(); early()
        assertEquals(0, env.taps); assertEquals(RunPhase.STOPPED, env.runner.status.phase)
    }

    @Test fun `fresh same-source reference moves a long deadline and invalidates old wakeups`() {
        val env = Runtime(); env.arm()
        val stale = env.queue.first().action
        env.advance(10_000)
        assertTrue(env.runner.rebase(TimeReference(1_010_100, 10_000, true)))
        assertEquals(59_900, env.runner.deadlineElapsed)
        env.elapsed = 59_800; stale(); assertEquals(0, env.prepared)
        env.advance(59_800); assertEquals(1, env.prepared)
        env.advance(59_899); assertEquals(0, env.taps)
        env.advance(59_900); assertEquals(1, env.taps)
    }

    @Test fun `final five seconds freeze while unsynced older and future anchors are ignored`() {
        val env = Runtime(); env.arm()
        env.advance(10_000)
        assertFalse(env.runner.rebase(TimeReference(1_010_100, 10_000, false)))
        assertFalse(env.runner.rebase(TimeReference(1_000_100, 0, true)))
        assertFalse(env.runner.rebase(TimeReference(1_010_100, 10_001, true)))
        env.advance(55_000)
        assertFalse(env.runner.rebase(TimeReference(1_055_500, 55_000, true)))
        assertEquals(60_000, env.runner.deadlineElapsed)
        env.advance(60_000); assertEquals(1, env.taps)
        assertFalse(env.runner.rebase(TimeReference(1_060_500, 60_000, true)))
    }

    @Test fun `large correction or elapsed deadline stops rather than catching up`() {
        val env = Runtime(); env.arm(); env.advance(10_000)
        env.runner.rebase(TimeReference(1_012_000, 10_000, true))
        assertEquals(RunPhase.FAILED, env.runner.status.phase)
        env.advance(65_000); assertEquals(0, env.taps)
        val passed = Runtime(); passed.arm(); passed.advance(10_000)
        passed.runner.rebase(TimeReference(1_070_000, 10_000, true))
        assertEquals(RunPhase.EXPIRED, passed.runner.status.phase)
        passed.advance(65_000); assertEquals(0, passed.taps)
    }

    @Test fun `failed platform preparation consumes no gesture and cannot be retried`() {
        var now = 0L
        val actions = mutableListOf<() -> Unit>()
        var taps = 0
        val runner = OneShotRunner({ now }, DelayScheduler { _, action -> actions.add(action); CancelToken {} },
            GestureDriver { _, _ -> taps++; true }, preparationMillis = 100, prepare = { error("Window unavailable") })
        runner.arm(TapPoint(1f, 1f), 1_001_000, TimeReference(1_000_000, 0, true))
        now = 900; actions.first()(); now = 1_000; actions.toList().forEach { it() }
        assertEquals(0, taps); assertEquals(RunPhase.FAILED, runner.status.phase)
    }

    @Test fun `irregular refreshes for days use anchors rather than accumulating ticks`() {
        val time = SyncedTime()
        val epoch = 1_790_000_000_000L
        assertTrue(time.accept(epoch, 100, 100))
        val anchor = time.snapshot(100, 0)
        for (day in 0..30) {
            val elapsed = 100 + day * 86_400_000L + 871
            assertEquals(epoch + elapsed - 100, anchor.now(elapsed))
            assertEquals(anchor.now(elapsed), time.snapshot(elapsed, -9_999).now(elapsed))
        }
        // A later synchronization removes oscillator error; display frequency never enters the formula.
        val elapsed = 30 * 86_400_000L + 100
        assertTrue(time.accept(epoch + 30 * 86_400_000L - 300, elapsed, elapsed))
        assertEquals(epoch + 30 * 86_400_000L - 300 + 871, time.snapshot(elapsed, 0).now(elapsed + 871))
    }
}
