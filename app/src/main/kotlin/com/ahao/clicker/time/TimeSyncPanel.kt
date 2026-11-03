package com.ahao.clicker.time

import android.graphics.Typeface
import android.content.ClipData
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.DragEvent
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import com.ahao.clicker.ClickService
import com.ahao.clicker.ClickerApp
import com.ahao.clicker.R
import com.ahao.clicker.SettingsStore
import com.ahao.clicker.core.BuiltinTimeSource
import com.ahao.clicker.core.SourceClock
import com.ahao.clicker.core.TimeReference
import com.ahao.clicker.databinding.TimeSyncPanelBinding
import java.time.ZoneId

/** Time-page UI owns its own refresh callback; no Activity is retained by the network layer. */
class TimeSyncPanel internal constructor(private val binding: TimeSyncPanelBinding, private val app: ClickerApp,
    private val settings: TimeSettingsController) {
    private val context = binding.root.context
    private val store = SettingsStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val zone = ZoneId.systemDefault()
    private val syncDate = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss:SSS").withZone(zone)
    private val names = context.resources.getStringArray(R.array.builtin_sources)
    private var visible = false
    private var dragGeneration = 0L
    private var orderGeneration = 0L
    private var dragging: SourceDrag? = null
    private data class SourceDrag(val owner: TimeSyncPanel, val generation: Long,
        val source: BuiltinTimeSource, val origin: View, val original: List<BuiltinTimeSource>,
        val centers: List<Float>, var committed: Boolean = false)
    private data class Card(val source: BuiltinTimeSource, val root: View, val title: RadioButton, val time: TextView,
        val lastSync: TextView, var renderedSuccess: Long = Long.MIN_VALUE)
    private val cards = store.sourceOrder().mapIndexed { index, source ->
        val padding = context.resources.getDimensionPixelSize(R.dimen.control_padding_vertical)
        val verticalPadding = context.resources.getDimensionPixelSize(R.dimen.source_padding_vertical)
        val root = LinearLayout(context).apply {
            tag = "source_card_${source.name}"
            orientation = LinearLayout.VERTICAL
            setPadding(padding, verticalPadding, padding, verticalPadding)
            setBackgroundResource(R.drawable.source_background)
        }
        val title = RadioButton(context).apply {
            tag = source.name
            minHeight = context.resources.getDimensionPixelSize(R.dimen.source_title_height)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            setTextColor(context.getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.body_text))
        }
        val time = TextView(context).apply {
            tag = "${source.name}_time"
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.source_time_text))
            maxLines = 1
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(
                context.resources.getDimensionPixelSize(R.dimen.source_time_min_text),
                context.resources.getDimensionPixelSize(R.dimen.source_time_text), 1, TypedValue.COMPLEX_UNIT_PX)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val lastSync = TextView(context).apply {
            tag = "${source.name}_last_sync"
            includeFontPadding = false
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.caption_text))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 2f))
            addView(time, LinearLayout.LayoutParams(0, -2, 3f).apply {
                marginStart = context.resources.getDimensionPixelSize(R.dimen.source_gap)
            })
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(lastSync, LinearLayout.LayoutParams(-1, -2))
        binding.sources.addView(root, cardLayout(index))
        Card(source, root, title, time, lastSync)
    }.toMutableList()

    init {
        cards.forEach { card ->
            for (view in listOf(card.root, card.title, card.time, card.lastSync)) {
                view.setOnClickListener { if (visible && dragging == null) select(card.source) }
                view.setOnLongClickListener { origin ->
                    if (!visible || dragging != null) return@setOnLongClickListener false
                    val drag = SourceDrag(this, dragGeneration, card.source, origin,
                        cards.map { it.source }, cards.map { it.root.top + it.root.height / 2f })
                    dragging = drag
                    val started = origin.startDragAndDrop(ClipData.newPlainText("source", card.source.name),
                        View.DragShadowBuilder(card.root), drag, 0)
                    if (started) card.root.alpha = .5f else dragging = null
                    started
                }
            }
            androidx.core.view.ViewCompat.addAccessibilityAction(card.root, context.getString(R.string.source_move_previous)) { _, _ ->
                moveBy(card.source, -1)
            }
            androidx.core.view.ViewCompat.addAccessibilityAction(card.root, context.getString(R.string.source_move_next)) { _, _ ->
                moveBy(card.source, 1)
            }
        }
        binding.sources.setOnDragListener { _, event ->
            val drag = event.localState as? SourceDrag
            if (drag?.owner !== this || drag !== dragging) return@setOnDragListener false
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                finishDrag(drag, visible)
                return@setOnDragListener true
            }
            if (!visible || drag.generation != dragGeneration) return@setOnDragListener false
            if (event.action == DragEvent.ACTION_DRAG_LOCATION) previewDrag(drag, event)
            if (event.action == DragEvent.ACTION_DROP) {
                if (!previewDrag(drag, event)) return@setOnDragListener false
                if (!drag.committed) {
                    val next = cards.map { it.source }
                    if (next != drag.original) store.saveSourceOrder(next)
                    drag.committed = true
                }
            }
            true
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            dragGeneration++
            dragging?.let { drag -> drag.origin.cancelDragAndDrop(); finishDrag(drag, false) }
            stopOrderAnimation()
        }
        main.removeCallbacks(refresh)
        app.observeAllTimes(this, value)
        if (value) refresh.run()
    }

    private fun select(source: BuiltinTimeSource) {
        if (!settings.select(source)) {
            Toast.makeText(context, R.string.task_active, Toast.LENGTH_SHORT).show()
            render(); return
        }
        render()
    }

    private fun moveBy(source: BuiltinTimeSource, delta: Int): Boolean {
        if (!visible || dragging != null) return false
        val target = cards.getOrNull(cards.indexOfFirst { it.source == source } + delta) ?: return false
        reorder(source, target.source)
        return true
    }
    private fun cardLayout(index: Int): GridLayout.LayoutParams {
        val gap = context.resources.getDimensionPixelSize(R.dimen.source_gap)
        return GridLayout.LayoutParams(GridLayout.spec(index), GridLayout.spec(0, 1f)).apply {
            width = 0; height = -2; setGravity(Gravity.FILL_HORIZONTAL)
            setMargins(gap, gap, gap, gap)
        }
    }
    private fun reorder(source: BuiltinTimeSource, target: BuiltinTimeSource) {
        val before = cards.map { it.source }
        val next = SourceOrder.move(before, source, target)
        if (next == before) return
        displayOrder(next, true)
        store.saveSourceOrder(next)
    }

    private fun previewDrag(drag: SourceDrag, event: DragEvent): Boolean {
        if (event.x < 0 || event.x >= binding.sources.width || event.y < 0 || event.y >= binding.sources.height) return false
        // Fixed slot centers keep an animating card from toggling its own drop target.
        val index = drag.centers.indices.minBy { kotlin.math.abs(event.y - drag.centers[it]) }
        displayOrder(SourceOrder.move(cards.map { it.source }, drag.source, cards[index].source), true)
        return true
    }

    private fun finishDrag(drag: SourceDrag, animate: Boolean) {
        if (dragging !== drag) return
        if (!drag.committed) displayOrder(drag.original, animate)
        cards.forEach { it.root.alpha = 1f }
        dragging = null
    }

    private fun stopOrderAnimation() {
        orderGeneration++
        cards.forEach { it.root.animate().cancel(); it.root.translationY = 0f }
    }

    private fun displayOrder(next: List<BuiltinTimeSource>, animate: Boolean) {
        if (next == cards.map { it.source }) return
        val tops = cards.associate { it.source to it.root.top + it.root.translationY }
        val reordered = next.map { item -> cards.first { it.source == item } }
        cards.clear(); cards.addAll(reordered)
        stopOrderAnimation()
        val generation = orderGeneration
        cards.forEachIndexed { index, card ->
            card.root.layoutParams = cardLayout(index)
            // Reorder children without detaching the view that owns the native drag.
            binding.sources.bringChildToFront(card.root)
        }
        if (animate) binding.sources.doOnNextLayout {
            if (generation != orderGeneration) return@doOnNextLayout
            cards.forEach { card ->
                card.root.translationY = tops.getValue(card.source) - card.root.top
                card.root.animate().translationY(0f)
                    .setDuration(context.resources.getInteger(R.integer.source_reorder_duration).toLong()).start()
            }
        }
    }

    private fun render() {
        val elapsed = SystemClock.elapsedRealtime()
        val device = System.currentTimeMillis()
        val activeTask = ClickService.current?.hasActiveTask == true
        val selected = app.timeSources.selectedSource
        for (card in cards) {
            val manager = app.timeSources.manager(card.source)
            val state = manager.state
            val local = card.source == BuiltinTimeSource.LOCAL
            if (card.renderedSuccess != state.lastSuccessEpoch) {
                card.renderedSuccess = state.lastSuccessEpoch
                card.lastSync.text = when {
                    local -> context.getString(R.string.source_local_no_sync)
                    state.lastSuccessEpoch < 0 -> context.getString(R.string.source_never_synced)
                    else -> context.getString(R.string.source_last_sync,
                        syncDate.format(java.time.Instant.ofEpochMilli(state.lastSuccessEpoch)))
                }
            }
            val reference = if (local) TimeReference(device, elapsed, true) else manager.reference()
            val qualifier = when {
                !local && reference.synced && elapsed - state.lastSuccessElapsed > 300_000 -> R.string.source_stale
                reference.synced && state.failed -> R.string.source_previous
                else -> null
            }
            val name = names[card.source.ordinal]
            val title = qualifier?.let { context.getString(R.string.source_card_title, name, context.getString(it)) } ?: name
            if (card.title.text != title) card.title.text = title
            card.title.isChecked = card.source == selected
            card.root.isSelected = card.source == selected
            card.title.isEnabled = !activeTask
            val text = SourceClock.text(reference, elapsed, device, zone) ?: context.getString(when {
                state.syncing -> R.string.syncing
                state.failed -> R.string.source_unavailable
                else -> R.string.source_waiting
            })
            if (card.time.text != text) card.time.text = text
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            render()
            main.postDelayed(this, 100)
        }
    }
}
