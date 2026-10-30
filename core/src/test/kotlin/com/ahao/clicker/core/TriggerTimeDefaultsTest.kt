package com.ahao.clicker.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerTimeDefaultsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun epoch(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun `confirmed value contains only minute second and millisecond`() {
        for (hour in listOf("00", "12", "23")) {
            assertEquals("3599750", TriggerTimeDefaults.remember(epoch("2026-10-30T$hour:59:59.750+08:00"), zone))
        }
    }

    @Test fun `reopening uses current hour with remembered minute second and millisecond`() {
        val saved = TriggerTimeDefaults.remember(epoch("2026-10-29T23:59:59.750+08:00"), zone)
        assertEquals(epoch("2026-10-30T12:59:59.750+08:00"),
            TriggerTimeDefaults.seed(epoch("2026-10-30T12:13:14.123+08:00"), saved, zone))
    }

    @Test fun `midnight uses new date and zero hour without restoring previous hour`() {
        assertEquals(epoch("2027-01-01T00:59:59.999+08:00"),
            TriggerTimeDefaults.seed(epoch("2027-01-01T00:00:00+08:00"), "3599999", zone))
    }

    @Test fun `past minute is a draft in current hour and does not silently roll forward`() {
        assertEquals(epoch("2026-10-30T12:00:00+08:00"),
            TriggerTimeDefaults.seed(epoch("2026-10-30T12:59:59.999+08:00"), "0", zone))
    }

    @Test fun `absent or corrupt memory leaves current time unchanged`() {
        val now = epoch("2026-10-30T12:13:14.123+08:00")
        for (saved in listOf("", "-1", "3600000", "NaN", "2147483648")) {
            assertEquals(now, TriggerTimeDefaults.seed(now, saved, zone))
        }
    }

    @Test fun `memory follows local minute in time zones with fractional hour offset`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        val saved = TriggerTimeDefaults.remember(epoch("2026-10-29T23:59:59.750+05:45"), kathmandu)
        assertEquals(epoch("2026-10-30T07:59:59.750+05:45"),
            TriggerTimeDefaults.seed(epoch("2026-10-30T07:01:02.003+05:45"), saved, kathmandu))
    }
}
