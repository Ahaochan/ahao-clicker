package com.ahao.clicker.core

import java.net.URI

enum class TimeFormat { HTTP_DATE, JSON_MILLIS, JSON_SECONDS, JD_REQUEST_ID }

enum class BuiltinTimeSource(val host: String, val url: String = "", val field: String = "timestamp", val minimumIntervalMillis: Long = 60_000) {
    ALIYUN("ntp.aliyun.com"), TENCENT("ntp.tencent.com"), CLOUDFLARE("time.cloudflare.com"),
    JD("api.m.jd.com", "https://api.m.jd.com", "X-API-Request-Id", 1_000),
    TAOBAO("acs.m.taobao.com", "https://acs.m.taobao.com/gw/mtop.common.getTimestamp/1.0/", "data.t", 1_000),
    ;
    val format: TimeFormat get() = when {
        this == JD -> TimeFormat.JD_REQUEST_ID
        url.isEmpty() -> TimeFormat.HTTP_DATE
        else -> TimeFormat.JSON_MILLIS
    }
}

data class TimeSourceConfig(
    val url: String = "",
    val format: TimeFormat = TimeFormat.HTTP_DATE,
    val field: String = "timestamp",
    val intervalMillis: Long = 60_000,
    val builtin: BuiltinTimeSource? = null,
) {
    init {
        if (builtin == null) {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        } else {
            require(url == builtin.url)
            if (url.isNotEmpty()) require(format == builtin.format && field == builtin.field)
        }
        require(intervalMillis in 1_000..86_400_000)
        require(format == TimeFormat.HTTP_DATE || field.isNotBlank())
    }
    companion object {
        fun builtin(source: BuiltinTimeSource, intervalMillis: Long = 60_000) =
            TimeSourceConfig(url = source.url, format = source.format,
                field = source.field, intervalMillis = intervalMillis, builtin = source)
    }
}

data class TimeReference(val epochMillis: Long, val elapsedMillis: Long, val synced: Boolean) {
    fun now(elapsed: Long): Long = epochMillis + (elapsed - elapsedMillis)
}

/** RTT midpoint estimate; timestamps are estimates, not a hard-real-time clock guarantee. */
class SyncedTime {
    @Volatile private var reference: TimeReference? = null
    fun reset() { reference = null }

    fun accept(serverEpochMillis: Long, sentElapsed: Long, receivedElapsed: Long): Boolean {
        val rtt = receivedElapsed - sentElapsed
        if (serverEpochMillis !in 1..253_402_300_799_999 || sentElapsed < 0 || rtt !in 0..2_000) return false
        reference = TimeReference(serverEpochMillis, sentElapsed + rtt / 2, true)
        return true
    }

    fun snapshot(elapsed: Long, deviceEpoch: Long): TimeReference =
        reference ?: TimeReference(deviceEpoch, elapsed, false)
}
