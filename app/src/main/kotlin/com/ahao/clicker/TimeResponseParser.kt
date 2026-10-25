package com.ahao.clicker

import com.ahao.clicker.core.TimeFormat
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object TimeResponseParser {
    fun parse(format: TimeFormat, field: String, date: String?, body: String): Long {
        if (format == TimeFormat.HTTP_DATE) return ZonedDateTime.parse(
            requireNotNull(date), DateTimeFormatter.RFC_1123_DATE_TIME,
        ).toInstant().toEpochMilli()
        require(body.length <= 65_536)
        var value: Any = JSONObject(body)
        for (key in field.split('.')) value = (value as JSONObject).get(key)
        val timestamp = value.toString().toLong()
        return if (format == TimeFormat.JSON_SECONDS) Math.multiplyExact(timestamp, 1_000) else timestamp
    }
}
