package com.ahao.clicker.core

fun interface CancelToken { fun cancel() }
fun interface DelayScheduler { fun after(delayMillis: Long, action: () -> Unit): CancelToken }
fun interface GestureDriver { fun tap(step: ClickStep, result: (Boolean) -> Unit): Boolean }

data class ClickStep(val point: TapPoint, val waitBeforeMillis: Long = 100, val pressMillis: Long = 40) {
    init {
        require(waitBeforeMillis in 0..86_400_000)
        require(pressMillis in 1..60_000)
    }
}

class ClickPlan(steps: List<ClickStep>, val count: Int, val startMillis: Long, val endMillis: Long) {
    val steps: List<ClickStep> = steps.toList()
    init {
        require(steps.size in 1..500)
        require(count > 0)
        require(startMillis >= 0 && endMillis > startMillis)
    }
}

enum class RunPhase { IDLE, WAITING, RUNNING, COMPLETED, STOPPED, EXPIRED, FAILED }
data class RunStatus(val phase: RunPhase = RunPhase.IDLE, val completed: Int = 0, val target: Int = 0)

/** All calls, callbacks and scheduler actions must execute on the same serial thread. */
class ClickRunner(
    private val now: () -> Long,
    private val scheduler: DelayScheduler,
    private val driver: GestureDriver,
) {
    var status = RunStatus()
        private set
    private var generation = 0L
    private var flight = 0L
    private var awaiting = false
    private var next: CancelToken? = null
    private var deadline: CancelToken? = null

    fun start(plan: ClickPlan) {
        stop()
        status = RunStatus(RunPhase.WAITING, 0, plan.count)
        if (now() >= plan.endMillis) { finish(RunPhase.EXPIRED); return }
        val run = generation
        deadline = scheduler.after(plan.endMillis - now()) {
            if (run == generation) finish(RunPhase.EXPIRED)
        }
        if (now() >= plan.startMillis) dispatch(plan, run)
        else next = scheduler.after(plan.startMillis - now()) { dispatch(plan, run) }
    }

    fun stop() { finish(RunPhase.STOPPED) }

    private fun finish(phase: RunPhase) {
        generation++
        awaiting = false
        next?.cancel()
        deadline?.cancel()
        next = null
        deadline = null
        status = status.copy(phase = phase)
    }

    private fun dispatch(plan: ClickPlan, run: Long) {
        if (run != generation) return
        val step = plan.steps[status.completed % plan.steps.size]
        if (plan.endMillis - now() < step.pressMillis) { finish(RunPhase.EXPIRED); return }
        status = status.copy(phase = RunPhase.RUNNING)
        awaiting = true
        val gesture = ++flight
        next = scheduler.after(step.pressMillis + 2_000) {
            if (run == generation && awaiting && gesture == flight) finish(RunPhase.FAILED)
        }
        try {
            val accepted = driver.tap(step) { success ->
                if (run != generation || gesture != flight || !awaiting) return@tap
                awaiting = false
                next?.cancel()
                if (!success) { finish(RunPhase.FAILED); return@tap }
                if (now() >= plan.endMillis) { finish(RunPhase.EXPIRED); return@tap }
                status = status.copy(completed = status.completed + 1)
                if (status.completed == plan.count) { finish(RunPhase.COMPLETED); return@tap }
                val delay = plan.steps[status.completed % plan.steps.size].waitBeforeMillis
                next = scheduler.after(delay) { dispatch(plan, run) }
            }
            if (!accepted && run == generation && awaiting) finish(RunPhase.FAILED)
        } catch (_: Exception) {
            if (run == generation) finish(RunPhase.FAILED)
        }
    }
}
