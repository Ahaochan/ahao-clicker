package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class AutomationTest {
    @Test fun `time source rejects unsafe URLs and invalid intervals`() {
        assertThrows(IllegalArgumentException::class.java) { TimeSourceConfig("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { TimeSourceConfig("https://example.com", intervalMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { TimeSourceConfig("https://user:pass@example.com") }
    }

    @Test fun `clock compensates half RTT and ignores failed samples`() {
        val clock = SyncedTime()
        assertTrue(clock.accept(1_000_000, 100, 300))
        assertEquals(1_000_100, clock.snapshot(300, 0).now(300))
        assertFalse(clock.accept(9_000_000, 300, 10_000))
        assertEquals(1_000_200, clock.snapshot(400, 0).now(400))
        assertFalse(clock.accept(-1, 400, 401))
        assertFalse(clock.accept(Long.MAX_VALUE, 400, 401))
    }

    @Test fun `snapshot is monotonic and frozen across future synchronizations`() {
        val clock = SyncedTime()
        val fallback = clock.snapshot(100, 5_000)
        assertEquals(5_100, fallback.now(200))
        clock.accept(10_000, 100, 100)
        val frozen = clock.snapshot(100, 0)
        clock.accept(20_000, 200, 200)
        assertEquals(10_200, frozen.now(300))
        assertEquals(20_100, clock.snapshot(300, 0).now(300))
        clock.reset()
        assertFalse(clock.snapshot(300, 5_000).synced)
        assertEquals(10_200, frozen.now(300))
    }

    @Test fun `starts at boundary and stops exactly at count without expanding repetitions`() {
        val env = FakeRuntime()
        val runner = env.runner()
        runner.start(plan(count = Int.MAX_VALUE, start = 100, end = 500))
        assertEquals(0, env.taps.size)
        env.advance(99)
        assertEquals(0, env.taps.size)
        env.advance(100)
        assertEquals(1, env.taps.size)
        runner.stop()
        runner.start(plan(count = 2, start = 100, end = 500))
        env.complete()
        env.advance(109)
        assertEquals(2, env.taps.size)
        env.advance(110)
        env.complete()
        assertEquals(RunPhase.COMPLETED, runner.status.phase)
        assertEquals(2, runner.status.completed)
        env.advance(500)
        assertEquals(3, env.taps.size)
    }

    @Test fun `deadline prevents overlapping or late taps and ignores late callbacks`() {
        val env = FakeRuntime()
        val runner = env.runner()
        runner.start(plan(count = 5, end = 30))
        val oldCallback = env.callback!!
        env.advance(30)
        assertEquals(RunPhase.EXPIRED, runner.status.phase)
        oldCallback(true)
        assertEquals(0, runner.status.completed)
        assertEquals(1, env.taps.size)
        assertTrue(env.maxPending <= 2)
    }

    @Test fun `gesture that cannot finish before deadline is not dispatched`() {
        val env = FakeRuntime()
        val runner = env.runner()
        runner.start(plan(end = 4))
        assertEquals(0, env.taps.size)
        assertEquals(RunPhase.EXPIRED, runner.status.phase)
    }

    @Test fun `multipoint uses each next step interval and original order`() {
        val env = FakeRuntime()
        val runner = env.runner()
        runner.start(ClickPlan(listOf(ClickStep(TapPoint(1f, 1f), 7, 5), ClickStep(TapPoint(2f, 2f), 20, 5)), 3, 0, 1_000))
        env.complete()
        env.advance(19)
        assertEquals(1, env.taps.size)
        env.advance(20)
        env.complete()
        env.advance(27)
        assertEquals(listOf(1f, 2f, 1f), env.taps.map { it.point.x })
        env.complete()
        assertEquals(RunPhase.COMPLETED, runner.status.phase)
    }

    @Test fun `reject cancellation timeout and stop all prevent additional dispatch`() {
        for (mode in 0..3) {
            val env = FakeRuntime()
            env.accept = mode != 0
            val runner = env.runner()
            runner.start(plan(end = 10_000))
            when (mode) {
                1 -> env.complete(false)
                2 -> env.advance(2_005)
                3 -> { val late = env.callback!!; runner.stop(); late(true) }
            }
            env.advance(20_000)
            assertEquals(1, env.taps.size)
            assertEquals(if (mode == 3) RunPhase.STOPPED else RunPhase.FAILED, runner.status.phase)
        }
    }

    @Test fun `stop before scheduled start invalidates all callbacks`() {
        val env = FakeRuntime()
        val runner = env.runner()
        runner.start(plan(start = 100))
        runner.stop()
        env.advance(200)
        assertTrue(env.taps.isEmpty())
    }

    @Test fun `plan copies steps and validates bounds`() {
        val input = mutableListOf(ClickStep(TapPoint(1f, 2f)))
        val plan = ClickPlan(input, 1, 0, 10)
        input.clear()
        assertEquals(1, plan.steps.size)
        assertThrows(IllegalArgumentException::class.java) { ClickPlan(emptyList(), 1, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { ClickPlan(plan.steps, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { ClickPlan(plan.steps, 1, 2, 1) }
    }

    @Test fun `recorder captures actual completion gaps and bounds memory`() {
        val recorder = PointRecorder(2)
        recorder.record(TapPoint(1f, 1f), 100)
        assertTrue(recorder.completedSteps.isEmpty())
        assertThrows(IllegalStateException::class.java) { recorder.record(TapPoint(2f, 2f), 110) }
        recorder.completed(150)
        assertEquals(1, recorder.completedSteps.size)
        recorder.record(TapPoint(2f, 2f), 250)
        recorder.completed(300)
        assertEquals(listOf(0L, 100L), recorder.steps.map { it.waitBeforeMillis })
        assertThrows(IllegalArgumentException::class.java) { recorder.record(TapPoint(3f, 3f), 400) }
        recorder.clear()
        assertTrue(recorder.steps.isEmpty())
    }

    private fun plan(count: Int = 10, start: Long = 0, end: Long = 1_000) =
        ClickPlan(listOf(ClickStep(TapPoint(10f, 20f), 10, 5)), count, start, end)

    private class FakeRuntime : DelayScheduler, GestureDriver {
        data class Event(val at: Long, val order: Int, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        var sequence = 0
        var maxPending = 0
        var accept = true
        var callback: ((Boolean) -> Unit)? = null
        val taps = mutableListOf<ClickStep>()
        val events = PriorityQueue<Event>(compareBy<Event> { it.at }.thenBy { it.order })
        override fun after(delayMillis: Long, action: () -> Unit): CancelToken {
            val event = Event(now + delayMillis, sequence++, action)
            events.add(event)
            maxPending = maxOf(maxPending, events.count { !it.cancelled })
            return CancelToken { event.cancelled = true }
        }
        override fun tap(step: ClickStep, result: (Boolean) -> Unit): Boolean {
            taps.add(step)
            callback = result
            return accept
        }
        fun complete(success: Boolean = true) { callback!!.invoke(success) }
        fun runner() = ClickRunner({ now }, this, this)
        fun advance(until: Long) {
            while (events.isNotEmpty() && events.peek().at <= until) {
                val event = events.remove()
                now = event.at
                if (!event.cancelled) event.action()
            }
            now = until
        }
    }
}
