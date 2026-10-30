package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class MultiPointRunnerTest {
    private class Runtime {
        data class Entry(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var time = 0L
        var accepted = true
        var throws = false
        var completeImmediately = false
        val queue = mutableListOf<Entry>()
        val calls = mutableListOf<Pair<Long, ClickStep>>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val runner = MultiPointRunner({ time }, DelayScheduler { delay, action ->
            val item = Entry(time + delay, action); queue.add(item)
            CancelToken { item.cancelled = true }
        }, GestureDriver { step, callback ->
            calls.add(time to step); callbacks.add(callback)
            if (throws) error("platform failure")
            if (completeImmediately) callback(true)
            accepted
        })
        fun advance(to: Long) {
            var processed = 0
            while (true) {
                check(++processed <= 10_000) { "Scheduler did not yield" }
                val item = queue.filter { !it.cancelled && it.at <= to }.minByOrNull { it.at } ?: break
                queue.remove(item); time = item.at; item.action()
            }
            time = to
        }
        fun steps(vararg delays: Long) = delays.mapIndexed { index, delay ->
            ClickStep(TapPoint(index + 10f, 20f), delay, 1)
        }
    }

    @Test fun `cycles use each point delay including last back to first until stopped`() {
        val env = Runtime(); env.runner.start(env.steps(1_000, 500, 1_000), inputReadyAt = 150)
        env.advance(999); assertTrue(env.calls.isEmpty())
        env.advance(1_000); assertEquals(1, env.calls.size)
        env.advance(1_080); env.callbacks[0](true)
        env.advance(1_499); assertEquals(1, env.calls.size)
        env.advance(1_500); env.callbacks[1](true)
        env.advance(2_500); env.callbacks[2](true)
        env.advance(3_499); assertEquals(3, env.calls.size)
        env.advance(3_500); env.callbacks[3](true)
        env.advance(4_000); env.callbacks[4](true)
        env.advance(5_000); env.callbacks[5](true)
        env.runner.stop()
        env.advance(10_000)
        assertEquals(listOf(1_000L, 1_500L, 2_500L, 3_500L, 4_000L, 5_000L), env.calls.map { it.first })
        assertEquals(listOf(10f, 11f, 12f, 10f, 11f, 12f), env.calls.map { it.second.point.x })
        assertEquals(RunStatus(RunPhase.STOPPED, 6, 3), env.runner.status)
    }

    @Test fun `slow completion never overlaps gestures or shifts all future delays by callback time`() {
        val env = Runtime(); env.runner.start(env.steps(0, 10, 100))
        env.advance(0); env.advance(80); assertEquals(1, env.calls.size)
        env.callbacks[0](true); env.advance(80); assertEquals(2, env.calls.size)
        env.callbacks[1](true); env.advance(180); assertEquals(3, env.calls.size)
    }

    @Test fun `input preparation only limits very short first delays`() {
        val env = Runtime(); env.runner.start(env.steps(0), 150)
        env.advance(149); assertTrue(env.calls.isEmpty())
        env.advance(150); assertEquals(150L, env.calls.single().first)
    }

    @Test fun `stop invalidates queued wakeups and late gesture completion`() {
        val env = Runtime(); env.runner.start(env.steps(0, 100))
        env.advance(0); val stale = env.callbacks.single()
        env.runner.stop(); stale(true); env.advance(10_000)
        assertEquals(1, env.calls.size); assertEquals(RunPhase.STOPPED, env.runner.status.phase)
    }

    @Test fun `early and duplicate wakeups cannot inject early or twice`() {
        val env = Runtime(); env.runner.start(env.steps(1_000))
        val action = env.queue.first().action
        env.time = 999; action(); assertTrue(env.calls.isEmpty())
        env.advance(1_000); action(); assertEquals(1, env.calls.size)
    }

    @Test fun `rejected cancelled timed out and thrown gestures terminate without advancing`() {
        for (mode in 0..3) {
            val env = Runtime(); env.accepted = mode != 0; env.throws = mode == 3
            env.runner.start(env.steps(0, 100)); env.advance(0)
            if (mode == 1) env.callbacks.single()(false)
            env.advance(5_000)
            assertEquals(1, env.calls.size); assertEquals(RunPhase.FAILED, env.runner.status.phase)
        }
    }

    @Test fun `new run ignores old completion and snapshots mutable editor list`() {
        val env = Runtime(); env.runner.start(env.steps(0)); env.advance(0)
        val stale = env.callbacks.single()
        val edited = env.steps(100, 100).toMutableList()
        env.runner.start(edited); edited.clear(); stale(true)
        env.advance(100); env.callbacks.last()(true)
        env.advance(200); env.callbacks.last()(true)
        assertEquals(3, env.calls.size); assertEquals(2, env.runner.status.completed)
    }

    @Test fun `sequence and delay input are bounded`() {
        val env = Runtime()
        for (steps in listOf(emptyList(), (0..50).map { env.steps(0).single() })) {
            assertThrows(IllegalArgumentException::class.java) { env.runner.start(steps) }
        }
        assertEquals(1_000L, MultiPointSequence.DEFAULT_DELAY)
        assertEquals(0L, MultiPointSequence.delay("0"))
        assertEquals(86_400_000L, MultiPointSequence.delay("86400000"))
        for (text in listOf("", "-1", "86400001", "1.5", "9223372036854775808")) assertNull(MultiPointSequence.delay(text))
    }

    @Test fun `stop from status notification prevents a new injection`() {
        for (stopAt in listOf(RunPhase.WAITING, RunPhase.RUNNING)) {
            var injected = 0
            var time = 0L
            val actions = mutableListOf<() -> Unit>()
            lateinit var runner: MultiPointRunner
            runner = MultiPointRunner({ time }, DelayScheduler { _, action -> actions.add(action); CancelToken {} },
                GestureDriver { _, _ -> injected++; true }, { if (it.phase == stopAt) runner.stop() })
            runner.start(listOf(ClickStep(TapPoint(10f, 10f), 0, 1)))
            time = 100
            actions.toList().forEach { it() }
            assertEquals(0, injected)
            assertEquals(RunPhase.STOPPED, runner.status.phase)
        }
    }

    @Test fun `stop before first deadline ignores even a cancelled action delivered by scheduler`() {
        val env = Runtime(); env.runner.start(env.steps(1_000))
        val stale = env.queue.first().action
        env.runner.stop(); env.time = 1_000; stale(); env.advance(5_000)
        assertTrue(env.calls.isEmpty())
    }

    @Test fun `duplicate completion cannot consume two points`() {
        val env = Runtime(); env.runner.start(env.steps(0, 100))
        env.advance(0); val done = env.callbacks.single()
        done(true); done(true); done(false)
        env.advance(100); env.callbacks.last()(true)
        assertEquals(2, env.calls.size); assertEquals(RunStatus(RunPhase.WAITING, 2, 2), env.runner.status)
        env.runner.stop()
    }

    @Test fun `single point keeps repeating rather than completing`() {
        val env = Runtime(); env.runner.start(env.steps(1_000))
        for (index in 1..3) { env.advance(index * 1_000L); env.callbacks.last()(true) }
        assertEquals(listOf(1_000L, 2_000L, 3_000L), env.calls.map { it.first })
        assertEquals(RunPhase.WAITING, env.runner.status.phase)
        env.runner.stop(); env.advance(20_000); assertEquals(3, env.calls.size)
    }

    @Test fun `zero delay with synchronous completion yields instead of spinning at same time`() {
        val env = Runtime(); env.completeImmediately = true; env.runner.start(env.steps(0))
        env.advance(5)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), env.calls.map { it.first })
        env.runner.stop(); env.advance(100)
        assertEquals(6, env.calls.size)
        assertTrue(env.queue.none { !it.cancelled })
    }
}
