package com.ahao.clicker

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ahao.clicker.core.TapPoint

/** Keep input channels stable; make capture transparent and untouchable around injected taps. */
class ClickOverlay(
    private val service: ClickService,
    private val finish: () -> Unit,
    private val stop: () -> Unit,
    private val capture: (TapPoint) -> Unit,
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val label = TextView(service).apply {
        setTextColor(service.getColor(R.color.control_text))
        minLines = 2; maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val finishButton = Button(service).apply { setText(R.string.finish_recording); setOnClickListener { view -> view.post { finish() } } }
    private val controls = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        val horizontal = resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
        val vertical = resources.getDimensionPixelSize(R.dimen.control_padding_vertical)
        setPadding(horizontal, vertical, horizontal, vertical)
        setBackgroundColor(service.getColor(R.color.control_background))
        addView(label)
        addView(finishButton)
        addView(Button(service).apply { setText(R.string.stop); setOnClickListener { view -> view.post { stop() } } })
    }
    private val captureView = object : View(service) {
        private var downX = 0f
        private var downY = 0f
        private var validTap = false
        private val slop = android.view.ViewConfiguration.get(service).scaledTouchSlop
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!captureEnabled) { validTap = false; return true }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.rawX; downY = event.rawY; validTap = true
            }
            if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                kotlin.math.abs(event.rawX - downX) > slop || kotlin.math.abs(event.rawY - downY) > slop) validTap = false
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                performClick()
                if (validTap && event.eventTime - event.downTime < android.view.ViewConfiguration.getLongPressTimeout()) {
                    val point = TapPoint(event.rawX, event.rawY)
                    // Acknowledge ACTION_UP before changing the capture window.
                    val token = generation
                    post { if (capturing && captureEnabled && generation == token) capture(point) }
                }
                validTap = false
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }.apply { setBackgroundColor(service.getColor(R.color.capture_tint)); contentDescription = service.getString(R.string.record_hint) }
    private var capturing = false
    private var showing = false
    private var captureParams: WindowManager.LayoutParams? = null
    private var captureEnabled = false
    private var generation = 0L

    fun showControls(recording: Boolean) {
        finishButton.visibility = if (recording) View.VISIBLE else View.GONE
        if (showing) return
        val params = params(service.resources.getDimensionPixelSize(R.dimen.control_width), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        manager.addView(controls, params)
        showing = true
    }
    fun showCapture() {
        if (capturing) {
            // Restore the existing window after the injected UP has drained.
            captureParams?.let {
                it.alpha = 1f
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                manager.updateViewLayout(captureView, it)
                captureEnabled = true
            }
            return
        }
        val layout = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        captureParams = layout
        manager.addView(captureView, layout)
        capturing = true
        captureEnabled = true
    }
    fun suspendCapture(ready: () -> Unit) {
        if (!capturing || !captureEnabled) return
        captureEnabled = false
        val token = ++generation
        val layout = checkNotNull(captureParams)
        layout.alpha = 0f
        layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        manager.updateViewLayout(captureView, layout)
        // Apply relayout, then allow a bounded input-window settling period. Playback has no such wait.
        captureView.postOnAnimation { captureView.postOnAnimation {
            if (capturing && generation == token) captureView.postDelayed({ if (capturing && generation == token) ready() }, 200)
        } }
    }
    fun update(text: String) { if (label.text != text) label.text = text }
    fun close() {
        generation++
        if (capturing) manager.removeViewImmediate(captureView)
        captureParams = null
        capturing = false
        captureEnabled = false
        if (showing) manager.removeViewImmediate(controls)
        showing = false
    }
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT,
    )
}
