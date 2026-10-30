package com.ahao.clicker

import android.app.AlertDialog
import android.os.Handler
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import com.ahao.clicker.core.*

/** Owns an editable draft and a user-controlled loop, all on the service main thread. */
class MultiPointController(private val service: ClickService, private val main: Handler,
    scheduler: DelayScheduler, driver: GestureDriver, readTime: () -> Long, dismiss: () -> Unit) {
    private val store = SettingsStore(service)
    private var dialog: AlertDialog? = null
    private var restore: Runnable? = null
    private var session = 0L
    private var closing = false
    private val overlay = MultiPointOverlay(service, ::toggle, ::addPoint, ::editPoint, ::persist, dismiss, readTime)
    private val runner = MultiPointRunner({ SystemClock.elapsedRealtime() }, scheduler,
        GestureDriver { step, result -> if (overlay.covers(step.point)) false else driver.tap(step, result) }, ::statusChanged)
    val visible get() = overlay.isVisible
    val status get() = runner.status
    val active get() = status.phase == RunPhase.WAITING || status.phase == RunPhase.RUNNING

    fun show() {
        session++
        val size = screenSize(service)
        val saved = if (store.get("multiPointDisplay") == displayKey(service)) runCatching {
            val text = store.get("multiPoints")
            (if (text.isEmpty()) emptyList() else StepText.parse(text)).also { steps ->
                require(steps.size <= MultiPointSequence.MAX_POINTS && steps.all { it.point.x < size.first && it.point.y < size.second })
            }.map { it.copy(pressMillis = 1) }
        }.getOrNull() else null
        overlay.show(saved ?: listOf(newStep(0)))
    }
    private fun newStep(index: Int): ClickStep {
        val size = screenSize(service)
        val spacing = service.resources.getDimensionPixelSize(R.dimen.crosshair_size) +
            service.resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
        val x = size.first / 2f + if (index == 0) 0 else ((index - 1) % 5 - 2) * spacing
        val y = size.second / 2f + if (index == 0) 0 else ((index - 1) / 5 + 1) * spacing
        return ClickStep(TapPoint(x.coerceIn(0f, size.first - 1f), y.coerceIn(0f, size.second - 1f)),
            MultiPointSequence.DEFAULT_DELAY, 1)
    }
    private fun editable() = visible && !active && restore == null && dialog == null
    private fun addPoint() {
        if (!editable()) return
        if (overlay.count == MultiPointSequence.MAX_POINTS) { overlay.showFeedback(service.getString(R.string.multi_limit)); return }
        overlay.append(newStep(overlay.count))
        persist()
    }
    private fun persist() {
        if (!visible || active) return
        store.save(mapOf("multiPoints" to StepText.format(overlay.snapshot()), "multiPointDisplay" to displayKey(service)))
    }
    private fun editPoint(index: Int) {
        if (!editable()) return
        val step = overlay.snapshot().getOrNull(index) ?: return
        val token = session
        val content = LayoutInflater.from(service).inflate(R.layout.multi_point_delay, null)
        val input = content.findViewById<EditText>(R.id.multi_delay_input).apply {
            setText(service.getString(R.string.multi_delay_value, step.waitBeforeMillis))
        }
        val picker = AlertDialog.Builder(service).setTitle(service.getString(R.string.multi_point_title, index + 1))
            .setView(content).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.multi_delete, null).setPositiveButton(android.R.string.ok, null).create()
        picker.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog = picker
        fun current() = dialog === picker && session == token && visible && !active
        picker.setOnDismissListener { if (dialog === picker) dialog = null }
        picker.setOnShowListener {
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!current()) return@setOnClickListener
                val delay = MultiPointSequence.delay(input.text.toString())
                if (delay == null) { input.error = service.getString(R.string.multi_delay_error); return@setOnClickListener }
                overlay.setDelay(index, delay); persist(); picker.dismiss()
            }
            picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (current()) { overlay.delete(index); persist(); picker.dismiss() }
            }
        }
        picker.show()
    }
    private fun toggle() {
        if (active) { runner.stop(); return }
        if (!editable()) { overlay.showFeedback(service.getString(R.string.multi_input_settling)); return }
        val steps = overlay.snapshot()
        if (steps.isEmpty()) { overlay.showFeedback(service.getString(R.string.multi_empty)); return }
        if (steps.any { overlay.covers(it.point) }) { overlay.showFeedback(service.getString(R.string.overlay_overlap)); return }
        persist()
        overlay.setMarkersTouchable(false)
        runner.start(steps, SystemClock.elapsedRealtime() + 150)
        overlay.showFeedback(service.getString(R.string.auto_click_enabled))
    }
    private fun statusChanged(state: RunStatus) {
        if (closing || !visible) return
        if (active) {
            restore?.let(main::removeCallbacks); restore = null
            overlay.setPlaying(true)
            return
        }
        overlay.setPlaying(false)
        overlay.setEditing(false)
        restore?.let(main::removeCallbacks)
        val token = session
        restore = Runnable {
            if (session == token && visible && !active) {
                restore = null
                overlay.setMarkersTouchable(true); overlay.setEditing(true)
            }
        }.also { main.postDelayed(it, 500) }
        overlay.showFeedback(service.getString(when (state.phase) {
            RunPhase.FAILED -> R.string.status_failed
            else -> R.string.auto_click_disabled
        }))
    }
    fun close() {
        closing = true; session++
        restore?.let(main::removeCallbacks); restore = null
        dialog?.dismiss(); dialog = null
        runner.stop(); overlay.close()
        closing = false
    }
}
