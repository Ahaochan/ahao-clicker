package com.ahao.clicker.core

/** Bounded service/lifecycle checks. The visible clock draws on vsync; deadlines have an independent scheduler. */
object ClockRefresh {
    @Suppress("UNUSED_PARAMETER") // Remaining time no longer accelerates checks; the deadline has its own scheduler.
    fun delayMillis(visible: Boolean, remainingMillis: Long? = null): Long = when {
        !visible -> 250L
        else -> 100L
    }
}
