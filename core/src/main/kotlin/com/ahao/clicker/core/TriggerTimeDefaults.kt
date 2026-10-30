package com.ahao.clicker.core

import java.time.Instant
import java.time.ZoneId

/** Only the millisecond position within an hour is remembered, never a date or hour. */
object TriggerTimeDefaults {
    fun remember(target: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(target).atZone(zone)
        return (time.minute * 60_000 + time.second * 1_000 + time.nano / 1_000_000).toString()
    }

    fun seed(now: Long, saved: String, zone: ZoneId): Long {
        val millis = saved.toIntOrNull()?.takeIf { it in 0..3_599_999 } ?: return now
        return Instant.ofEpochMilli(now).atZone(zone)
            .withMinute(millis / 60_000).withSecond(millis / 1_000 % 60)
            .withNano(millis % 1_000 * 1_000_000).toInstant().toEpochMilli()
    }
}
