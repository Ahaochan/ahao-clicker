package com.ahao.clicker.core

/** Uses reference time for the warning window and monotonic time for the prompt cadence. */
class TimedStartReminder {
    enum class State { NONE, WARNING, NOTIFY }

    private var notifiedTarget: Long? = null
    private var lastNoticeElapsed = 0L

    fun update(target: Long?, now: Long, elapsed: Long, allowNotice: Boolean = true): State {
        if (target == null || target - now !in 1L..60_000L) {
            reset()
            return State.NONE
        }
        if (!allowNotice || (notifiedTarget == target && elapsed - lastNoticeElapsed < 10_000L)) return State.WARNING
        notifiedTarget = target
        lastNoticeElapsed = elapsed
        return State.NOTIFY
    }

    fun reset() { notifiedTarget = null }
}
