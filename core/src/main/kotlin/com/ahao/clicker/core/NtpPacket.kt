package com.ahao.clicker.core

import kotlin.math.abs
import kotlin.math.roundToLong

data class NtpSample(val epochAtReceipt: Long, val networkRoundTripMillis: Long)

/** Minimal unicast SNTP codec (RFC 5905). Networking and cancellation belong to app. */
object NtpPacket {
    private const val UNIX_OFFSET = 2_208_988_800L
    private const val ERA = 4_294_967_296L
    fun request(epochMillis: Long): ByteArray = ByteArray(48).apply {
        this[0] = 0x23 // version 4, client mode
        writeTimestamp(this, 40, epochMillis)
    }
    fun writeTimestamp(packet: ByteArray, offset: Int, epochMillis: Long) {
        val seconds = Math.floorDiv(epochMillis, 1_000) + UNIX_OFFSET
        val fraction = Math.floorMod(epochMillis, 1_000) * ERA / 1_000
        for (i in 0..3) {
            packet[offset + i] = (seconds ushr (24 - i * 8)).toByte()
            packet[offset + 4 + i] = (fraction ushr (24 - i * 8)).toByte()
        }
    }
    fun receive(request: ByteArray, reply: ByteArray, size: Int, sent: Long, received: Long, deviceEpoch: Long): NtpSample {
        require(size >= 48 && size <= reply.size && request.size >= 48)
        val header = reply[0].toInt() and 255
        require(header ushr 6 != 3 && header and 7 == 4 && (header ushr 3 and 7) in 3..4)
        require((reply[1].toInt() and 255) in 1..15) // Reject unsynchronized and Kiss-o'-Death.
        require((0..7).all { reply[24 + it] == request[40 + it] })
        val rtt = received - sent
        require(sent >= 0 && rtt in 0..2_000)
        val receiveTime = timestamp(reply, 32, deviceEpoch)
        val transmitTime = timestamp(reply, 40, deviceEpoch)
        val processing = transmitTime - receiveTime
        require(processing in 0..rtt)
        val network = rtt - processing
        val epoch = transmitTime + network / 2
        require(epoch in 1..253_402_300_799_999)
        return NtpSample(epoch, network)
    }
    private fun timestamp(packet: ByteArray, offset: Int, near: Long): Long {
        fun word(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (packet[at + i].toLong() and 255) }
        val seconds = word(offset)
        val fraction = word(offset + 4)
        require(seconds != 0L || fraction != 0L)
        val nearSeconds = Math.floorDiv(near, 1_000) + UNIX_OFFSET
        val era = ((nearSeconds - seconds).toDouble() / ERA).roundToLong()
        val epoch = (seconds + era * ERA - UNIX_OFFSET) * 1_000 + (fraction * 1_000 + ERA / 2) / ERA
        require(abs(epoch - near) < ERA / 2 * 1_000)
        return epoch
    }
}
