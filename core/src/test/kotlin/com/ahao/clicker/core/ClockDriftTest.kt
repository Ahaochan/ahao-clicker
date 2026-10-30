package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class ClockDriftTest {
    private val epoch = 1_790_000_000_000L
    private fun oscillator(realMillis: Long, ppm: Long) = realMillis + realMillis * ppm / 1_000_000

    @Test fun `periodic samples bound oscillator drift over seven virtual days`() {
        for (ppm in listOf(-200L, 200L)) {
            val time = SyncedTime()
            for (minute in 0L..7 * 24 * 60) {
                val real = minute * 60_000
                val elapsed = oscillator(real, ppm)
                assertTrue(time.accept(epoch + real, elapsed, elapsed))
                for (delay in listOf(0L, 871L, 32_917L, 59_999L)) {
                    val observed = time.snapshot(oscillator(real + delay, ppm), -99)
                        .now(oscillator(real + delay, ppm))
                    assertTrue(kotlin.math.abs(observed - (epoch + real + delay)) <= 12)
                }
            }
        }
    }

    @Test fun `no synchronization cannot guarantee accuracy and recovery replaces accumulated error`() {
        val time = SyncedTime()
        time.accept(epoch, 0, 0)
        val real = 24 * 60 * 60_000L
        val drifted = oscillator(real, 200)
        assertEquals(17_280, time.snapshot(drifted, 0).now(drifted) - (epoch + real))
        assertTrue(time.accept(epoch + real, drifted, drifted))
        assertEquals(epoch + real, time.snapshot(drifted, 0).now(drifted))
        // An invalid network response must not overwrite the last accepted reference.
        assertFalse(time.accept(0, drifted, drifted + 1))
        assertEquals(epoch + real + 123, time.snapshot(drifted + 123, Long.MAX_VALUE).now(drifted + 123))
    }

    @Test fun `suspended refresh catches up from elapsed anchor without tick accumulation`() {
        val time = SyncedTime()
        time.accept(epoch, 100, 100)
        // No update for eight hours, as with a stalled UI; elapsedRealtime includes that duration.
        val elapsed = 100 + 8 * 60 * 60_000L + 871
        assertEquals(epoch + elapsed - 100, time.snapshot(elapsed, -86_400_000).now(elapsed))
    }
}
