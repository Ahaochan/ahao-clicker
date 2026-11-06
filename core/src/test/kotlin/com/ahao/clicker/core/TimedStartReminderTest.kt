package com.ahao.clicker.core

import com.ahao.clicker.core.TimedStartReminder.State.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TimedStartReminderTest {
    private val reminder = TimedStartReminder()
    private val target = 100_000L

    @Test fun `warn only within the final minute before the target`() {
        assertEquals(NONE, reminder.update(null, 40_000, 1_000))
        assertEquals(NONE, reminder.update(target, 39_999, 1_000))
        assertEquals(NOTIFY, reminder.update(target, 40_000, 1_001))
        assertEquals(WARNING, reminder.update(target, 99_999, 1_002))
        assertEquals(NONE, reminder.update(target, 100_000, 1_003))
        assertEquals(NONE, reminder.update(target, 100_001, 1_004))
    }

    @Test fun `repeat on monotonic time without catching up missed reminders`() {
        assertEquals(NOTIFY, reminder.update(target, 40_000, 1_000))
        assertEquals(WARNING, reminder.update(target, 61_000, 10_999))
        assertEquals(NOTIFY, reminder.update(target, 48_000, 11_000))
        assertEquals(NOTIFY, reminder.update(target, 80_000, 36_000))
        assertEquals(WARNING, reminder.update(target, 80_001, 36_001))
        assertEquals(WARNING, reminder.update(target, 89_999, 45_999))
        assertEquals(NOTIFY, reminder.update(target, 90_000, 46_000))
    }

    @Test fun `new target and lifecycle reset start a fresh reminder`() {
        assertEquals(NOTIFY, reminder.update(target, 50_000, 1_000))
        assertEquals(NOTIFY, reminder.update(target + 1_000, 50_001, 1_001))
        reminder.reset()
        assertEquals(NOTIFY, reminder.update(target + 1_000, 50_002, 1_002))
        assertEquals(NONE, reminder.update(target + 1_000, 39_000, 1_003))
        assertEquals(NOTIFY, reminder.update(target + 1_000, 41_000, 1_004))
    }

    @Test fun `action feedback can defer a notice without consuming its interval`() {
        assertEquals(WARNING, reminder.update(target, 50_000, 1_000, allowNotice = false))
        assertEquals(NOTIFY, reminder.update(target, 52_000, 3_000))
        assertEquals(WARNING, reminder.update(target, 62_000, 13_000, allowNotice = false))
        assertEquals(NOTIFY, reminder.update(target, 63_000, 14_000))
    }
}
