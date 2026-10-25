package com.ahao.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.app.KeyguardManager
import com.ahao.clicker.core.*

class ClickService : AccessibilityService(), GestureDriver {
    companion object {
        var current: ClickService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val scheduler = DelayScheduler { delay, action ->
        val runnable = Runnable(action)
        main.postDelayed(runnable, delay)
        CancelToken { main.removeCallbacks(runnable) }
    }
    private lateinit var overlay: ClickOverlay
    private val recorder = PointRecorder()
    private var runClock = TimeReference(0, 0, false)
    private val runner = ClickRunner({ runClock.now(SystemClock.elapsedRealtime()) }, scheduler, this)
    private var recording = false
    private var capturePending = false
    private var busy = false
    private var recordingToken = 0L
    private var sizeAtStart = Pair(0, 0)
    private var timeout: Runnable? = null
    private var displayStatus = R.string.status_idle
    val status: RunStatus get() = runner.status

    override fun onServiceConnected() {
        stopAll()
        current = this
        overlay = ClickOverlay(this, { finishRecording() }, { stopAll() }, ::capture)
    }

    fun startTask(steps: List<ClickStep>, count: Int, start: Long, end: Long) {
        check(!busy) { getString(R.string.error_busy) }
        stopAll()
        val size = screenSize()
        require(steps.all { it.point.x < size.first && it.point.y < size.second }) { getString(R.string.error_bounds) }
        runClock = (application as ClickerApp).timeSync.reference()
        require(end > runClock.now(SystemClock.elapsedRealtime())) { getString(R.string.error_window) }
        val plan = ClickPlan(steps, count, start, end)
        sizeAtStart = size
        displayStatus = R.string.status_running
        overlay.showControls(false)
        runner.start(plan)
        main.post(monitor)
    }

    fun record() {
        check(!busy) { getString(R.string.error_busy) }
        stopAll()
        recorder.clear()
        recording = true
        sizeAtStart = screenSize()
        displayStatus = R.string.status_recording
        overlay.showCapture()
        overlay.showControls(true)
        main.post(monitor)
    }

    fun stopAll() {
        runner.stop()
        if (recording) persistRecording()
        recording = false
        capturePending = false
        recordingToken++
        timeout?.let(main::removeCallbacks)
        timeout = null
        main.removeCallbacks(monitor)
        if (::overlay.isInitialized) overlay.close()
        displayStatus = R.string.status_idle
    }

    private fun finishRecording() {
        if (busy) return
        stopAll()
    }

    private fun persistRecording() {
        val steps = recorder.completedSteps
        if (steps.isNotEmpty()) SettingsStore(this).save(mapOf(
            "steps" to StepText.format(steps), "count" to steps.size.toString(),
            "recordedAt" to SystemClock.elapsedRealtime().toString(),
        ))
    }

    private fun capture(point: TapPoint) {
        if (!recording || busy) return
        val token = ++recordingToken
        capturePending = true
        overlay.update(getString(R.string.record_pending))
        overlay.suspendCapture {
            if (!recording || token != recordingToken) return@suspendCapture
            try { recorder.record(point, SystemClock.elapsedRealtime()) }
            catch (_: Exception) { recordingFailure(); return@suspendCapture }
            var settled = false
            fun finish(success: Boolean) {
                if (settled || !recording || token != recordingToken) return
                settled = true
                timeout?.let(main::removeCallbacks)
                timeout = null
                if (success) {
                    recorder.completed(SystemClock.elapsedRealtime())
                    persistRecording()
                    // Completion means UP was queued, not that the target consumed it.
                    // Keep the capture window untouchable while the input dispatcher drains that UP.
                    main.postDelayed({
                        if (recording && token == recordingToken) {
                            capturePending = false
                            overlay.showCapture()
                            overlay.showControls(true)
                            overlay.update(getString(R.string.status_recording))
                        }
                    }, 500)
                } else recordingFailure()
            }
            timeout = Runnable { finish(false) }.also { main.postDelayed(it, 2_040) }
            if (!tap(ClickStep(point, 0), ::finish)) finish(false)
        }
    }

    private fun recordingFailure() {
        // Do not save a point whose injected click did not complete.
        val successful = recorder.completedSteps
        SettingsStore(this).save(mapOf("steps" to StepText.format(successful),
            "count" to successful.size.toString(), "recordedAt" to SystemClock.elapsedRealtime().toString()))
        recording = false
        stopAll()
        displayStatus = R.string.status_failed
    }

    override fun tap(step: ClickStep, result: (Boolean) -> Unit): Boolean {
        if (busy || locked()) return false
        val size = screenSize()
        if (size != sizeAtStart) return false
        if (step.point.x >= size.first || step.point.y >= size.second) return false
        val path = Path().apply { moveTo(step.point.x, step.point.y) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, step.pressMillis),
        ).build()
        busy = true
        var settled = false
        val watchdog = Runnable {
            if (!settled) { settled = true; busy = false; result(false) }
        }
        fun done(success: Boolean) {
            if (settled) return
            settled = true
            main.removeCallbacks(watchdog)
            busy = false
            result(success)
        }
        main.postDelayed(watchdog, step.pressMillis + 2_000)
        return try {
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { done(true) }
                override fun onCancelled(gestureDescription: GestureDescription) { done(false) }
            }, main)
            if (!accepted) { settled = true; main.removeCallbacks(watchdog); busy = false }
            accepted
        } catch (_: Exception) { done(false); false }
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (locked() || screenSize() != sizeAtStart) { stopAll(); return }
            val state = runner.status
            if (!recording && state.phase != RunPhase.RUNNING && state.phase != RunPhase.WAITING) {
                overlay.close()
                return
            }
            overlay.update(if (recording) getString(if (capturePending) R.string.record_pending else displayStatus) else getString(
                R.string.progress, state.completed, state.target,
            ))
            main.postDelayed(this, 250)
        }
    }

    fun statusText(): String = if (recording) getString(if (capturePending) R.string.record_pending else R.string.status_recording) else when (runner.status.phase) {
        RunPhase.IDLE, RunPhase.STOPPED -> getString(displayStatus)
        RunPhase.WAITING -> getString(R.string.status_waiting)
        RunPhase.RUNNING -> getString(R.string.progress, status.completed, status.target)
        RunPhase.COMPLETED -> getString(R.string.status_completed, status.completed)
        RunPhase.EXPIRED -> getString(R.string.status_expired, status.completed)
        RunPhase.FAILED -> getString(R.string.status_failed)
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
        !getSystemService(android.os.PowerManager::class.java).isInteractive
    private fun screenSize(): Pair<Int, Int> {
        @Suppress("DEPRECATION")
        val point = android.graphics.Point().also { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealSize(it) }
        return point.x to point.y
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); stopAll() }
    override fun onInterrupt() { stopAll() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onUnbind(intent: Intent?): Boolean { stopAll(); current = null; return super.onUnbind(intent) }
    override fun onDestroy() { stopAll(); current = null; super.onDestroy() }
}
