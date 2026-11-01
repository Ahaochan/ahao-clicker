package com.ahao.clicker.time

import com.ahao.clicker.core.BuiltinTimeSource
import com.ahao.clicker.core.TimeSourceConfig
import org.junit.Assert.*
import org.junit.Test

class LocalTimeProviderTest {
    @Test fun `device time is usable without an external synchronization sample`() {
        val local = LocalTimeProvider({ 123 }, { 1_000_000 })
        assertTrue(local.reference().synced)
        assertEquals(1_000_000L, local.reference().now(123))
        assertTrue(local.hasFreshSample())
        assertFalse(local.state.syncing || local.state.failed)
        assertEquals(-1L, local.state.nextAttemptElapsed)
    }
    @Test fun `steady local time keeps one reference rather than rebasing every poll`() {
        var elapsed = 100L; var epoch = 1_000_000L
        val local = LocalTimeProvider({ elapsed }, { epoch })
        val initial = local.reference()
        for (advance in listOf(10L, 10_000L, 86_400_000L * 30)) {
            elapsed += advance; epoch += advance
            assertSame(initial, local.reference())
            assertEquals(epoch, initial.now(elapsed))
        }
    }
    @Test fun `system clock adjustment is read again instead of accumulating old local ticks`() {
        var elapsed = 100L; var epoch = 1_000_000L
        val local = LocalTimeProvider({ elapsed }, { epoch })
        val before = local.reference()
        elapsed += 1_000; epoch += 1_800
        val changed = local.reference()
        assertNotSame(before, changed); assertEquals(epoch, changed.now(elapsed))
        epoch -= 500
        assertEquals(epoch, local.reference().now(elapsed))
    }
    @Test fun `local provider remains usable when page observation stops`() {
        val local = LocalTimeProvider({ 123 }, { 1_000_000 })
        local.pause()
        assertTrue(local.hasFreshSample())
        assertFalse(local.state.failed)
    }
    @Test fun `local provider does not accept a network source configuration`() {
        val local = LocalTimeProvider({ 123 }, { 1_000_000 })
        assertThrows(IllegalArgumentException::class.java) {
            local.configure(TimeSourceConfig.builtin(BuiltinTimeSource.JD), true)
        }
    }
}
