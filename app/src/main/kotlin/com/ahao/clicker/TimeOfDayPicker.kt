package com.ahao.clicker

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ahao.clicker.core.NextTriggerTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object TimeOfDayPicker {
    fun show(context: Context, seed: Long, now: () -> Long, windowType: Int? = null,
        dismissed: () -> Unit = {}, failed: (Throwable) -> Unit = {
            Toast.makeText(context, it.message ?: context.getString(R.string.invalid_settings), Toast.LENGTH_LONG).show()
        }, selected: (Long) -> Unit): AlertDialog {
        val zone = ZoneId.systemDefault()
        val value = Instant.ofEpochMilli(seed).atZone(zone).toLocalTime()
        val labels = listOf(R.string.choose_hour, R.string.choose_minute, R.string.choose_second, R.string.choose_millisecond)
        val maxima = listOf(23, 59, 59, 999)
        val values = listOf(value.hour, value.minute, value.second, value.nano / 1_000_000)
        val pickers = ArrayList<NumberPicker>(4)
        val row = LinearLayout(context)
        labels.forEachIndexed { index, label ->
            val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val picker = NumberPicker(context).apply {
                id = View.generateViewId()
                minValue = 0; maxValue = maxima[index]
                setFormatter { it.toString().padStart(if (index == 3) 3 else 2, '0') }
                this.value = values[index]
                wrapSelectorWheel = true
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                contentDescription = context.getString(label)
            }
            cell.addView(TextView(context).apply {
                setText(label); gravity = Gravity.CENTER; labelFor = picker.id
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.body_text))
            })
            cell.addView(picker, LinearLayout.LayoutParams(-1, context.resources.getDimensionPixelSize(R.dimen.time_wheel_height)))
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            pickers.add(picker)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
            setPadding(padding, padding, padding, padding)
            addView(row)
            addView(TextView(context).apply {
                setText(R.string.time_of_day_hint)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.caption_text))
            })
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.pick_time)
            .setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        windowType?.let { dialog.window?.setType(it) }
        dialog.setOnDismissListener { dismissed() }
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val time = LocalTime.of(pickers[0].value, pickers[1].value, pickers[2].value, pickers[3].value * 1_000_000)
                    selected(NextTriggerTime.resolve(now(), time, zone))
                    dialog.dismiss()
                }.onFailure(failed)
            }
        }
        dialog.show()
        return dialog
    }
}
