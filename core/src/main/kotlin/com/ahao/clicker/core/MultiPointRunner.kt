package com.ahao.clicker.core

object MultiPointSequence {
    const val MAX_POINTS = 50
    const val DEFAULT_DELAY = 1_000L
    fun delay(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it in 0..86_400_000 }
}

/** Serial owner. Reuses a bounded sequence until stopped, without overlapping gestures. */
class MultiPointRunner(
    private val now: () -> Long,
    private val scheduler: DelayScheduler,
    private val driver: GestureDriver,
    private val changed: (RunStatus) -> Unit = {},
) {
    var status = RunStatus()
        private set
    private var steps = emptyList<ClickStep>()
    private var position = 0
    private var generation = 0L
    private var wake = 0L
    private var flight = 0L
    private var awaiting = false
    private var next: CancelToken? = null
    private var watchdog: CancelToken? = null

    fun start(steps: List<ClickStep>, inputReadyAt: Long = now()) {
        require(steps.size in 1..MultiPointSequence.MAX_POINTS)
        invalidate()
        val run = generation
        val started = now()
        this.steps = steps.toList()
        position = 0
        status = RunStatus(RunPhase.WAITING, 0, steps.size)
        changed(status)
        if (run == generation) schedule(maxOf(started + steps.first().waitBeforeMillis, inputReadyAt), run)
    }
    fun stop() = finish(RunPhase.STOPPED)

    private fun invalidate() {
        generation++; wake++; awaiting = false
        next?.cancel(); watchdog?.cancel()
        next = null; watchdog = null
    }
    private fun finish(phase: RunPhase) {
        invalidate()
        steps = emptyList()
        status = status.copy(phase = phase)
        changed(status)
    }
    private fun schedule(deadline: Long, run: Long) {
        if (run != generation) return
        val token = ++wake
        next?.cancel()
        next = scheduler.after((deadline - now()).coerceAtLeast(0)) {
            if (run != generation || token != wake || awaiting) return@after
            if (now() < deadline) schedule(deadline, run) else dispatch(run)
        }
    }
    private fun dispatch(run: Long) {
        if (run != generation || awaiting) return
        wake++
        val step = steps[position]
        val started = now()
        val attempt = ++flight
        awaiting = true
        status = status.copy(phase = RunPhase.RUNNING)
        changed(status)
        if (run != generation) return
        watchdog = scheduler.after(step.pressMillis + 2_000) {
            if (run == generation && attempt == flight && awaiting) finish(RunPhase.FAILED)
        }
        try {
            val accepted = driver.tap(step) { success ->
                if (run != generation || attempt != flight || !awaiting) return@tap
                awaiting = false
                watchdog?.cancel(); watchdog = null
                if (!success) { finish(RunPhase.FAILED); return@tap }
                position = (position + 1) % steps.size
                val delay = steps[position].waitBeforeMillis.coerceAtLeast(1)
                status = status.copy(phase = RunPhase.WAITING,
                    completed = if (status.completed < Int.MAX_VALUE) status.completed + 1 else Int.MAX_VALUE)
                changed(status)
                if (run == generation) schedule(started + delay, run)
            }
            if (!accepted && run == generation && attempt == flight && awaiting) finish(RunPhase.FAILED)
        } catch (_: Exception) {
            if (run == generation && attempt == flight && awaiting) finish(RunPhase.FAILED)
        }
    }
}
