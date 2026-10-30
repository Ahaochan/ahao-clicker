package com.ahao.clicker

import kotlin.math.roundToInt

internal enum class OverlayPanelKind(val sizeKey: String) {
    TIMED("timedOverlaySize"), MULTI_POINT("multiOverlaySize")
}

internal data class OverlaySize(val widthDp: Float, val heightDp: Float) {
    fun encode(): String = "$widthDp,$heightDp"
    companion object {
        fun decode(value: String): OverlaySize? {
            val parts = value.split(',')
            if (parts.size != 2) return null
            val width = parts[0].toFloatOrNull() ?: return null
            val height = parts[1].toFloatOrNull() ?: return null
            return if (width.isFinite() && height.isFinite() && width > 0 && height > 0)
                OverlaySize(width, height) else null
        }
    }
}

internal fun restoreOverlaySize(size: OverlaySize, density: Float, left: Int, top: Int,
    available: OverlayRect, minimumWidth: Int, minimumHeight: (Int) -> Int): OverlayRect {
    val width = (size.widthDp * density).roundToInt()
        .coerceIn(minimumWidth.coerceAtMost(available.width), available.width)
    val height = (size.heightDp * density).roundToInt()
        .coerceIn(minimumHeight(width).coerceAtMost(available.height), available.height)
    return OverlayRect(left.coerceIn(available.left, available.left + available.width - width),
        top.coerceIn(available.top, available.top + available.height - height), width, height)
}
