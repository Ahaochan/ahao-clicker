package com.ahao.clicker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ahao.clicker.core.ClickStep
import com.ahao.clicker.core.MultiPointSequence
import com.ahao.clicker.core.TapPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** Static numbered marker windows; only the shared clock requests redraws. */
class MultiPointOverlay(private val service: ClickService, private val toggle: () -> Unit,
    private val add: () -> Unit, private val edit: (Int) -> Unit, private val changed: () -> Unit,
    private val dismiss: () -> Unit, private val readTime: () -> Long) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val side = service.resources.getDimensionPixelSize(R.dimen.crosshair_size)
    private val markers = mutableListOf<Marker>()
    private var panel: LinearLayout? = null
    private var play: ImageButton? = null
    private var plus: ImageButton? = null
    private var feedback: TextView? = null
    private var passThrough = false
    private var editing = true
    private var playing = false
    private val hideFeedback = Runnable { feedback?.visibility = View.GONE }
    val isVisible get() = panel != null
    val count get() = markers.size

    private class Marker(val view: NumberedMarker, val params: WindowManager.LayoutParams,
        var step: ClickStep)

    fun show(steps: List<ClickStep>) {
        close()
        val frame = OverlayPanel(service)
        val header = LayoutInflater.from(service).inflate(R.layout.overlay_header, frame, false)
        play = header.findViewById<ImageButton>(R.id.toggle_click).apply {
            setOnClickListener { post { if (panel === frame) toggle() } }
        }
        plus = header.findViewById<ImageButton>(R.id.add_point).apply {
            visibility = View.VISIBLE
            setOnClickListener { post { if (panel === frame && editing) add() } }
        }
        header.findViewById<ImageButton>(R.id.close_overlay).apply {
            contentDescription = service.getString(R.string.multi_close)
            setOnClickListener { post { if (panel === frame) dismiss() } }
        }
        frame.addView(header)
        val content = FrameLayout(service).apply {
            addView(ScrollView(service).apply {
                isFillViewport = true
                addView(ClockFace(service, readTime), FrameLayout.LayoutParams(-1,
                    service.resources.getDimensionPixelSize(R.dimen.overlay_clock_height)))
            }, FrameLayout.LayoutParams(-1, -1))
            minimumHeight = service.resources.getDimensionPixelSize(R.dimen.overlay_clock_height)
        }
        frame.addView(content, LinearLayout.LayoutParams(-1, service.resources.getDimensionPixelSize(R.dimen.overlay_clock_height), 1f))
        feedback = (LayoutInflater.from(service).inflate(R.layout.overlay_feedback, content, false) as TextView)
            .also(content::addView)
        val width = service.resources.getDimensionPixelSize(R.dimen.multi_control_width).coerceAtMost(screenSize(service).first)
        val layout = params(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = service.resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
            y = service.resources.getDimensionPixelSize(R.dimen.touch_target)
        }
        frame.addResizeHandles(layout, 4, OverlayPanelKind.MULTI_POINT) { editing && !playing }
        panel = frame
        manager.addView(frame, layout)
        drag(header.findViewById(R.id.overlay_drag), frame, layout, false) {}
        steps.forEach(::append)
        setPlaying(false)
    }

    fun append(step: ClickStep) {
        check(markers.size < MultiPointSequence.MAX_POINTS && !passThrough)
        val view = NumberedMarker(service)
        val layout = params(side, side).apply {
            x = (step.point.x - side / 2f).roundToInt()
            y = (step.point.y - side / 2f).roundToInt()
        }
        val marker = Marker(view, layout, step)
        markers.add(marker)
        OverlayDiagnostics.watch(view, "multi_marker_${markers.size}")
        renumber()
        view.setOnClickListener { view.post {
            if (editing && markers.contains(marker)) edit(markers.indexOf(marker))
        } }
        manager.addView(view, layout)
        drag(view, view, layout, true) {
            update(marker)
            changed()
        }
    }

    fun snapshot(): List<ClickStep> = markers.map { update(it); it.step }
    private fun update(marker: Marker) {
        if (!marker.view.isAttachedToWindow || marker.view.width == 0) return
        val xy = IntArray(2)
        marker.view.getLocationOnScreen(xy)
        marker.step = marker.step.copy(point = TapPoint(xy[0] + side / 2f, xy[1] + side / 2f))
    }
    fun setDelay(index: Int, delay: Long) {
        markers[index].let { it.step = it.step.copy(waitBeforeMillis = delay) }
    }
    fun delete(index: Int) {
        val marker = markers.removeAt(index)
        manager.removeViewImmediate(marker.view)
        renumber()
    }
    private fun renumber() {
        markers.forEachIndexed { index, marker ->
            marker.view.number = (index + 1).toString()
            marker.view.contentDescription = service.getString(R.string.multi_point_description, index + 1)
            marker.view.invalidate()
        }
    }
    fun setMarkersTouchable(touchable: Boolean) {
        if (passThrough == !touchable) return
        markers.forEach {
            it.params.flags = if (touchable) it.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else it.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            manager.updateViewLayout(it.view, it.params)
        }
        passThrough = !touchable
    }
    fun setPlaying(value: Boolean) {
        playing = value
        play?.isActivated = value
        play?.contentDescription = service.getString(if (value) R.string.auto_click_stop else R.string.auto_click_start)
        setEditing(!value)
    }
    fun setEditing(value: Boolean) { editing = value; plus?.isEnabled = value }
    fun covers(point: TapPoint): Boolean {
        val view = panel ?: return false
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return point.x >= xy[0] && point.x < xy[0] + view.width && point.y >= xy[1] && point.y < xy[1] + view.height
    }
    fun showFeedback(message: String) {
        feedback?.let {
            it.removeCallbacks(hideFeedback); it.text = message; it.visibility = View.VISIBLE
            it.postDelayed(hideFeedback, 2_000)
        }
    }
    fun close() {
        feedback?.removeCallbacks(hideFeedback)
        markers.forEach { manager.removeViewImmediate(it.view) }
        markers.clear(); passThrough = false
        panel?.let(manager::removeViewImmediate)
        panel = null; feedback = null; play = null; plus = null
        editing = true; playing = false
    }

    @Suppress("ClickableViewAccessibility")
    private fun drag(handle: View, window: View, layout: WindowManager.LayoutParams, marker: Boolean, moved: () -> Unit) {
        var rawX = 0f; var rawY = 0f; var x = 0; var y = 0; var dragging = false
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            if (!editing || playing) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX; rawY = event.rawY; x = layout.x; y = layout.y; dragging = false
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - rawX) > slop || abs(event.rawY - rawY) > slop) dragging = true
                    if (dragging) {
                        val size = screenSize(service)
                        val bounds = if (marker) null else overlayAvailableBounds(window)
                        val left = if (marker) -side / 2 else bounds!!.left
                        val top = if (marker) -side / 2 else bounds!!.top
                        val right = if (marker) -side / 2 + 1 else size.first - bounds!!.left - bounds.width
                        val bottom = if (marker) -side / 2 + 1 else size.second - bounds!!.top - bounds.height
                        layout.x = (x + (event.rawX - rawX).roundToInt()).coerceIn(left,
                            (size.first - window.width - right).coerceAtLeast(left))
                        layout.y = (y + (event.rawY - rawY).roundToInt()).coerceIn(top,
                            (size.second - window.height - bottom).coerceAtLeast(top))
                        manager.updateViewLayout(window, layout)
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (!dragging) handle.performClick()
                        else window.postOnAnimation { window.post { if (window.isAttachedToWindow) moved() } }
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
    }
    @android.annotation.SuppressLint("RtlHardcoded")
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
}

