package com.ahao.clicker.core

class PointRecorder(private val limit: Int = 500) {
    private val points = ArrayList<ClickStep>()
    private var lastCompleted: Long? = null
    private var pendingAt: Long? = null
    val steps: List<ClickStep> get() = points.toList()
    val completedSteps: List<ClickStep> get() = if (pendingAt == null) points.toList() else points.dropLast(1)

    init { require(limit in 1..500) }

    fun record(point: TapPoint, atMillis: Long) {
        check(pendingAt == null) { "Previous gesture has not completed" }
        require(points.size < limit) { "Recording is full" }
        val wait = lastCompleted?.let { atMillis - it } ?: 0
        require(wait >= 0)
        points.add(ClickStep(point, wait))
        pendingAt = atMillis
    }

    fun completed(atMillis: Long) {
        val pending = checkNotNull(pendingAt)
        require(atMillis >= pending)
        lastCompleted = atMillis
        pendingAt = null
    }

    fun clear() { points.clear(); lastCompleted = null; pendingAt = null }
}
