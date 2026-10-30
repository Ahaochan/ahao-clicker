package com.ahao.clicker.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Resolves once at confirmation; this does not create a daily repeating task. */
object NextTriggerTime {
    fun resolve(now: Long, time: LocalTime, zone: ZoneId): Long {
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        while (true) {
            val local = date.atTime(time)
            val next = zone.rules.getValidOffsets(local).map { local.toInstant(it).toEpochMilli() }
                .filter { it > now }.minOrNull()
            if (next != null) return next
            date = date.plusDays(1)
        }
    }
}
