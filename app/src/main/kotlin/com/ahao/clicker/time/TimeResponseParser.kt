package com.ahao.clicker.time

import com.ahao.clicker.core.TimeFormat
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object TimeResponseParser {
    private val requestId = Regex("[0-9]{1,32}-[0-9]{1,32}-[0-9]{13}")
    private fun httpDate(value: String?) = ZonedDateTime.parse(requireNotNull(value), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    fun parse(format: TimeFormat, field: String, date: String?, body: String, header: String? = null): Long {
        if (format == TimeFormat.JD_REQUEST_ID) {
            require(header != null && header.length <= 128 && requestId.matches(header))
            val epoch = header.substringAfterLast('-').toLong()
            require(kotlin.math.abs(epoch - httpDate(date)) <= 2_000)
            return epoch
        }
        if (format == TimeFormat.HTTP_DATE) return httpDate(date)
        require(body.length <= 65_536)
        var value: Any = JSONObject(body)
        for (key in field.split('.')) value = (value as JSONObject).get(key)
        val timestamp = value.toString().toLong()
        return if (format == TimeFormat.JSON_SECONDS) Math.multiplyExact(timestamp, 1_000) else timestamp
    }
}
