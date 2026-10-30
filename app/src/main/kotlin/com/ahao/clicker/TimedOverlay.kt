package com.ahao.clicker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.ahao.clicker.core.TapPoint
import kotlin.math.roundToInt
import androidx.core.graphics.withScale
import com.ahao.clicker.core.TimingObservation

/** Small independent windows: the marker never shares a touchable window with Stop. */
class TimedOverlay(private val service: ClickService, private val toggleClick: () -> Unit,
    private val pointChanged: (TapPoint) -> Unit, private val editTime: () -> Unit, private val readTime: () -> Long) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var markerReady = false
    private var markerSuspended = false
    private var marker: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var clock: ClockFace? = null
    private var toggle: ImageButton? = null
    private var startTime: TextView? = null
    private var startEpoch: Long? = null
    private var compact = false
    private var feedback: TextView? = null
    private val hideFeedback = Runnable { feedback?.visibility = View.GONE }
    private var draggable = true
    private val side = service.resources.getDimensionPixelSize(R.dimen.crosshair_size)
    private var shownPoint: TapPoint? = null
    val isVisible get() = panel != null || marker != null
    val clockVisible get() = clock?.visibility == View.VISIBLE

    fun showMarker(point: TapPoint?) {
        marker?.let { if (!markerSuspended) manager.removeViewImmediate(it) }
        markerSuspended = false
        markerReady = false
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = service.getColor(R.color.crosshair_outline)
            strokeWidth = service.resources.getDimension(R.dimen.crosshair_stroke) + service.resources.getDimension(R.dimen.crosshair_outline) * 2
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = service.getColor(R.color.crosshair_color)
            strokeWidth = service.resources.getDimension(R.dimen.crosshair_stroke)
        }
        val gap = service.resources.getDimension(R.dimen.crosshair_gap)
        val arm = service.resources.getDimension(R.dimen.crosshair_arm)
        val dot = service.resources.getDimension(R.dimen.crosshair_dot)
        val border = service.resources.getDimension(R.dimen.crosshair_outline)
        val view = object : View(service) {
            override fun onDraw(canvas: Canvas) {
                val x = width / 2f; val y = height / 2f
                fun lines(p: Paint) {
                    canvas.drawLine(x - gap - arm, y, x - gap, y, p)
                    canvas.drawLine(x + gap, y, x + gap + arm, y, p)
                    canvas.drawLine(x, y - gap - arm, x, y - gap, p)
                    canvas.drawLine(x, y + gap, x, y + gap + arm, p)
                }
                lines(outline); lines(paint)
                canvas.drawCircle(x, y, dot + border, outline)
                canvas.drawCircle(x, y, dot, paint)
            }
        }.apply { contentDescription = service.getString(R.string.crosshair_description) }
        val size = screenSize(service)
        val at = point ?: TapPoint(size.first / 2f, size.second / 2f)
        val params = params(side, side).apply {
            x = (at.x - side / 2).roundToInt(); y = (at.y - side / 2).roundToInt()
        }
        marker = view; markerParams = params; shownPoint = at
        manager.addView(view, params)
        drag(view, view, params) { finished ->
            updatePoint()
            if (finished) pointChanged(point())
        }
        view.postOnAnimation { view.post {
            if (marker === view && !markerSuspended && view.isAttachedToWindow) {
                markerReady = true
                updatePoint(); pointChanged(point())
            }
        } }
    }
    private fun updatePoint() {
        val view = marker ?: return
        if (markerSuspended || !markerReady) return
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        shownPoint = TapPoint(xy[0] + view.width / 2f, xy[1] + view.height / 2f)
    }
    fun point(): TapPoint { updatePoint(); return checkNotNull(shownPoint) }
    /** Remove the input window during injection; restore only this same marker if still current. */
    fun suspendMarker(): () -> Unit {
        val view = checkNotNull(marker)
        val layout = checkNotNull(markerParams)
        if (!markerSuspended) {
            updatePoint()
            pointChanged(point())
            manager.removeViewImmediate(view)
            markerSuspended = true
        }
        return {
            if (marker === view && markerSuspended) {
                markerReady = false
                manager.addView(view, layout)
                markerSuspended = false
                view.postOnAnimation { view.post {
                    if (marker === view && !markerSuspended && view.isAttachedToWindow) {
                        markerReady = true
                        updatePoint()
                    }
                } }
            }
        }
    }

    fun showPanel(label: Int, cancel: () -> Unit) {
        closePanel()
        draggable = true
        val body = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val h = resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
            val v = resources.getDimensionPixelSize(R.dimen.control_padding_vertical)
            setPadding(h, v, h, v)
        }
        run {
            clock = ClockFace(service, readTime)
            body.addView(clock, LinearLayout.LayoutParams(-1, service.resources.getDimensionPixelSize(R.dimen.overlay_clock_height)))
            startTime = LayoutInflater.from(service).inflate(R.layout.overlay_start_time, body, false) as TextView
            startTime?.setOnClickListener { editTime() }
            body.addView(startTime)
        }
        val frame = OverlayPanel(service).apply {
            compactChanged = { small -> compact = small; renderStartTime() }
            body.setPadding(0, 0, 0, 0)
            val content = FrameLayout(service).apply {
                addView(ScrollView(service).apply { addView(body) }, FrameLayout.LayoutParams(-1, -2))
            }
            feedback = (LayoutInflater.from(service).inflate(R.layout.overlay_feedback, content, false) as TextView)
                .also { content.addView(it) }
            addView(content, LinearLayout.LayoutParams(-1, -2, 1f))

        }
        val header = LayoutInflater.from(service).inflate(R.layout.overlay_header, frame, false)
        val handle = header.findViewById<View>(R.id.overlay_drag)
        toggle = header.findViewById<ImageButton>(R.id.toggle_click).apply {
            setOnClickListener { post { toggleClick() } }
        }
        header.findViewById<ImageButton>(R.id.close_overlay).apply {
            contentDescription = service.getString(label)
            setOnClickListener { post { cancel() } }
        }
        frame.addView(header, 0)
        val params = params(service.resources.getDimensionPixelSize(R.dimen.control_width), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            TimingObservation.windowTitle?.let { title = it }
            x = service.resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
            y = service.resources.getDimensionPixelSize(R.dimen.touch_target)
        }
        frame.addResizeHandles(params, 3, OverlayPanelKind.TIMED) { draggable }
        panel = frame; panelParams = params
        manager.addView(frame, params)
        drag(handle, frame, params) {}
        updatePoint()
    }
    fun armed() = setPlaying(true)
    fun setPlaying(playing: Boolean) {
        toggle?.let {
            if (it.isActivated != playing) it.isActivated = playing
            it.contentDescription = service.getString(if (playing) R.string.auto_click_stop else R.string.auto_click_start)
        }
        draggable = !playing
    }
    fun showStartTime(epoch: Long?) {
        startEpoch = epoch
        renderStartTime()
    }
    private fun renderStartTime() {
        val time = startEpoch?.let { com.ahao.clicker.core.ClockDigits.format(it, java.time.ZoneId.systemDefault()) }
        val fullLabel = time?.let { service.getString(R.string.click_start_time, it) } ?: service.getString(R.string.click_start_unset)
        val label = if (compact) time ?: service.getString(R.string.click_start_unset_compact) else fullLabel
        if (startTime?.text != label) {
            startTime?.text = label
            startTime?.contentDescription = service.getString(R.string.edit_trigger_time_value, fullLabel)
        }
    }
    fun showFeedback(message: String): Boolean {
        val view = feedback ?: return false
        view.removeCallbacks(hideFeedback)
        view.text = message
        view.visibility = View.VISIBLE
        view.postDelayed(hideFeedback, 2_000)
        return true
    }
    fun covers(point: TapPoint): Boolean {
        val view = panel ?: return false
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return point.x >= xy[0] && point.x < xy[0] + view.width && point.y >= xy[1] && point.y < xy[1] + view.height
    }
    @Suppress("ClickableViewAccessibility")
    private fun drag(handle: View, window: View, params: WindowManager.LayoutParams, changed: (Boolean) -> Unit) {
        var startX = 0f; var startY = 0f; var px = 0; var py = 0
        handle.setOnTouchListener { _, event ->
            if (!draggable && window !== marker) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; px = params.x; py = params.y }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val size = screenSize(service)
                    val half = if (window === marker) side / 2 else 0
                    val safe = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val insets = manager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                        androidx.core.graphics.Insets.of(insets.left, insets.top, insets.right, insets.bottom)
                    } else androidx.core.view.ViewCompat.getRootWindowInsets(window)?.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                    val left = if (half == 0) safe?.left ?: 0 else -half
                    val top = if (half == 0) safe?.top ?: 0 else -half
                    val right = if (half == 0) safe?.right ?: 0 else -half
                    val bottom = if (half == 0) safe?.bottom ?: 0 else -half
                    val edge = if (window === marker) 1 else 0
                    params.x = (px + (event.rawX - startX).roundToInt()).coerceIn(left, (size.first - window.width - right - edge).coerceAtLeast(left))
                    params.y = (py + (event.rawY - startY).roundToInt()).coerceIn(top, (size.second - window.height - bottom - edge).coerceAtLeast(top))
                    manager.updateViewLayout(window, params)
                    val finished = event.actionMasked == MotionEvent.ACTION_UP
                    window.postOnAnimation {
                        if (finished) window.post { if (window.isAttachedToWindow) changed(true) }
                        else if (window.isAttachedToWindow) changed(false)
                    }
                }
            }
            true
        }
    }
    fun close() {
        closePanel()
        marker?.let { if (!markerSuspended) manager.removeViewImmediate(it) }
        markerSuspended = false
        markerReady = false
        marker = null; markerParams = null; shownPoint = null
    }
    private fun closePanel() {
        feedback?.removeCallbacks(hideFeedback)
        (panel as? OverlayPanel)?.compactChanged = null
        panel?.let { manager.removeViewImmediate(it) }
        panel = null; panelParams = null; clock = null
        toggle = null; startTime = null
        startEpoch = null; compact = false
        feedback = null
    }
    // Physical screen pixels must retain the same origin in RTL locales too.
    @android.annotation.SuppressLint("RtlHardcoded")
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
}

