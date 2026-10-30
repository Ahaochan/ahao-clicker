package com.ahao.clicker.core

/** Bounded provider state; all accesses use the manager's serial lock. */
class SyncPacing {
    private val attempts = LongArray(BuiltinTimeSource.entries.size) { -1 }
    fun delay(source: BuiltinTimeSource?, elapsed: Long): Long {
        val last = source?.let { attempts[it.ordinal] } ?: return 0
        return if (last < 0) 0 else (source.minimumIntervalMillis - (elapsed - last)).coerceAtLeast(0)
    }
    fun attempted(source: BuiltinTimeSource?, elapsed: Long) {
        if (source != null) attempts[source.ordinal] = elapsed
    }
}
