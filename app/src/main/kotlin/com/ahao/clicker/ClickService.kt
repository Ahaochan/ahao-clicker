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
import android.app.AlertDialog
import android.view.WindowManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import com.ahao.clicker.time.TimeSyncManager
import com.ahao.clicker.core.*

class ClickService : AccessibilityService(), GestureDriver {
    companion object {
        var current: ClickService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val timedMain = HandlerCompat.createAsync(Looper.getMainLooper())
    private val scheduler = DelayScheduler { delay, action ->
        val runnable = Runnable(action)
        main.postDelayed(runnable, delay)
        CancelToken { main.removeCallbacks(runnable) }
    }
    private lateinit var overlay: ClickOverlay
    private lateinit var timedOverlay: TimedOverlay
    private lateinit var multiPoint: MultiPointController
    private val recorder = PointRecorder()
    private var runClock = TimeReference(0, 0, false)
    private val runner = ClickRunner({ runClock.now(SystemClock.elapsedRealtime()) }, scheduler, this)
    private val oneShot = OneShotRunner({ SystemClock.elapsedRealtime() }, DelayScheduler { delay, action ->
        val runnable = Runnable {
            TimingObservation.emit(TimingObservation.SCHED_ENTER)
            action()
        }
        val accepted = timedMain.postAtTime(runnable, SystemClock.uptimeMillis() + delay.coerceAtLeast(0))
        TimingObservation.emit(TimingObservation.SCHED_ACCEPT, if (accepted) 1 else 0, delay)
        CancelToken { timedMain.removeCallbacks(runnable) }
    }, GestureDriver(::tapTimed), preparationMillis = 250, prepare = ::prepareTimedInput, pressMillis = 1)
    private enum class TimedMode { NONE, PREPARED, PAUSED, ARMED, RESULT, CLOCK }
    private var timedMode = TimedMode.NONE
    private var preparedTarget = 0L
    private var lastInfoElapsed = 0L
    private var standaloneClock = false
    private var pendingTimedTap: Runnable? = null
    private var pendingMarkerRestore: (() -> Unit)? = null
    private var markerSuspendedElapsed = 0L
    private var timedSource: TimeSyncManager? = null
    private var markerRestore: Runnable? = null
    private var timePickerGeneration = 0L
    private var timePicker: AlertDialog? = null
    private var clickToast: Toast? = null
    private var registered = false
    private var lastDisplayKey = ""
    private val app get() = application as ClickerApp
    val hasActiveTask: Boolean get() = recording || (::multiPoint.isInitialized && multiPoint.active) || timedMode in listOf(TimedMode.ARMED, TimedMode.PREPARED) ||
        runner.status.phase in listOf(RunPhase.WAITING, RunPhase.RUNNING)
    val taskReference: TimeReference? get() = if (timedMode == TimedMode.ARMED) oneShot.reference else null
    val oneShotStatus get() = oneShot.status
    val oneShotArmedElapsed get() = oneShot.armedElapsed
    var oneShotTiming: OneShotTiming? = null
        private set
    private var recording = false
    private var capturePending = false
    private var preparingRecording = false
    private var busy = false
    private var recordingToken = 0L
    private var sizeAtStart = Pair(0, 0)
    private var timeout: Runnable? = null
    private var displayStatus = R.string.status_idle
    val status: RunStatus get() = if (::multiPoint.isInitialized && multiPoint.visible) multiPoint.status
        else if (timedMode == TimedMode.ARMED || timedMode == TimedMode.RESULT) oneShot.status else runner.status

    override fun onServiceConnected() {
        stopAll()
        current = this
        lastDisplayKey = displayKey(this)
        overlay = ClickOverlay(this, { finishRecording() }, { stopAll() }, ::capture)
        timedOverlay = TimedOverlay(this, ::toggleTimedClick, ::persistPoint, { openTimePicker(false) }) {
            displayReference().now(SystemClock.elapsedRealtime())
        }
        multiPoint = MultiPointController(this, main, scheduler, this,
            { app.timeSync.reference().now(SystemClock.elapsedRealtime()) }, ::stopAll)
        if (!registered) {
            ContextCompat.registerReceiver(this, interruptions, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    private val interruptions = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopAll() }
    }

    private fun persistPoint(point: TapPoint) {
        val store = SettingsStore(this)
        val values = mapOf("pointX" to point.x.toString(), "pointY" to point.y.toString(), "pointDisplay" to displayKey(this))
        if (values.any { (key, value) -> store.get(key) != value }) store.save(values)
    }

    fun prepareTimed(point: TapPoint, target: Long) {
        check(!hasActiveTask && !busy) { getString(R.string.task_active) }
        require(point.x < screenSize().first && point.y < screenSize().second) { getString(R.string.error_bounds) }
        require(target > app.timeSync.reference().now(SystemClock.elapsedRealtime())) { getString(R.string.time_past) }
        stopAll()
        preparedTarget = target; timedMode = TimedMode.PREPARED
        sizeAtStart = screenSize()
        timedOverlay.showMarker(point)
        timedOverlay.showPanel(R.string.close_overlay_stop, ::stopAll)
        timedOverlay.showStartTime(target)
        app.observeTime(this, true)
        main.post(timedTick)
    }

    private fun armTimed(): Boolean {
        if (timedMode !in listOf(TimedMode.CLOCK, TimedMode.PREPARED, TimedMode.PAUSED, TimedMode.RESULT)) return false
        try {
            TimingObservation.emit(TimingObservation.ARM_CHECK_BEGIN)
            check(!busy && !recording) { getString(R.string.error_busy) }
            check(!locked() && screenSize() == sizeAtStart) { getString(R.string.error_bounds) }
            check(app.timeSync.hasFreshSample()) { getString(R.string.sync_required) }
            val point = timedOverlay.point()
            require(point.x < sizeAtStart.first && point.y < sizeAtStart.second) { getString(R.string.error_bounds) }
            check(!timedOverlay.covers(point)) { getString(R.string.overlay_overlap) }
            val reference = app.timeSync.reference()
            timedSource = app.timeSync
            require(preparedTarget > reference.now(SystemClock.elapsedRealtime())) { getString(R.string.time_past) }
            val elapsed = SystemClock.elapsedRealtime()
            oneShotTiming = OneShotTiming(preparedTarget, elapsed + preparedTarget - reference.now(elapsed))
            TimingObservation.emit(TimingObservation.ARM_CHECK_END, preparedTarget)
            oneShot.arm({
                timedOverlay.point().also { check(!timedOverlay.covers(it)) { getString(R.string.overlay_overlap) } }
            }, preparedTarget, reference)
            timedOverlay.armed()
            timedMode = TimedMode.ARMED
            lastInfoElapsed = 0L
            return true
        } catch (error: Exception) {
            if (oneShot.status.phase == RunPhase.WAITING || oneShot.status.phase == RunPhase.RUNNING) stopAll()
            notifyClick(getString(R.string.auto_click_start_failed, error.message ?: getString(R.string.invalid_settings)))
            return false
        }
    }

    private fun toggleTimedClick() {
        TimingObservation.emit(TimingObservation.PLAY_ENTER, timedMode.ordinal.toLong())
        if (timedMode == TimedMode.ARMED) {
            oneShot.stop()
            cancelPendingTimedTap(true)
            timedMode = if (standaloneClock) TimedMode.CLOCK else TimedMode.PAUSED
            timedOverlay.setPlaying(false)
            lastInfoElapsed = 0L
            notifyClick(getString(R.string.auto_click_disabled))
            return
        }
        try {
            check(timedMode in listOf(TimedMode.CLOCK, TimedMode.PREPARED, TimedMode.PAUSED, TimedMode.RESULT)) { getString(R.string.task_active) }
            if (standaloneClock) {
                val store = SettingsStore(this)
                preparedTarget = store.get("target").toLongOrNull() ?: 0L
                timedOverlay.showStartTime(preparedTarget.takeIf { it > 0 })
            }
            if (preparedTarget <= app.timeSync.reference().now(SystemClock.elapsedRealtime())) {
                openTimePicker(true)
                return
            }
            if (armTimed()) notifyClick(getString(R.string.auto_click_enabled))
        } catch (error: Exception) {
            notifyClick(getString(R.string.auto_click_start_failed, error.message ?: getString(R.string.invalid_settings)))
        }
    }
    private fun openTimePicker(startAfterSelection: Boolean) {
        if (timePicker != null) return
        if (hasActiveTask || busy || timedMode == TimedMode.NONE) {
            notifyClick(getString(R.string.task_active)); return
        }
        val store = SettingsStore(this)
        val seed = store.get("target").toLongOrNull() ?: app.timeSync.reference().now(SystemClock.elapsedRealtime())
        val generation = ++timePickerGeneration
        timePicker = TimeOfDayPicker.show(this, seed,
            { app.timeSync.reference().now(SystemClock.elapsedRealtime()) },
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            dismissed = { if (generation == timePickerGeneration) { timePicker = null; timePickerGeneration++ } },
            failed = { notifyClick(it.message ?: getString(R.string.invalid_settings)) },
        ) { target ->
            check(generation == timePickerGeneration && current === this && timedMode != TimedMode.NONE && !hasActiveTask && !busy) { getString(R.string.task_active) }
            store.saveTimedTarget(target)
            TimingObservation.emit(TimingObservation.TARGET_SELECTED, target)
            preparedTarget = target
            timedOverlay.showStartTime(target)
            timePicker?.dismiss()
            if (startAfterSelection) toggleTimedClick()
        }
    }

    private fun notifyClick(message: String) {
        clickToast?.cancel()
        clickToast = null
        if (::timedOverlay.isInitialized && timedOverlay.showFeedback(message)) return
        clickToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }
    fun refreshClockTarget() {
        if (standaloneClock && timedMode in listOf(TimedMode.CLOCK, TimedMode.RESULT) || timedMode == TimedMode.PAUSED) {
            val target = SettingsStore(this).get("target").toLongOrNull()?.takeIf { it > 0 }
            if (timedMode == TimedMode.PAUSED) preparedTarget = target ?: 0L
            timedOverlay.showStartTime(target)
        }
    }

    fun showTimedOverlay() {
        if (::multiPoint.isInitialized && multiPoint.visible) {
            check(!busy) { getString(R.string.error_busy) }
            stopAll()
        }
        if (!timedOverlay.isVisible) toggleClock()
    }

    fun showMultiPointOverlay() {
        check(!busy && !recording) { getString(R.string.error_busy) }
        if (multiPoint.visible) return
        stopAll()
        sizeAtStart = screenSize()
        multiPoint.show()
        app.observeTime(this, true)
    }

    fun toggleClock() {
        check(!hasActiveTask && !recording && !busy) { getString(R.string.task_active) }
        if (timedMode == TimedMode.CLOCK) { stopAll(); return }
        stopAll()
        standaloneClock = true
        timedMode = TimedMode.CLOCK
        sizeAtStart = screenSize()
        val store = SettingsStore(this)
        store.save(mapOf("target" to store.timedDefault(app.timeSync.reference().now(SystemClock.elapsedRealtime())).toString()))
        val saved = if (store.get("pointDisplay") == displayKey(this)) runCatching {
            TapPoint(store.get("pointX").toFloat(), store.get("pointY").toFloat()).takeIf {
                it.x < sizeAtStart.first && it.y < sizeAtStart.second
            }
        }.getOrNull() else null
        timedOverlay.showMarker(saved)
        timedOverlay.showPanel(R.string.clock_close, ::stopAll)
        refreshClockTarget()
        app.observeTime(this, true)
        main.post(timedTick)
    }

    // Shared by task status checks and the actual Canvas draw; only a live task freezes its reference.
    private fun displayReference(): TimeReference {
        val phase = oneShot.status.phase
        val reference = if (timedMode == TimedMode.ARMED && (phase == RunPhase.WAITING || phase == RunPhase.RUNNING))
            oneShot.reference ?: app.timeSync.reference() else app.timeSync.reference()
        TimingObservation.emit(TimingObservation.REFERENCE_SELECTED, reference.epochMillis, reference.elapsedMillis,
            if (reference.synced) 1 else 0, timedMode.ordinal.toLong())
        return reference
    }

    private val timedTick = object : Runnable {
        override fun run() {
            if (timedMode == TimedMode.NONE) return
            val elapsed = SystemClock.elapsedRealtime()
            if (timedMode == TimedMode.ARMED && oneShot.status.phase == RunPhase.WAITING) {
                val source = timedSource
                if (source == null || source !== app.timeSync) { stopAll(); return }
                if (source.hasFreshSample() && oneShot.rebase(source.reference())) {
                    oneShotTiming = oneShotTiming?.copy(plannedElapsed = oneShot.deadlineElapsed)
                }
            }
            val now = displayReference().now(elapsed)
            if (elapsed - lastInfoElapsed >= 250) {
                if (locked() || screenSize() != sizeAtStart) { stopAll(); return }
                lastInfoElapsed = elapsed
                if (timedMode == TimedMode.ARMED && oneShot.status.phase !in listOf(RunPhase.WAITING, RunPhase.RUNNING)) {
                    timedMode = TimedMode.RESULT
                    timedOverlay.setPlaying(false)
                    notifyClick(if (oneShot.status.phase == RunPhase.COMPLETED) getString(R.string.click_completed) else statusText())
                }
            }
            val active = timedMode == TimedMode.ARMED &&
                (oneShot.status.phase == RunPhase.WAITING || oneShot.status.phase == RunPhase.RUNNING)
            main.postDelayed(this, ClockRefresh.delayMillis(timedOverlay.clockVisible,
                if (active) preparedTarget - now else null))
        }
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
        app.observeTime(this, true)
        runner.start(plan)
        main.post(monitor)
    }

    fun record() {
        check(!busy) { getString(R.string.error_busy) }
        stopAll()
        recorder.clear()
        recording = true
        capturePending = true
        preparingRecording = true
        sizeAtStart = screenSize()
        displayStatus = R.string.status_recording
        overlay.showCapture()
        overlay.showControls(true)
        app.observeTime(this, true)
        val token = recordingToken
        main.postDelayed({
            if (recording && token == recordingToken) {
                preparingRecording = false
                capturePending = false
                overlay.update(getString(R.string.status_recording))
            }
        }, 600)
        main.post(monitor)
    }

    fun stopAll() {
        TimingObservation.emit(TimingObservation.STOP_ALL)
        runner.stop()
        oneShot.stop()
        cancelPendingTimedTap(false)
        timedMode = TimedMode.NONE
        timedSource = null
        timePickerGeneration++
        timePicker?.dismiss()
        timePicker = null
        standaloneClock = false
        clickToast?.cancel()
        clickToast = null
        lastInfoElapsed = 0L
        markerRestore?.let(main::removeCallbacks)
        markerRestore = null
        main.removeCallbacks(timedTick)
        if (::timedOverlay.isInitialized) timedOverlay.close()
        if (::multiPoint.isInitialized) multiPoint.close()
        app.observeTime(this, false)
        if (recording) persistRecording()
        recording = false
        capturePending = false
        preparingRecording = false
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
        if (!recording || busy || capturePending) return
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

    private fun cancelPendingTimedTap(restore: Boolean) {
        pendingTimedTap?.let(timedMain::removeCallbacks)
        pendingTimedTap = null
        val resume = pendingMarkerRestore
        pendingMarkerRestore = null
        if (restore) resume?.invoke()
    }

    private fun prepareTimedInput() {
        TimingObservation.emit(TimingObservation.INPUT_CHECK_BEGIN)
        check(timedMode == TimedMode.ARMED && !locked() && screenSize() == sizeAtStart) { "timed_input_state" }
        check(timedSource === app.timeSync && app.timeSync.hasFreshSample()) { getString(R.string.sync_required) }
        val point = timedOverlay.point()
        check(point.x < sizeAtStart.first && point.y < sizeAtStart.second && !timedOverlay.covers(point)) { "timed_input_point" }
        TimingObservation.emit(TimingObservation.INPUT_CHECK_END, point.x.toRawBits().toLong(), point.y.toRawBits().toLong())
        markerRestore?.let(main::removeCallbacks)
        markerRestore = null
        TimingObservation.emit(TimingObservation.MARKER_REMOVE_BEGIN)
        pendingMarkerRestore = timedOverlay.suspendMarker()
        TimingObservation.emit(TimingObservation.MARKER_REMOVE_END)
        markerSuspendedElapsed = SystemClock.elapsedRealtime()
    }

    private fun tapTimed(step: ClickStep, result: (Boolean) -> Unit): Boolean {
        val resume = pendingMarkerRestore ?: run {
            TimingObservation.rejected(1); return false
        }
        var settled = false
        fun finish(success: Boolean) {
            if (settled) return
            settled = true
            markerRestore?.let(main::removeCallbacks)
            markerRestore = Runnable { markerRestore = null; resume() }.also { main.postDelayed(it, 500) }
            result(success)
        }
        // Preparation normally settled before the deadline. A late preparation still cannot inject through the marker.
        val inject = Runnable {
            pendingTimedTap = null
            pendingMarkerRestore = null
            if (timedMode != TimedMode.ARMED || oneShot.status.phase != RunPhase.RUNNING) {
                TimingObservation.rejected(2); return@Runnable finish(false)
            }
            val clock = oneShot.reference ?: run {
                TimingObservation.rejected(3); return@Runnable finish(false)
            }
            if (clock.now(SystemClock.elapsedRealtime()) - preparedTarget > OneShotRunner.MAX_LATENESS_MILLIS) {
                TimingObservation.rejected(4)
                finish(false); return@Runnable
            }
            if (!tap(step, ::finish)) finish(false)
        }
        val remaining = markerSuspendedElapsed + 150 - SystemClock.elapsedRealtime()
        if (remaining <= 0) inject.run()
        else {
            pendingTimedTap = inject
            timedMain.postAtTime(inject, SystemClock.uptimeMillis() + remaining)
        }
        return true
    }

    override fun tap(step: ClickStep, result: (Boolean) -> Unit): Boolean {
        TimingObservation.emit(TimingObservation.GESTURE_CHECK_BEGIN)
        if (busy || locked()) { TimingObservation.rejected(5); return false }
        val size = screenSize()
        if (size != sizeAtStart) { TimingObservation.rejected(6); return false }
        if (step.point.x >= size.first || step.point.y >= size.second) {
            TimingObservation.rejected(7); return false
        }
        TimingObservation.emit(TimingObservation.GESTURE_CHECK_END)
        TimingObservation.emit(TimingObservation.GESTURE_BUILD_BEGIN)
        val path = Path().apply { moveTo(step.point.x, step.point.y) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, step.pressMillis),
        ).build()
        TimingObservation.emit(TimingObservation.GESTURE_BUILD_END)
        val timedAttempt = timedMode == TimedMode.ARMED
        val timing = if (timedAttempt) oneShotTiming?.copy(dispatchedElapsed = SystemClock.elapsedRealtime()) else null
        if (timedAttempt) oneShotTiming = timing
        busy = true
        var settled = false
        val watchdog = Runnable {
            TimingObservation.emit(TimingObservation.PLATFORM_WATCHDOG)
            if (!settled) { settled = true; busy = false; result(false) }
        }
        fun done(success: Boolean) {
            TimingObservation.emit(TimingObservation.GESTURE_CALLBACK, if (success) 1 else 0, if (settled) 1 else 0)
            if (settled) return
            settled = true
            main.removeCallbacks(watchdog)
            busy = false
            if (timedAttempt && oneShotTiming === timing) oneShotTiming = timing?.copy(callbackElapsed = SystemClock.elapsedRealtime())
            result(success)
        }
        main.postDelayed(watchdog, step.pressMillis + 2_000)
        return try {
            TimingObservation.emit(TimingObservation.DISPATCH_API_BEGIN)
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { done(true) }
                override fun onCancelled(gestureDescription: GestureDescription) { done(false) }
            }, main)
            TimingObservation.emit(TimingObservation.DISPATCH_API_END, if (accepted) 1 else 0)
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
                app.observeTime(this@ClickService, false)
                return
            }
            overlay.update(if (recording) recordingStatus() else getString(
                R.string.progress, state.completed, state.target,
            ))
            main.postDelayed(this, 250)
        }
    }

    private fun recordingStatus(): String = getString(when {
        preparingRecording -> R.string.record_preparing
        capturePending -> R.string.record_pending
        else -> R.string.status_recording
    })
    fun statusText(): String = if (::multiPoint.isInitialized && multiPoint.visible) when (multiPoint.status.phase) {
        RunPhase.WAITING, RunPhase.RUNNING -> getString(R.string.multi_running)
        RunPhase.FAILED -> getString(R.string.status_failed)
        else -> getString(R.string.status_idle)
    } else if (recording) recordingStatus()
    else if (timedMode == TimedMode.ARMED || timedMode == TimedMode.RESULT) getString(when (oneShot.status.phase) {
        RunPhase.WAITING -> R.string.status_waiting
        RunPhase.RUNNING -> R.string.status_running
        RunPhase.COMPLETED -> R.string.one_completed
        RunPhase.EXPIRED -> R.string.one_expired
        RunPhase.FAILED -> R.string.status_failed
        else -> R.string.status_idle
    }) else when (runner.status.phase) {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val key = displayKey(this)
        if (key == lastDisplayKey) return
        lastDisplayKey = key
        stopAll()
        val store = SettingsStore(this)
        if (store.get("pointDisplay") != key) store.save(mapOf("pointDisplay" to ""))
    }
    override fun onInterrupt() { stopAll() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onUnbind(intent: Intent?): Boolean { stopAll(); current = null; return super.onUnbind(intent) }
    override fun onDestroy() {
        stopAll(); current = null
        if (registered) { unregisterReceiver(interruptions); registered = false }
        super.onDestroy()
    }
}

data class OneShotTiming(val targetEpoch: Long, val plannedElapsed: Long,
    val dispatchedElapsed: Long? = null, val callbackElapsed: Long? = null)
