package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class TimeCatalogTest {
    @Test fun `built in commerce APIs carry their own timestamp mapping`() {
        val jd = TimeSourceConfig.builtin(BuiltinTimeSource.JD, 1_000)
        assertEquals("https://api.m.jd.com", jd.url)
        assertEquals("X-API-Request-Id", jd.field)
        assertEquals(TimeFormat.JD_REQUEST_ID, jd.format)
        val taobao = TimeSourceConfig.builtin(BuiltinTimeSource.TAOBAO, 1_000)
        assertEquals("data.t", taobao.field)
        assertTrue(taobao.url.startsWith("https://acs.m.taobao.com/"))
        assertEquals(TimeFormat.JSON_MILLIS, taobao.format)
    }
    @Test fun `numeric interval bounds are shared while provider pacing remains independent`() {
        for (source in BuiltinTimeSource.entries) {
            assertEquals(1_000, TimeSourceConfig.builtin(source, 1_000).intervalMillis.toInt())
            TimeSourceConfig.builtin(source, 86_400_000)
            for (bad in listOf(0L, 999L, 86_400_001L, Long.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { TimeSourceConfig.builtin(source, bad) }
            }
        }
        val pacing = SyncPacing()
        pacing.attempted(BuiltinTimeSource.JD, 100)
        pacing.attempted(BuiltinTimeSource.ALIYUN, 100)
        assertEquals(500L, pacing.delay(BuiltinTimeSource.JD, 600))
        assertEquals(59_500L, pacing.delay(BuiltinTimeSource.ALIYUN, 600))
        assertEquals(0L, pacing.delay(BuiltinTimeSource.TAOBAO, 600))
    }
    @Test fun `display extrapolates each source with signed millisecond offset`() {
        val sample = TimeReference(86_399_700, 1_000, true)
        assertEquals("23:59:59:700(+800)", SourceClock.text(sample, 1_000, 86_398_900, java.time.ZoneOffset.UTC))
        assertEquals("00:00:00:100(-50)", SourceClock.text(sample, 1_400, 86_400_150, java.time.ZoneOffset.UTC))
        assertEquals("00:00:00:100(+0)", SourceClock.text(sample, 1_400, 86_400_100, java.time.ZoneOffset.UTC))
        assertNull(SourceClock.text(TimeReference(100, 1_000, false), 1_000, 100, java.time.ZoneOffset.UTC))
    }
}