/** Reuses twelve characters and Paint; no formatter or hierarchy rebuild per frame. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic view requires the selected clock; never inflated from XML.
internal class ClockFace(context: android.content.Context, private val readTime: () -> Long) : View(context) {
    private val chars = "00:00:00:000".toCharArray()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.control_text)
        typeface = Typeface.MONOSPACE
        textSize = resources.getDimension(R.dimen.clock_text)
    }
    private val zone = java.util.TimeZone.getDefault()
    private val textWidth = paint.measureText(chars, 0, chars.size)
    private val textHeight = paint.descent() - paint.ascent()
    private var lastSecond = Long.MIN_VALUE
    private var offset = 0
    private val redraw = Runnable {
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            TimingObservation.emit(TimingObservation.INVALIDATE)
            invalidate()
        }
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(redraw)
        super.onDetachedFromWindow()
    }
    private fun update(epoch: Long) {
        val second = epoch / 1_000
        val announce = second != lastSecond
        if (announce) { offset = zone.getOffset(epoch); lastSecond = second }
        var ms = Math.floorMod(epoch + offset, 86_400_000L).toInt()
        fun pair(at: Int, value: Int) { chars[at] = ('0'.code + value / 10).toChar(); chars[at + 1] = ('0'.code + value % 10).toChar() }
        pair(0, ms / 3_600_000); ms %= 3_600_000
        pair(3, ms / 60_000); ms %= 60_000
        pair(6, ms / 1_000); ms %= 1_000
        chars[9] = ('0'.code + ms / 100).toChar(); pair(10, ms % 100)
        if (announce) contentDescription = String(chars)
    }
    override fun onDraw(canvas: Canvas) {
        TimingObservation.emit(TimingObservation.FRAME_BEGIN)
        val epoch = readTime()
        TimingObservation.emit(TimingObservation.FRAME_VALUE, epoch)
        update(epoch)
        val scale = overlayClockScale(width.toFloat(), height.toFloat(), textWidth, textHeight)
        if (scale > 0) canvas.withScale(scale, scale, 0f, 0f) {
            drawText(chars, 0, chars.size, 0f, (height / scale - paint.ascent() - paint.descent()) / 2, paint)
        }
        TimingObservation.emit(TimingObservation.FRAME_END)
        // Parent/layout/ripple redraws can arrive before our timer. Keep exactly one cancellable request.
        removeCallbacks(redraw)
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) postDelayed(redraw, 16)
        TimingObservation.emit(TimingObservation.FRAME_REQUEST)
    }
}

@Suppress("DEPRECATION")
fun screenSize(context: android.content.Context): Pair<Int, Int> {
    val point = android.graphics.Point()
    context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(point)
    return point.x to point.y
}
