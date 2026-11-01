package com.ahao.clicker

/** Conversion boundary for the millisecond picker; does not rewrite saved times. */
internal object TimeWheel {
    const val maximum = 99
    fun millis(position: Int): Int { require(position in 0..maximum); return position * 10 }
    fun position(millis: Int): Int { require(millis in 0..999); return millis / 10 }
    fun label(position: Int): String = millis(position).toString().padStart(3, '0')
}
