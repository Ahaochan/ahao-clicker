package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class OneShotTest {
    private class Runtime : DelayScheduler, GestureDriver {
        var elapsed = 100L
        var lastPoint: TapPoint? = null
        var taps = 0
        var accepted = true
        val actions = mutableListOf<() -> Unit>()
        var result: ((Boolean) -> Unit)? = null
        override fun after(delayMillis: Long, action: () -> Unit): CancelToken {
            actions.add(action)
            return CancelToken { /* Deliberately deliver cancelled callbacks too. */ }
        }
        override fun tap(step: ClickStep, result: (Boolean) -> Unit): Boolean {
            lastPoint = step.point
            taps++; this.result = result
            return accepted
        }
        val runner = OneShotRunner({ elapsed }, this, this)
        fun arm() = runner.arm(TapPoint(10f, 20f), 2_000, TimeReference(1_000, 100, true))
        fun due() { elapsed = 1_100; actions.first().invoke() }
    }

    @Test fun `reads the latest point only at dispatch and consumes once`() {
        val env = Runtime()
        var point = TapPoint(10f, 20f)
        var reads = 0
        env.runner.arm({ reads++; point }, 2_000, TimeReference(1_000, 100, true))
        assertEquals(0, reads)
        point = TapPoint(30f, 40f)
        env.due(); env.actions.first().invoke()
        assertEquals(point, env.lastPoint)
        assertEquals(1, reads)
        assertEquals(1, env.taps)
    }

    @Test fun `cancelled or failed point resolution cannot dispatch or retry`() {
        val cancelled = Runtime()
        cancelled.runner.arm({ error("Must not resolve after stop") }, 2_000, TimeReference(1_000, 100, true))
        cancelled.runner.stop(); cancelled.due()
        assertEquals(0, cancelled.taps)
        val failed = Runtime()
        failed.runner.arm({ error("Point unavailable") }, 2_000, TimeReference(1_000, 100, true))
        failed.due(); failed.actions.first().invoke()
        assertEquals(0, failed.taps)
        assertEquals(RunPhase.FAILED, failed.runner.status.phase)
    }

    @Test fun `fires once and duplicate timer and completion callbacks cannot repeat`() {
        val env = Runtime(); env.arm()
        assertEquals(0, env.taps)
        env.due(); env.actions.first().invoke()
        assertEquals(1, env.taps)
        env.result!!(true); env.result!!(true)
        env.actions.toList().forEach { it() }
        assertEquals(1, env.taps)
        assertEquals(RunPhase.COMPLETED, env.runner.status.phase)
    }

    @Test fun `stopping invalidates even an uncancelled scheduled callback`() {
        val env = Runtime(); env.arm(); env.runner.stop(); env.due()
        assertEquals(0, env.taps)
    }

    @Test fun `reject timeout and failed callback consume the only attempt`() {
        repeat(3) { mode ->
            val env = Runtime(); env.accepted = mode != 0; env.arm(); env.due()
            if (mode == 1) env.result!!(false)
            if (mode == 2) env.actions.last().invoke()
            env.result?.invoke(true)
            env.actions.toList().forEach { it() }
            assertEquals(1, env.taps)
            assertEquals(RunPhase.FAILED, env.runner.status.phase)
        }
    }

    @Test fun `expired and unsynced times are rejected without starting`() {
        val env = Runtime()
        assertThrows(IllegalArgumentException::class.java) {
            env.runner.arm(TapPoint(1f, 1f), 1_000, TimeReference(1_000, 100, true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            env.runner.arm(TapPoint(1f, 1f), 2_000, TimeReference(1_000, 100, false))
        }
        assertEquals(0, env.taps)
    }

    @Test fun `a stalled timer expires rather than catching up with a late click`() {
        val env = Runtime(); env.arm(); env.elapsed = 2_000; env.actions.first().invoke()
        assertEquals(0, env.taps)
        assertEquals(RunPhase.EXPIRED, env.runner.status.phase)
    }

    @Test fun `clock is frozen and callbacks from an earlier run cannot affect the new run`() {
        val env = Runtime(); env.arm(); val old = env.actions.first()
        assertEquals(1_500, env.runner.reference!!.now(600))
        env.runner.stop(); env.arm(); old()
        assertEquals(RunPhase.WAITING, env.runner.status.phase)
        assertEquals(0, env.taps)
    }

    @Test fun `millisecond columns cover all digits and preserve cross-day values`() {
        assertEquals(750, MillisecondDigits.value(7, 5, 0))
        assertEquals(999, MillisecondDigits.value(9, 9, 9))
        assertEquals(0, MillisecondDigits.value(0, 0, 0))
        assertThrows(IllegalArgumentException::class.java) { MillisecondDigits.value(10, 0, 0) }
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val last = java.time.LocalDateTime.of(2026, 10, 25, 23, 59, 59, 999_000_000).atZone(zone)
        assertEquals("23:59:59:999", ClockDigits.format(last.toInstant().toEpochMilli(), zone))
        assertEquals("00:00:00:000", ClockDigits.format(last.toInstant().toEpochMilli() + 1, zone))
    }
}
