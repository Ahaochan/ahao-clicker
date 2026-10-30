package com.ahao.clicker.core

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class NextTriggerTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun epoch(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun `future time stays today and preserves milliseconds`() {
        assertEquals(epoch("2026-10-26T12:59:59.750+08:00"), NextTriggerTime.resolve(
            epoch("2026-10-26T12:00:00+08:00"), LocalTime.of(12, 59, 59, 750_000_000), zone))
    }

    @Test fun `past or identical time uses tomorrow without repeating`() {
        val time = LocalTime.of(12, 0, 0, 999_000_000)
        for (now in listOf("2026-10-26T12:00:00.999+08:00", "2026-10-26T12:00:01+08:00")) {
            assertEquals(epoch("2026-10-27T12:00:00.999+08:00"), NextTriggerTime.resolve(epoch(now), time, zone))
        }
    }

    @Test fun `midnight handles month and year rollover`() {
        assertEquals(epoch("2027-01-01T00:00:00+08:00"), NextTriggerTime.resolve(
            epoch("2026-12-31T23:59:59.999+08:00"), LocalTime.MIDNIGHT, zone))
    }

    @Test fun `daylight saving gap skips missing time instead of silently shifting it`() {
        assertEquals(epoch("2026-03-09T02:30:00-04:00"), NextTriggerTime.resolve(
            epoch("2026-03-08T00:00:00-05:00"), LocalTime.of(2, 30), ZoneId.of("America/New_York")))
    }

    @Test fun `daylight saving overlap takes the next actual occurrence`() {
        assertEquals(epoch("2026-11-01T01:30:00-05:00"), NextTriggerTime.resolve(
            epoch("2026-11-01T01:45:00-04:00"), LocalTime.of(1, 30), ZoneId.of("America/New_York")))
    }
}
