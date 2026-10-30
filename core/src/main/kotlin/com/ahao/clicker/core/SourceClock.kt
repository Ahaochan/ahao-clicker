package com.ahao.clicker.core

import java.time.ZoneId

/** A failed/absent source must never be presented as the device's clock. */
object SourceClock {
    fun text(reference: TimeReference, elapsed: Long, deviceEpoch: Long, zone: ZoneId): String? {
        if (!reference.synced) return null
        val epoch = reference.now(elapsed)
        val offset = epoch - deviceEpoch
        return ClockDigits.format(epoch, zone) + "(" + (if (offset >= 0) "+" else "") + offset + ")"
    }
}
