package com.ahao.clicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TapTaskTest {
    @Test
    fun `accepts origin and zero wait between gestures`() {
        val task = TapTask(TapPoint(0f, 0f), intervalMillis = 0)
        assertEquals(1, task.repeatCount)
        assertEquals(0L, task.intervalMillis)
    }

    @Test
    fun `rejects invalid coordinates on either axis`() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { TapPoint(it, 0f) }
            assertThrows(IllegalArgumentException::class.java) { TapPoint(0f, it) }
        }
    }

    @Test
    fun `rejects nonpositive repetition and duration`() {
        val point = TapPoint(10f, 20f)
        listOf(0, -1).forEach {
            assertThrows(IllegalArgumentException::class.java) { TapTask(point, repeatCount = it) }
        }
        listOf(0L, -1L).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                TapTask(point, pressDurationMillis = it)
            }
        }
    }

    @Test
    fun `rejects negative wait`() {
        assertThrows(IllegalArgumentException::class.java) {
            TapTask(TapPoint(10f, 20f), intervalMillis = -1)
        }
    }
}
