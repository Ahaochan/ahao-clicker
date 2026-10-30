package com.ahao.clicker.core

import kotlin.math.abs

/** Serial-thread owner. Absolute deadlines, bounded preparation and one consumed attempt. */
class OneShotRunner(
    private val elapsed: () -> Long,
    private val scheduler: DelayScheduler,
    private val driver: GestureDriver,
    private val preparationMillis: Long = 0,
    private val prepare: () -> Unit = {},
    private val pressMillis: Long = 40,
) {
    init { require(preparationMillis in 0..1_000 && pressMillis in 1..60_000) }
    var status = RunStatus()
        private set
    var reference: TimeReference? = null
        private set
    var targetMillis = 0L
        private set
    /** Existing validation clock read, retained for passive acceptance (not scheduling). */
    var armedElapsed = 0L
        private set
    val deadlineElapsed: Long get() = reference?.let { targetMillis - it.epochMillis + it.elapsedMillis } ?: 0L
    private var generation = 0L
    private var pending: CancelToken? = null
    private var point: (() -> TapPoint)? = null
    private var prepared = false

    fun arm(point: TapPoint, target: Long, clock: TimeReference) = arm({ point }, target, clock)

    fun arm(point: () -> TapPoint, target: Long, clock: TimeReference) {
        val armedAt = if (clock.synced) elapsed() else 0L
        require(clock.synced && target > clock.now(armedAt))
        stop()
        armedElapsed = armedAt
        reference = clock; targetMillis = target; this.point = point; prepared = false
        status = RunStatus(RunPhase.WAITING, 0, 1)
        TimingObservation.emit(TimingObservation.ARM, targetMillis, deadlineElapsed, clock.epochMillis, clock.elapsedMillis)
        schedulePreparation()
    }

    /** Only newer samples of the already-selected source may be supplied by the platform owner. */
    fun rebase(clock: TimeReference): Boolean {
        val old = reference ?: return false
        val now = elapsed()
        if (TimingObservation.observer != null) TimingObservation.emit(TimingObservation.REBASE_CHECK,
            clock.epochMillis, clock.elapsedMillis, deadlineElapsed, status.phase.ordinal.toLong())
        if (status.phase != RunPhase.WAITING || prepared || !clock.synced ||
            clock.elapsedMillis <= old.elapsedMillis || clock.elapsedMillis > now ||
            targetMillis - old.now(now) <= FREEZE_MILLIS) return false
        val remaining = targetMillis - clock.now(now)
        if (remaining <= 0) { finish(RunPhase.EXPIRED); return false }
        if (remaining <= FREEZE_MILLIS || abs(clock.now(now) - old.now(now)) > MAX_CORRECTION_MILLIS) {
            finish(RunPhase.FAILED); return false
        }
        val previousDeadline = deadlineElapsed
        reference = clock
        TimingObservation.emit(TimingObservation.REBASE_ACCEPT, previousDeadline, deadlineElapsed, clock.epochMillis, clock.elapsedMillis)
        schedulePreparation()
        return true
    }

    private fun queue(delay: Long, action: () -> Unit) {
        val token = ++generation
        pending?.cancel()
        TimingObservation.emit(TimingObservation.QUEUED, delay, token, deadlineElapsed)
        pending = scheduler.after(delay.coerceAtLeast(0)) {
            TimingObservation.emit(TimingObservation.QUEUE_ENTER, token, generation, status.phase.ordinal.toLong())
            if (token == generation && status.phase == RunPhase.WAITING) action()
            else TimingObservation.emit(TimingObservation.STALE_CALLBACK, token, generation)
        }
    }
    private fun schedulePreparation() {
        if (preparationMillis == 0L) { scheduleDispatch(); return }
        queue(deadlineElapsed - elapsed() - preparationMillis) {
            val remaining = deadlineElapsed - elapsed()
            if (remaining > preparationMillis) { schedulePreparation(); return@queue }
            if (-remaining > MAX_LATENESS_MILLIS) { finish(RunPhase.EXPIRED); return@queue }
            try {
                prepared = true
                TimingObservation.emit(TimingObservation.PREPARE_BEGIN, deadlineElapsed, remaining)
                prepare()
                TimingObservation.emit(TimingObservation.PREPARE_END, deadlineElapsed)
                if (status.phase == RunPhase.WAITING) scheduleDispatch()
            } catch (error: Exception) {
                TimingObservation.failed(TimingObservation.PREPARE_FAILED, error)
                finish(RunPhase.FAILED)
            }
        }
    }
    private fun scheduleDispatch() {
        queue(deadlineElapsed - elapsed()) {
            val late = elapsed() - deadlineElapsed
            TimingObservation.emit(TimingObservation.DEADLINE_ENTER, deadlineElapsed, late)
            if (late < 0) { scheduleDispatch(); return@queue }
            if (late > MAX_LATENESS_MILLIS) { finish(RunPhase.EXPIRED); return@queue }
            status = status.copy(phase = RunPhase.RUNNING)
            val token = ++generation
            pending = scheduler.after(pressMillis + 2_000) {
                TimingObservation.emit(TimingObservation.WATCHDOG, token, generation)
                if (token == generation && status.phase == RunPhase.RUNNING) finish(RunPhase.FAILED)
            }
            try {
                TimingObservation.emit(TimingObservation.POINT_BEGIN)
                val resolved = checkNotNull(point).invoke()
                TimingObservation.emit(TimingObservation.POINT_END, resolved.x.toRawBits().toLong(), resolved.y.toRawBits().toLong())
                TimingObservation.emit(TimingObservation.DRIVER_BEGIN, deadlineElapsed)
                val accepted = driver.tap(ClickStep(resolved, 0, pressMillis)) { success ->
                    TimingObservation.emit(TimingObservation.DRIVER_CALLBACK, if (success) 1 else 0, token, generation)
                    if (token != generation || status.phase != RunPhase.RUNNING) return@tap
                    status = status.copy(completed = if (success) 1 else 0)
                    finish(if (success) RunPhase.COMPLETED else RunPhase.FAILED)
                }
                TimingObservation.emit(TimingObservation.DRIVER_RETURN, if (accepted) 1 else 0)
                if (!accepted && token == generation) finish(RunPhase.FAILED)
            } catch (error: Exception) {
                TimingObservation.failed(TimingObservation.DRIVER_FAILED, error)
                finish(RunPhase.FAILED)
            }
        }
    }

    fun stop() = finish(RunPhase.STOPPED)
    private fun finish(phase: RunPhase) {
        TimingObservation.emit(TimingObservation.TERMINAL, phase.ordinal.toLong(), status.completed.toLong(), deadlineElapsed, generation)
        generation++
        pending?.cancel(); pending = null; point = null
        status = status.copy(phase = phase)
    }

    companion object {
        const val FREEZE_MILLIS = 5_000L
        const val MAX_CORRECTION_MILLIS = 1_000L
        // A fail-closed dispatch window, not an accuracy guarantee.
        const val MAX_LATENESS_MILLIS = 250L
    }
}
