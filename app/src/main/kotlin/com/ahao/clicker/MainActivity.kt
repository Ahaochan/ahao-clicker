package com.ahao.clicker

import android.app.AlertDialog
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.ahao.clicker.databinding.ActivityMainBinding
import com.ahao.clicker.time.TimeSyncPanel

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var timePanel: TimeSyncPanel
    private var resumed = false
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as ClickerApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySafeInsets()
        timePanel = TimeSyncPanel(binding.timePage, app)
        savedInstanceState?.getString("timeInterval")?.let(binding.timePage.syncInterval::setText)
        binding.tabs.setOnCheckedChangeListener { _, _ -> showTab() }
        binding.tabs.check(savedInstanceState?.getInt("selectedTab", R.id.tab_click) ?: R.id.tab_click)
        showTab()
        binding.showClock.setOnClickListener {
            if (accessibilityReady()) {
                try { ClickService.current?.showTimedOverlay() }
                catch (error: Exception) { Toast.makeText(this, error.message ?: getString(R.string.invalid_settings), Toast.LENGTH_LONG).show() }
            } else openAccessibilitySettings()
        }
        binding.accessibilitySettings.setOnClickListener { openAccessibilitySettings() }
        binding.showRecordingOverlay.setOnClickListener {
            if (accessibilityReady()) {
                try { ClickService.current?.showMultiPointOverlay() }
                catch (error: Exception) { Toast.makeText(this, error.message ?: getString(R.string.invalid_settings), Toast.LENGTH_LONG).show() }
            } else openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this).setTitle(R.string.accessibility_settings)
            .setMessage(R.string.accessibility_disclosure).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.agree_open) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
    }
    private fun accessibilityReady(): Boolean {
        if (ClickService.current == null) return false
        val own = ComponentName(this, ClickService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val service = info.resolveInfo?.serviceInfo
                service != null && ComponentName(service.packageName, service.name) == own &&
                    info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
            }
    }
    private fun updateSetupStatus() {
        val ready = accessibilityReady()
        val color = getColor(if (ready) R.color.status_ready else R.color.status_unavailable)
        fun update(view: TextView, label: Int) {
            val text = getString(label)
            if (view.text != text) view.text = text
            if (view.currentTextColor != color) view.setTextColor(color)
        }
        update(binding.accessibilityStatus, if (ready) R.string.setup_accessibility_ready else R.string.setup_accessibility_unavailable)
        update(binding.overlayStatus, if (ready) R.string.setup_overlay_ready else R.string.setup_overlay_unavailable)
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || binding.tabs.checkedRadioButtonId != R.id.tab_click) return
            updateSetupStatus()
            binding.showClock.isEnabled = ClickService.current?.hasActiveTask != true
            main.postDelayed(this, 500)
        }
    }
    override fun onResume() {
        super.onResume(); resumed = true; app.observeTime(this, true); showTab()
    }
    private fun showTab() {
        val time = binding.tabs.checkedRadioButtonId == R.id.tab_time
        binding.clickPage.visibility = if (time) View.GONE else View.VISIBLE
        binding.timePage.root.visibility = if (time) View.VISIBLE else View.GONE
        timePanel.setVisible(time && resumed)
        main.removeCallbacks(refresh)
        if (resumed && !time) refresh.run()
    }
    override fun onPause() {
        resumed = false; timePanel.setVisible(false)
        main.removeCallbacks(refresh); app.observeTime(this, false); super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedTab", binding.tabs.checkedRadioButtonId)
        outState.putString("timeInterval", binding.timePage.syncInterval.text.toString())
        super.onSaveInstanceState(outState)
    }
}
