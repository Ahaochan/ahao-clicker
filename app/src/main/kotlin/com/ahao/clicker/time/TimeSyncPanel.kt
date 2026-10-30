package com.ahao.clicker.time

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
import com.ahao.clicker.databinding.TimeSyncPanelBinding
import java.time.ZoneId

/** Time-page UI owns its own refresh callback; no Activity is retained by the network layer. */
class TimeSyncPanel(private val binding: TimeSyncPanelBinding, private val app: ClickerApp) {
    private val context = binding.root.context
    private val store = SettingsStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val zone = ZoneId.systemDefault()
    private val names = context.resources.getStringArray(R.array.builtin_sources)
    private var visible = false
    private data class Card(val source: BuiltinTimeSource, val root: View, val title: RadioButton, val time: TextView)
    private val cards = listOf(BuiltinTimeSource.JD, BuiltinTimeSource.TAOBAO,
        BuiltinTimeSource.ALIYUN, BuiltinTimeSource.TENCENT, BuiltinTimeSource.CLOUDFLARE).mapIndexed { index, source ->
        val padding = context.resources.getDimensionPixelSize(R.dimen.control_padding_vertical)
        val gap = context.resources.getDimensionPixelSize(R.dimen.source_gap)
        val root = LinearLayout(context).apply {
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
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(time, LinearLayout.LayoutParams(-1, -2))
        binding.sources.addView(root, GridLayout.LayoutParams(
            GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f)).apply {
            width = 0; height = -2; setGravity(Gravity.FILL_HORIZONTAL)
            setMargins(gap, gap, gap, gap)
        })
        Card(source, root, title, time)
    }

    init {
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.sourcesHeading, true)
        binding.syncInterval.setText(String.format(java.util.Locale.ROOT, "%d", store.source().intervalMillis))
        binding.syncNow.setOnClickListener { saveAndSync() }
        binding.syncInterval.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { saveAndSync(); true } else false
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        main.removeCallbacks(refresh)
        app.observeAllTimes(this, value)
        if (value) main.post(refresh) else {
            binding.syncInterval.clearFocus()
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }

    private fun select(source: BuiltinTimeSource) {
        if (ClickService.current?.hasActiveTask == true) {
            Toast.makeText(context, R.string.task_active, Toast.LENGTH_SHORT).show()
            render(); return
        }
        store.save(mapOf("source" to source.name))
        app.refreshTimeSources()
        render()
    }

    private fun saveAndSync() {
        if (ClickService.current?.hasActiveTask == true) {
            Toast.makeText(context, R.string.task_active, Toast.LENGTH_SHORT).show(); return
        }
        val interval = binding.syncInterval.text.toString().toLongOrNull()
        if (interval == null || interval !in 1_000..86_400_000) {
            binding.syncInterval.error = context.getString(R.string.interval_error); return
        }
        binding.syncInterval.error = null
        store.save(mapOf("syncInterval" to interval.toString()))
        app.refreshTimeSources()
        app.timeSources.syncAll(interval)
        binding.syncInterval.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.root.windowToken, 0)
        Toast.makeText(context, R.string.sync_saved, Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val elapsed = SystemClock.elapsedRealtime()
        val device = System.currentTimeMillis()
        val activeTask = ClickService.current?.hasActiveTask == true
        val selected = app.timeSources.selectedSource
        for (card in cards) {
            val manager = app.timeSources.manager(card.source)
            val state = manager.state
            val reference = manager.reference()
            val qualifier = when {
                reference.synced && elapsed - state.lastSuccessElapsed > 300_000 -> R.string.source_stale
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
