package com.ahao.clicker

import org.junit.Assert.*
import org.junit.Test

class TimeWheelTest {
    @Test fun `millisecond wheel has one hundred ten millisecond positions`() {
        assertEquals(99, TimeWheel.maximum)
        assertEquals("000", TimeWheel.label(0))
        assertEquals("010", TimeWheel.label(1))
        assertEquals("990", TimeWheel.label(99))
        assertEquals((0..990 step 10).toList(), (0..99).map(TimeWheel::millis))
    }
    @Test fun `legacy milliseconds map down without carrying into seconds`() {
        assertEquals(0, TimeWheel.position(9))
        assertEquals(34, TimeWheel.position(344))
        assertEquals(99, TimeWheel.position(999))
        assertEquals(340, TimeWheel.millis(TimeWheel.position(344)))
    }
    @Test fun `out of range values are rejected`() {
        for (value in listOf(-1, 100)) assertThrows(IllegalArgumentException::class.java) { TimeWheel.millis(value) }
        for (value in listOf(-1, 1_000)) assertThrows(IllegalArgumentException::class.java) { TimeWheel.position(value) }
    }
}
