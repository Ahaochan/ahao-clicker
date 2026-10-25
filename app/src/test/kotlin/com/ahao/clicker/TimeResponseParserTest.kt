package com.ahao.clicker

import com.ahao.clicker.core.TimeFormat
import org.junit.Assert.*
import org.junit.Test

class TimeResponseParserTest {
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
