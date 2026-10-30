package com.ahao.clicker.core

/** Optional measurement boundary. Ordinary applications leave this null. */
fun interface TimingObserver {
    fun record(event: Int, a: Long, b: Long, c: Long, d: Long)
    fun text(event: Int, value: String) = Unit
}

object TimingObservation {
    @Volatile var observer: TimingObserver? = null
    /** Benchmark may install this without observing any successful-path events. */
    @Volatile var failureObserver: TimingObserver? = null
    /** Optional benchmark window identity, set before addView; null in ordinary builds. */
    @Volatile var windowTitle: String? = null
    @Volatile var failures: Long = 0
        private set

    fun emit(event: Int, a: Long = 0, b: Long = 0, c: Long = 0, d: Long = 0) {
        val sink = observer ?: return
        try { sink.record(event, a, b, c, d) }
        catch (_: Exception) { failures++; observer = null }
    }

    /** Failure-only diagnostics; a broken sink must never change task control flow. */
    fun failed(event: Int, error: Exception) {
        failure(event, 0, error)
    }

    fun rejected(reason: Long) = failure(TIMED_REJECT, reason, null)

    private fun failure(event: Int, reason: Long, error: Exception?) {
        val sink = observer ?: failureObserver ?: return
        try {
            sink.record(event, reason, 0, 0, 0)
            if (error != null) sink.text(event, error.toString())
        } catch (_: Exception) {
            failures++
            if (observer === sink) observer = null
            if (failureObserver === sink) failureObserver = null
        }
    }

    const val ARM = 10
    const val REBASE_CHECK = 11
    const val REBASE_ACCEPT = 12
    const val QUEUED = 20
    const val QUEUE_ENTER = 21
    const val STALE_CALLBACK = 22
    const val PREPARE_BEGIN = 30
    const val PREPARE_END = 31
    const val PREPARE_FAILED = 32
    const val DEADLINE_ENTER = 40
    const val POINT_BEGIN = 41
    const val POINT_END = 42
    const val DRIVER_BEGIN = 43
    const val DRIVER_RETURN = 44
    const val DRIVER_CALLBACK = 45
    const val DRIVER_FAILED = 46
    const val WATCHDOG = 50
    const val TERMINAL = 60
    const val PLAY_ENTER = 100
    const val ARM_CHECK_BEGIN = 101
    const val ARM_CHECK_END = 102
    const val INPUT_CHECK_BEGIN = 103
    const val INPUT_CHECK_END = 104
    const val MARKER_REMOVE_BEGIN = 105
    const val MARKER_REMOVE_END = 106
    const val TIMED_REJECT = 107
    const val GESTURE_CHECK_BEGIN = 110
    const val GESTURE_CHECK_END = 111
    const val GESTURE_BUILD_BEGIN = 112
    const val GESTURE_BUILD_END = 113
    const val DISPATCH_API_BEGIN = 114
    const val DISPATCH_API_END = 115
    const val GESTURE_CALLBACK = 116
    const val PLATFORM_WATCHDOG = 117
    const val STOP_ALL = 118
    const val SCHED_ACCEPT = 120
    const val SCHED_ENTER = 121
    const val REFERENCE_SELECTED = 130
    const val TARGET_SELECTED = 140
    const val SYNC_BEGIN = 150
    const val SYNC_CONNECT_BEGIN = 151
    const val SYNC_CONNECT_END = 152
    const val SYNC_RESPONSE = 153
    const val SYNC_ACCEPT = 154
    const val SYNC_ERROR = 155
    const val SYNC_HEADERS = 156
    const val FRAME_BEGIN = 200
    const val FRAME_VALUE = 201
    const val FRAME_END = 202
    const val INVALIDATE = 203
    const val FRAME_REQUEST = 204
}
