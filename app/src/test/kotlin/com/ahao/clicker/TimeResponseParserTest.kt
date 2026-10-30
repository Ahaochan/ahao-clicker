package com.ahao.clicker

import com.ahao.clicker.time.TimeResponseParser

import com.ahao.clicker.core.TimeFormat
import org.junit.Assert.*
import org.junit.Test

class TimeResponseParserTest {
    @Test fun `taobao requires its numeric millisecond field`() {
        val config = com.ahao.clicker.core.TimeSourceConfig.builtin(com.ahao.clicker.core.BuiltinTimeSource.TAOBAO)
        assertEquals(1792943999700L, TimeResponseParser.parse(config.format, config.field, null,
            """{"data":{"t":"1792943999700"},"ret":["SUCCESS::接口调用成功"]}"""))
        assertThrows(Exception::class.java) { TimeResponseParser.parse(config.format, config.field, null, """{"ret":["FAIL_SYS"]}""") }
    }
    @Test fun `JD reads the complete request ID suffix and checks its server date`() {
        val date = "Mon, 26 Oct 2026 02:10:14 GMT"
        assertEquals(1792980614483L, TimeResponseParser.parse(TimeFormat.JD_REQUEST_ID, "X-API-Request-Id", date, "not JSON",
            "10105444978-107668-1792980614483"))
        assertEquals(1792980614483L, TimeResponseParser.parse(TimeFormat.JD_REQUEST_ID, "X-API-Request-Id",
            "Mon, 26 Oct 2026 02:10:15 GMT", "", "1-2-1792980614483"))
    }
    @Test fun `JD rejects absent malformed or inconsistent headers without JSON or Date fallback`() {
        for (id in listOf(null, "", "1792980614483", "a-b-1792980614483", "1-2-1792980614", "1-2-17929806144830",
            "1-2-1792980614483-extra", "1-2-1792980614.483", "1-2-+1792980614483", "1-2-1792980614483\r\nX:1", "1".repeat(129))) {
            assertThrows(Exception::class.java) { TimeResponseParser.parse(TimeFormat.JD_REQUEST_ID, "X-API-Request-Id",
                "Mon, 26 Oct 2026 02:10:14 GMT", """{"currentTime2":1792980614483}""", id) }
        }
        for (date in listOf(null, "invalid", "Mon, 26 Oct 2026 02:00:14 GMT")) {
            assertThrows(Exception::class.java) { TimeResponseParser.parse(TimeFormat.JD_REQUEST_ID, "X-API-Request-Id", date, "", "1-2-1792980614483") }
        }
    }
    @Test fun `reads nested JSON milliseconds and seconds without rounding`() {
        assertEquals(1_700_000_000_123L, TimeResponseParser.parse(TimeFormat.JSON_MILLIS, "data.time", null, """{"data":{"time":1700000000123}}"""))
        assertEquals(1_700_000_000_000L, TimeResponseParser.parse(TimeFormat.JSON_SECONDS, "time", null, """{"time":"1700000000"}"""))
    }
    @Test fun `parses RFC HTTP Date in UTC`() {
        assertEquals(0L, TimeResponseParser.parse(TimeFormat.HTTP_DATE, "", "Thu, 01 Jan 1970 00:00:00 GMT", ""))
    }
    @Test fun `rejects missing fractional overflowing and oversized payloads`() {
        for (body in listOf("{}", """{"time":1.5}""", """{"time":9223372036854775807}""", "x".repeat(65_537))) {
            assertThrows(Exception::class.java) { TimeResponseParser.parse(TimeFormat.JSON_SECONDS, "time", null, body) }
        }
        assertThrows(Exception::class.java) { TimeResponseParser.parse(TimeFormat.HTTP_DATE, "", null, "") }
    }
}
