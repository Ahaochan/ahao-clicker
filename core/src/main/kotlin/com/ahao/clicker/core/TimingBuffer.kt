package com.ahao.clicker.core

/** Fixed 64-byte records; consumed slots may be reused, unread slots are never overwritten. */
class TimingBuffer(private val capacity: Int = 31_744) {
    init { require(capacity in 1..31_744) }
    private val values = LongArray(capacity * 8)
    private var read = 0L
    private var written = 0L
    private var sequence = 0L
    @Volatile var dropped = 0L
        private set
    val size: Int @Synchronized get() = (written - read).toInt()

    @Synchronized fun record(elapsedNs: Long, tid: Long, event: Int,
        a: Long = 0, b: Long = 0, c: Long = 0, d: Long = 0, attempt: Long = 0): Boolean {
        sequence++
        if (written - read >= capacity) { dropped++; return false }
        val at = (written % capacity).toInt() * 8
        values[at] = sequence; values[at + 1] = elapsedNs; values[at + 2] = tid
        values[at + 3] = (event.toLong() and 0xffffffffL) or (attempt shl 32)
        values[at + 4] = a; values[at + 5] = b
        values[at + 6] = c; values[at + 7] = d
        written++
        return true
    }

    @Synchronized fun drain(destination: LongArray): Int {
        require(destination.size >= 8)
        val count = minOf((written - read).toInt(), destination.size / 8)
        repeat(count) { index ->
            val at = ((read + index) % capacity).toInt() * 8
            values.copyInto(destination, index * 8, at, at + 8)
        }
        read += count
        return count
    }
}
