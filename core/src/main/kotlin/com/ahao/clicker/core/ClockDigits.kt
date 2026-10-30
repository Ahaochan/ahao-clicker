package com.ahao.clicker.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MillisecondDigits {
    fun value(hundreds: Int, tens: Int, units: Int): Int {
        require(hundreds in 0..9 && tens in 0..9 && units in 0..9)
        return hundreds * 100 + tens * 10 + units
    }
}

object ClockDigits {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss:SSS")
    fun format(epoch: Long, zone: ZoneId): String = formatter.format(Instant.ofEpochMilli(epoch).atZone(zone))
}
