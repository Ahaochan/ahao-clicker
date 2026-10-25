package com.ahao.clicker

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.widget.*
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ahao.clicker.databinding.ActivityMainBinding
import com.ahao.clicker.core.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var format: Spinner
    private lateinit var periodic: CheckBox
    private lateinit var syncStatus: TextView
    private lateinit var taskStatus: TextView
    private val main = Handler(Looper.getMainLooper())
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
    private var recordedVersion = ""
    private val sync get() = (application as ClickerApp).timeSync

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.title, true)

        val contentPadding = resources.getDimensionPixelSize(R.dimen.content_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                safeArea.left + contentPadding,
                safeArea.top + contentPadding,
                safeArea.right + contentPadding,
                safeArea.bottom + contentPadding,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        store = SettingsStore(this)
        buildForm()
        savedInstanceState?.let { state ->
            fields.forEach { (key, input) -> state.getString("input_$key")?.let(input::setText) }
            format.setSelection(state.getInt("format", format.selectedItemPosition))
            recordedVersion = state.getString("recordedVersion", recordedVersion)
        }
    }

    private fun buildForm() {
        heading(R.string.time_source)
        input("url", R.string.source_url, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        format = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.time_formats))
            setSelection(TimeFormat.entries.indexOfFirst { it.name == store.get("format", "HTTP_DATE") }.coerceAtLeast(0))
            contentDescription = getString(R.string.source_format)
        }
        binding.form.addView(format)
        input("field", R.string.source_field, "timestamp")
        input("interval", R.string.sync_interval, "60000", InputType.TYPE_CLASS_NUMBER)
        periodic = CheckBox(this).apply {
            setText(R.string.periodic_sync)
            isChecked = store.get("periodic") == "true"
        }
        binding.form.addView(periodic)
        periodic.setOnCheckedChangeListener { _, checked ->
            if (!checked) { sync.pause(); store.save(mapOf("periodic" to "false")) }
            else try { saveTime(); sync.configure(store.source(), true) }
            catch (_: Exception) {
                periodic.isChecked = false
                Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show()
            }
        }
        button(R.string.save_source) { saveTime(); sync.configure(store.source(), periodic.isChecked) }
        button(R.string.sync_now) { saveTime(); sync.syncNow(store.source(), periodic.isChecked) }
        syncStatus = text(R.string.status_idle)

        heading(R.string.click_task)
        text(R.string.step_help)
        input("steps", R.string.steps, "300,600,100", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2
            maxLines = 6
            isVerticalScrollBarEnabled = true
        }
        input("count", R.string.click_count, "10", InputType.TYPE_CLASS_NUMBER)
        input("start", R.string.start_time, "")
        input("end", R.string.end_time, "")
        if (fields.getValue("start").text.isBlank() || fields.getValue("end").text.isBlank()) setWindow()
        button(R.string.fresh_window) { setWindow() }
        text(R.string.record_hint)
        button(R.string.record_points) {
            saveForm()
            val service = service()
            service.record()
            moveTaskToBack(true)
        }
        button(R.string.start_task) {
            val steps = StepText.parse(value("steps"))
            val start = parseDate(value("start"))
            val end = parseDate(value("end"))
            saveForm()
            service().startTask(steps, value("count").toInt(), start, end)
            moveTaskToBack(true)
        }
        button(R.string.stop) { ClickService.current?.stopAll() }
        taskStatus = text(R.string.status_idle)
        button(R.string.accessibility_settings) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_settings)
                .setMessage(R.string.accessibility_disclosure)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.agree_open) { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .show()
        }
        recordedVersion = store.get("recordedAt")
    }

    private fun service(): ClickService = checkNotNull(ClickService.current) { getString(R.string.enable_service) }
    private fun value(key: String) = fields.getValue(key).text.toString().trim()
    private fun saveForm() = store.save(listOf("steps", "count", "start", "end").associateWith { value(it) })
    private fun saveTime() {
        val values = listOf("url", "field", "interval").associateWith { value(it) }.toMutableMap()
        val selected = TimeFormat.entries[format.selectedItemPosition]
        TimeSourceConfig(value("url"), selected, value("field"), value("interval").toLong())
        values["format"] = selected.name
        values["periodic"] = periodic.isChecked.toString()
        store.save(values)
    }
    private fun parseDate(value: String) = LocalDateTime.parse(value, dateFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun formatted(epoch: Long) = dateFormat.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))
    private fun setWindow() {
        val now = sync.reference().now(SystemClock.elapsedRealtime())
        fields.getValue("start").setText(formatted(now + 3_000))
        fields.getValue("end").setText(formatted(now + 13_000))
    }

    private fun input(key: String, label: Int, fallback: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val name = text(label)
        return EditText(this).apply {
            id = View.generateViewId()
            name.labelFor = id
            inputType = type
            filters = arrayOf(InputFilter.LengthFilter(if (key == "steps") 64_000 else 2_048))
            setText(store.get(key, fallback))
            fields[key] = this
            binding.form.addView(this)
        }
    }
    private fun text(label: Int): TextView = TextView(this).apply {
        setText(label)
        setTextColor(getColor(R.color.text_primary))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.body_text))
        binding.form.addView(this)
    }
    private fun heading(label: Int) { text(label).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.heading_text))
        ViewCompat.setAccessibilityHeading(this, true)
    } }
    private fun button(label: Int, action: () -> Unit) {
        binding.form.addView(Button(this).apply { setText(label); setOnClickListener { guarded(action) } })
    }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            Toast.makeText(this, if (error is IllegalStateException && ClickService.current == null) R.string.enable_service else R.string.invalid_settings, Toast.LENGTH_LONG).show()
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime()
            val reference = sync.reference()
            val state = sync.state
            val mode = getString(when { state.syncing -> R.string.syncing; state.failed -> R.string.sync_failed; reference.synced -> R.string.synced; else -> R.string.device_time })
            val details = if (state.syncing || state.failed) getString(R.string.time_origin, mode,
                getString(if (reference.synced) R.string.synced else R.string.device_time)) else mode
            val age = if (state.lastSuccessElapsed < 0) -1 else (elapsed - state.lastSuccessElapsed) / 1_000
            syncStatus.text = getString(R.string.time_status, formatted(reference.now(elapsed)), details, state.rttMillis, age)
            taskStatus.text = ClickService.current?.statusText() ?: getString(R.string.enable_service)
            main.postDelayed(this, 250)
        }
    }
    override fun onResume() {
        super.onResume()
        val version = store.get("recordedAt")
        if (recordedVersion != version) {
            fields.getValue("steps").setText(store.get("steps"))
            fields.getValue("count").setText(store.get("count", "1"))
            recordedVersion = version
        }
        main.post(refresh)
    }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresh) }
    override fun onSaveInstanceState(outState: Bundle) {
        fields.forEach { (key, input) -> outState.putString("input_$key", input.text.toString()) }
        outState.putInt("format", format.selectedItemPosition)
        outState.putString("recordedVersion", recordedVersion)
        super.onSaveInstanceState(outState)
    }
}
