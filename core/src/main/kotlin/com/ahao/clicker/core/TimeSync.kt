package com.ahao.clicker.core

import java.net.URI

enum class TimeFormat { HTTP_DATE, JSON_MILLIS, JSON_SECONDS }

data class TimeSourceConfig(
    val url: String,
    val format: TimeFormat = TimeFormat.HTTP_DATE,
    val field: String = "timestamp",
    val intervalMillis: Long = 60_000,
) {
    init {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(intervalMillis in 1_000..86_400_000)
        require(format == TimeFormat.HTTP_DATE || field.isNotBlank())
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