private class NumberedMarker(context: android.content.Context) : View(context) {
    var number = "1"
    private val gap = resources.getDimension(R.dimen.multi_crosshair_gap)
    private val arm = resources.getDimension(R.dimen.crosshair_arm)
    private val border = resources.getDimension(R.dimen.crosshair_outline)
    private val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.crosshair_color)
        strokeWidth = resources.getDimension(R.dimen.crosshair_stroke)
    }
    private val outline = Paint(foreground).apply {
        color = context.getColor(R.color.crosshair_outline); strokeWidth += border * 2
    }
    private val text = Paint(foreground).apply {
        textSize = resources.getDimension(R.dimen.multi_number_text)
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val textOutline = Paint(text).apply {
        color = outline.color; style = Paint.Style.STROKE; strokeWidth = border * 2
    }
    override fun onDraw(canvas: Canvas) {
        val x = width / 2f; val y = height / 2f
        fun arms(paint: Paint) {
            canvas.drawLine(x - gap - arm, y, x - gap, y, paint)
            canvas.drawLine(x + gap, y, x + gap + arm, y, paint)
            canvas.drawLine(x, y - gap - arm, x, y - gap, paint)
            canvas.drawLine(x, y + gap, x, y + gap + arm, paint)
        }
        arms(outline); arms(foreground)
        val baseline = y - (text.ascent() + text.descent()) / 2
        canvas.drawText(number, x, baseline, textOutline)
        canvas.drawText(number, x, baseline, text)
    }
}
