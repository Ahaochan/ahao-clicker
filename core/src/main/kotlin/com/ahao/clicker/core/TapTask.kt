package com.ahao.clicker.core

/** Coordinates in pixels relative to the target display's coordinate space. */
data class TapPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && x >= 0f) { "x must be finite and non-negative" }
        require(y.isFinite() && y >= 0f) { "y must be finite and non-negative" }
    }
}

/**
 * A finite single-point task. Bounds against the current display are checked by the platform.
 * [intervalMillis] is the wait after a completed gesture, not a guaranteed start-to-start period.
 * Gesture duration limits are validated by the platform before dispatch.
 */
data class TapTask(
    val point: TapPoint,
    val repeatCount: Int = 1,
    val pressDurationMillis: Long = 50,
    val intervalMillis: Long = 500,
) {
    init {
        require(repeatCount > 0) { "repeatCount must be positive" }
        require(pressDurationMillis > 0) { "pressDurationMillis must be positive" }
        require(intervalMillis >= 0) { "intervalMillis must be non-negative" }
    }
}
