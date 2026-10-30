package com.ahao.clicker

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun overlayAvailableBounds(window: View): OverlayRect {
    val size = screenSize(window.context)
    val safe = if (android.os.Build.VERSION.SDK_INT >= 30) {
        val insets = window.context.getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        androidx.core.graphics.Insets.of(insets.left, insets.top, insets.right, insets.bottom)
    } else ViewCompat.getRootWindowInsets(window)?.getInsetsIgnoringVisibility(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    val left = safe?.left ?: 0; val top = safe?.top ?: 0
    return OverlayRect(left, top, (size.first - left - (safe?.right ?: 0)).coerceAtLeast(1),
        (size.second - top - (safe?.bottom ?: 0)).coerceAtLeast(1))
}

/** Limits the initial height, but respects an explicit user resize. Header/footer never scroll. */
internal class OverlayPanel(context: Context) : LinearLayout(context) {
    var compactChanged: ((Boolean) -> Unit)? = null
    private var compact: Boolean? = null
    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.control_panel_background))
        setPadding(resources.getDimensionPixelSize(R.dimen.control_padding_horizontal),
            resources.getDimensionPixelSize(R.dimen.control_padding_vertical),
            resources.getDimensionPixelSize(R.dimen.control_padding_horizontal),
            resources.getDimensionPixelSize(R.dimen.control_padding_vertical))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val small = MeasureSpec.getSize(widthMeasureSpec) < resources.getDimensionPixelSize(R.dimen.overlay_compact_width)
        if (compact != small) {
            compact = small
            val horizontal = resources.getDimensionPixelSize(if (small) R.dimen.overlay_compact_padding else R.dimen.control_padding_horizontal)
            val vertical = resources.getDimensionPixelSize(if (small) R.dimen.overlay_compact_padding else R.dimen.control_padding_vertical)
            setPadding(horizontal, vertical, horizontal, vertical)
            compactChanged?.invoke(small)
        }
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) heightMeasureSpec else {
            val maximum = screenSize(context).second * 3 / 4
            val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST)
                minOf(maximum, MeasureSpec.getSize(heightMeasureSpec)) else maximum
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, limit)
    }
    fun minimumHeightFor(width: Int, controls: Int): Int {
        val small = width < resources.getDimensionPixelSize(R.dimen.overlay_compact_width)
        val horizontal = resources.getDimensionPixelSize(if (small) R.dimen.overlay_compact_padding else R.dimen.control_padding_horizontal)
        val vertical = resources.getDimensionPixelSize(if (small) R.dimen.overlay_compact_padding else R.dimen.control_padding_vertical)
        val target = resources.getDimensionPixelSize(R.dimen.overlay_button_target)
        val header = overlayHeaderCells(width - horizontal * 2, target, controls).maxOf { it.top + it.height }
        return header + vertical * 2 + resources.getDimensionPixelSize(R.dimen.overlay_clock_height) +
            resources.getDimensionPixelSize(R.dimen.overlay_resize_target) + if (controls == 3) target else 0
    }
}

/** Updates only on a resize gesture; no polling, bitmap, animation or retained service singleton. */
@Suppress("ClickableViewAccessibility")
internal fun OverlayPanel.addResizeHandles(params: WindowManager.LayoutParams, controls: Int,
    kind: OverlayPanelKind, enabled: () -> Boolean) {
    val manager = context.getSystemService(WindowManager::class.java)
    val footer = LayoutInflater.from(context).inflate(R.layout.overlay_resize_handles, this, false)
    addView(footer)
    val minWidth = resources.getDimensionPixelSize(R.dimen.overlay_min_width)
    val store = SettingsStore(context)
    val density = resources.displayMetrics.density
    store.overlaySize(kind)?.let { size ->
        val restored = restoreOverlaySize(size, density, params.x, params.y, overlayAvailableBounds(this), minWidth) {
            minimumHeightFor(it, controls)
        }
        params.x = restored.left; params.y = restored.top
        params.width = restored.width; params.height = restored.height
    }
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    for ((id, fromLeft) in listOf(R.id.resize_bottom_left to true, R.id.resize_bottom_right to false)) {
        var rawX = 0f; var rawY = 0f
        var start = OverlayRect(0, 0, 0, 0)
        var available = start
        var dragging = false
        footer.findViewById<View>(id).setOnTouchListener { handle, event ->
            if (!isAttachedToWindow || !enabled()) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX; rawY = event.rawY; dragging = false
                    start = OverlayRect(params.x, params.y, width, height)
                    available = overlayAvailableBounds(this)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - rawX; val dy = event.rawY - rawY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        val candidate = resizeOverlay(start, available, dx.roundToInt(), dy.roundToInt(), fromLeft, minWidth, 1)
                        val next = resizeOverlay(start, available, dx.roundToInt(), dy.roundToInt(), fromLeft, minWidth,
                            minimumHeightFor(candidate.width, controls))
                        if (params.x != next.left || params.y != next.top || params.width != next.width || params.height != next.height) {
                            params.x = next.left; params.y = next.top; params.width = next.width; params.height = next.height
                            manager.updateViewLayout(this, params)
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP &&
                            (next.width != start.width || next.height != start.height)) {
                            store.saveOverlaySize(kind, OverlaySize(next.width / density, next.height / density))
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
    }
}
