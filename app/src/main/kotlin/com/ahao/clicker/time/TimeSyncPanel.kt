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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
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
class TimeSyncPanel(private val binding: TimeSyncPanelBinding, private val app: ClickerApp) {
    private val context = binding.root.context
    private val store = SettingsStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val zone = ZoneId.systemDefault()
    private val syncDate = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss:SSS").withZone(zone)
    private val names = context.resources.getStringArray(R.array.builtin_sources)
    private var visible = false
    private val settings = TimeSettingsController({ ClickService.current?.hasActiveTask == true },
        store::save, app::refreshTimeSources, app.timeSources::syncAll)
    private var dragGeneration = 0L
    private var dragging: SourceDrag? = null
    private data class SourceDrag(val owner: TimeSyncPanel, val generation: Long,
        val source: BuiltinTimeSource, val origin: View)
    private data class Card(val source: BuiltinTimeSource, val root: View, val title: RadioButton, val time: TextView,
        val lastSync: TextView, var renderedSuccess: Long = Long.MIN_VALUE)
    private val cards = store.sourceOrder().mapIndexed { index, source ->
        val padding = context.resources.getDimensionPixelSize(R.dimen.control_padding_vertical)
        val gap = context.resources.getDimensionPixelSize(R.dimen.source_gap)
        val root = LinearLayout(context).apply {
            tag = "source_card_${source.name}"
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.source_background)
        }
        val title = RadioButton(context).apply {
            tag = source.name
            minHeight = context.resources.getDimensionPixelSize(R.dimen.touch_target)
            setTextColor(context.getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.body_text))
            setOnClickListener { select(source) }
        }
        val time = TextView(context).apply {
            tag = "${source.name}_time"
            typeface = Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.source_time_text))
            minLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val lastSync = TextView(context).apply {
            tag = "${source.name}_last_sync"
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.caption_text))
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(time, LinearLayout.LayoutParams(-1, -2))
        root.addView(lastSync, LinearLayout.LayoutParams(-1, -2))
        binding.sources.addView(root, GridLayout.LayoutParams(
            GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f)).apply {
            width = 0; height = -2; setGravity(Gravity.FILL_HORIZONTAL)
            setMargins(gap, gap, gap, gap)
        })
        Card(source, root, title, time, lastSync)
    }.toMutableList()

    init {
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.sourcesHeading, true)
        cards.forEach { card ->
            for (view in listOf(card.root, card.title, card.time, card.lastSync)) view.setOnLongClickListener { origin ->
                if (!visible || dragging != null) return@setOnLongClickListener false
                val drag = SourceDrag(this, dragGeneration, card.source, origin)
                dragging = drag
                val started = origin.startDragAndDrop(ClipData.newPlainText("source", card.source.name),
                    View.DragShadowBuilder(card.root), drag, 0)
                if (started) card.root.alpha = .5f else dragging = null
                started
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
            if (drag?.owner !== this) return@setOnDragListener false
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                cards.forEach { it.root.alpha = 1f }; dragging = null
                return@setOnDragListener true
            }
            if (!visible || drag.generation != dragGeneration) return@setOnDragListener false
            if (event.action == DragEvent.ACTION_DROP) {
                val target = cards.firstOrNull { card ->
                    event.x >= card.root.left && event.x < card.root.right &&
                        event.y >= card.root.top && event.y < card.root.bottom
                } ?: return@setOnDragListener false
                reorder(drag.source, target.source)
            }
            true
        }
        binding.syncInterval.setText(String.format(java.util.Locale.ROOT, "%d", store.source().intervalMillis))
        binding.syncNow.setOnClickListener { saveAndSync() }
        binding.syncInterval.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { saveAndSync(); true } else false
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            dragGeneration++
            dragging?.origin?.cancelDragAndDrop()
            cards.forEach { it.root.alpha = 1f }; dragging = null
        }
        main.removeCallbacks(refresh)
        app.observeAllTimes(this, value)
        if (value) refresh.run() else {
            binding.syncInterval.clearFocus()
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }

    private fun select(source: BuiltinTimeSource) {
        if (!settings.select(source)) {
            Toast.makeText(context, R.string.task_active, Toast.LENGTH_SHORT).show()
            render(); return
        }
        render()
    }

    private fun saveAndSync() {
        when (settings.saveAndSync(binding.syncInterval.text.toString())) {
            TimeSettingsResult.ACTIVE_TASK -> {
                Toast.makeText(context, R.string.task_active, Toast.LENGTH_SHORT).show(); return
            }
            TimeSettingsResult.INVALID_INTERVAL -> {
                binding.syncInterval.error = context.getString(R.string.interval_error); return
            }
            TimeSettingsResult.SAVED -> Unit
        }
        binding.syncInterval.error = null
        binding.syncInterval.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.root.windowToken, 0)
        Toast.makeText(context, R.string.sync_saved, Toast.LENGTH_SHORT).show()
    }

    private fun moveBy(source: BuiltinTimeSource, delta: Int): Boolean {
        val target = cards.getOrNull(cards.indexOfFirst { it.source == source } + delta) ?: return false
        reorder(source, target.source)
        return true
    }
    private fun reorder(source: BuiltinTimeSource, target: BuiltinTimeSource) {
        val before = cards.map { it.source }
        val next = SourceOrder.move(before, source, target)
        if (next == before) return
        val reordered = next.map { item -> cards.first { it.source == item } }
        cards.clear(); cards.addAll(reordered)
        binding.sources.removeAllViews()
        val gap = context.resources.getDimensionPixelSize(R.dimen.source_gap)
        cards.forEachIndexed { index, card ->
            binding.sources.addView(card.root, GridLayout.LayoutParams(
                GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f)).apply {
                width = 0; height = -2; setGravity(Gravity.FILL_HORIZONTAL)
                setMargins(gap, gap, gap, gap)
            })
        }
        store.saveSourceOrder(next)
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
            // Keep the signed offset together when two columns cannot fit the whole line.
            val available = card.time.width - card.time.compoundPaddingLeft - card.time.compoundPaddingRight
            val display = if ('(' in text && available > 0 && card.time.paint.measureText(text) > available)
                text.replace("(", "\n(") else text
            if (card.time.text != display) card.time.text = display
        }
        val manager = app.timeSync
        val state = manager.state
        val summary = context.getString(R.string.source_reference, names[selected.ordinal], context.getString(when {
            selected == BuiltinTimeSource.LOCAL -> R.string.source_local_ready
            state.syncing -> R.string.syncing
            state.failed -> R.string.sync_failed
            manager.hasFreshSample() -> R.string.synced
            manager.reference().synced -> R.string.source_stale
            else -> R.string.source_waiting
        }))
        if (binding.status.text != summary) binding.status.text = summary
        binding.syncNow.isEnabled = !activeTask
        binding.syncInterval.isEnabled = !activeTask
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            render()
            main.postDelayed(this, 100)
        }
    }
}
