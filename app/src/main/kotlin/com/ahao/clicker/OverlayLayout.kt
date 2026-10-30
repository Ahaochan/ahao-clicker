package com.ahao.clicker

/** Returned in play, optional add, move, close order. Coordinates are local to the header. */
internal fun overlayHeaderCells(width: Int, target: Int, controls: Int): List<OverlayRect> {
    val side = minOf(target, width / controls)
    val move = controls - 2
    val moveWidth = width - (controls - 1) * side
    return (0 until controls).map { index ->
        val left = if (index <= move) index * side else width - side
        OverlayRect(left, 0, if (index == move) moveWidth else side, side)
    }
}

internal fun overlayClockScale(width: Float, height: Float, textWidth: Float, textHeight: Float): Float =
    minOf(1f, width / textWidth, height / textHeight).coerceAtLeast(0f)
