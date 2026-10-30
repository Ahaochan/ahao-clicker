package com.ahao.clicker.core

import org.junit.Assert.*
import org.junit.Test

class TimingBufferTest {
    @Test fun `records retain sequence timestamp thread code and payload when drained`() {
        val buffer = TimingBuffer(2)
        assertTrue(buffer.record(100, 7, 10, 1, 2, 3, 4))
        val out = LongArray(16)
        assertEquals(1, buffer.drain(out))
        assertArrayEquals(longArrayOf(1, 100, 7, 10, 1, 2, 3, 4), out.copyOf(8))
        assertEquals(0, buffer.drain(out))
    }

    @Test fun `overflow is counted instead of replacing a live record and later sequence exposes the gap`() {
        val buffer = TimingBuffer(2)
        buffer.record(100, 7, 10); buffer.record(200, 7, 20)
        assertFalse(buffer.record(300, 7, 30))
        assertEquals(1L, buffer.dropped)
        val out = LongArray(16)
        assertEquals(2, buffer.drain(out)); assertEquals(1L, out[0]); assertEquals(2L, out[8])
        assertTrue(buffer.record(400, 7, 40))
        assertEquals(1, buffer.drain(out)); assertEquals(4L, out[0]); assertEquals(400L, out[1])
    }

    @Test fun `partial drains and slot reuse preserve FIFO order`() {
        val buffer = TimingBuffer(2)
        buffer.record(10, 1, 10); buffer.record(20, 1, 20)
        val out = LongArray(8)
        assertEquals(1, buffer.drain(out)); assertEquals(10L, out[3])
        buffer.record(30, 1, 30)
        assertEquals(1, buffer.drain(out)); assertEquals(20L, out[3])
        assertEquals(1, buffer.drain(out)); assertEquals(30L, out[3])
        assertEquals(0, buffer.size)
    }

    @Test fun `invalid capacity and undersized drain cannot silently lose records`() {
        assertThrows(IllegalArgumentException::class.java) { TimingBuffer(0) }
        val buffer = TimingBuffer(1); buffer.record(10, 1, 1)
        assertThrows(IllegalArgumentException::class.java) { buffer.drain(LongArray(7)) }
        assertEquals(1, buffer.size)
    }
}
