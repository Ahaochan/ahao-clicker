package com.ahao.clicker

internal data class OverlayRect(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun resizeOverlay(start: OverlayRect, available: OverlayRect, dx: Int, dy: Int,
    fromLeft: Boolean, minimumWidth: Int, minimumHeight: Int): OverlayRect {
    val minWidth = minimumWidth.coerceAtMost(available.width)
    val minHeight = minimumHeight.coerceAtMost(available.height)
    val right = available.left + available.width
    val bottom = available.top + available.height
    val top = start.top.coerceIn(available.top, bottom - minHeight)
    val anchor = if (fromLeft) (start.left + start.width).coerceIn(available.left + minWidth, right)
        else start.left.coerceIn(available.left, right - minWidth)
    val width = (start.width.toLong() + if (fromLeft) -dx.toLong() else dx.toLong())
        .coerceIn(minWidth.toLong(), (if (fromLeft) anchor - available.left else right - anchor).toLong()).toInt()
    val height = (start.height.toLong() + dy).coerceIn(minHeight.toLong(), (bottom - top).toLong()).toInt()
    return OverlayRect(if (fromLeft) anchor - width else anchor, top, width, height)
}
