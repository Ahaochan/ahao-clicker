package com.ahao.clicker.time

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.ahao.clicker.R
import com.ahao.clicker.databinding.TimeIntervalPanelBinding

/** Interval editing is independent of the comparison page and owns no sync subscription. */
internal class TimeIntervalPanel(private val binding: TimeIntervalPanelBinding,
    private val settings: TimeSettingsController, initialInterval: Long) {
    private val context = binding.root.context

    init {
        binding.syncInterval.setText(String.format(java.util.Locale.ROOT, "%d", initialInterval))
        binding.syncNow.setOnClickListener { saveAndSync() }
        binding.syncInterval.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { saveAndSync(); true } else false
        }
    }

    fun setEditable(value: Boolean) {
        binding.syncInterval.isEnabled = value
        binding.syncNow.isEnabled = value
    }

    fun setVisible(value: Boolean) {
        if (!value) dismissKeyboard()
    }

    private fun dismissKeyboard() {
        binding.syncInterval.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.root.windowToken, 0)
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
        dismissKeyboard()
        Toast.makeText(context, R.string.sync_saved, Toast.LENGTH_SHORT).show()
    }
}
