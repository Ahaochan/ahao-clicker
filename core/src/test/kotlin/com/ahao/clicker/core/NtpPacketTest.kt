package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class NtpPacketTest {
    private val epoch = 1_792_900_000_000L
    private fun reply(request: ByteArray): ByteArray = ByteArray(48).apply {
        this[0] = 0x24; this[1] = 2
        request.copyInto(this, 24, 40, 48)
        NtpPacket.writeTimestamp(this, 32, epoch + 20)
        NtpPacket.writeTimestamp(this, 40, epoch + 30)
    }

    @Test fun `sample removes server processing time and compensates network delay`() {
        val request = NtpPacket.request(epoch)
        val sample = NtpPacket.receive(request, reply(request), 48, 100, 200, epoch)
        assertEquals(epoch + 75, sample.epochAtReceipt)
        assertEquals(90L, sample.networkRoundTripMillis)
    }

    @Test fun `reject short unsynchronized kiss-of-death wrong-mode and unmatched replies`() {
        val request = NtpPacket.request(epoch)
        val invalid = listOf(
            reply(request).apply { this[0] = 0xe4.toByte() },
            reply(request).apply { this[1] = 0 },
            reply(request).apply { this[0] = 0x23 },
            reply(request).apply { this[24] = (this[24].toInt() xor 1).toByte() },
            reply(request).apply { fill(0, 40, 48) },
        )
        invalid.forEach { packet -> assertThrows(IllegalArgumentException::class.java) {
            NtpPacket.receive(request, packet, 48, 100, 200, epoch)
        } }
        assertThrows(IllegalArgumentException::class.java) {
            NtpPacket.receive(request, reply(request), 47, 100, 200, epoch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NtpPacket.receive(request, reply(request), 48, 100, 3_101, epoch)
        }
    }

    @Test fun `era rollover does not turn a 2040 sample into a 1904 timestamp`() {
        val future = java.time.Instant.parse("2040-01-01T00:00:00Z").toEpochMilli()
        val request = NtpPacket.request(future)
        val packet = reply(request).apply {
            NtpPacket.writeTimestamp(this, 32, future)
            NtpPacket.writeTimestamp(this, 40, future)
        }
        assertEquals(future + 5, NtpPacket.receive(request, packet, 48, 0, 10, future).epochAtReceipt)
    }

    @Test fun `built-in configs are bounded and have stable provider identity`() {
        assertEquals(5, BuiltinTimeSource.entries.size)
        BuiltinTimeSource.entries.forEach {
            assertEquals(it, TimeSourceConfig.builtin(it, 60_000).builtin)
        }
        assertEquals(1_000L, TimeSourceConfig.builtin(BuiltinTimeSource.ALIYUN, 1_000).intervalMillis)
    }
}
