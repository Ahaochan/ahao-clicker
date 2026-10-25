package com.ahao.clicker.acceptance

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.ahao.clicker.*
import com.ahao.clicker.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** No test framework dependency; exercises the app against a separate receipt process. */
@android.annotation.TargetApi(29)
class MvpAcceptance : Instrumentation() {
    private lateinit var options: Bundle
    private lateinit var automation: UiAutomation
    private lateinit var activity: MainActivity
    private val cases = JSONArray()
    private val evidence = JSONObject()
    private val target = Uri.parse("content://com.ahao.clicker.acceptance.target")
    private val date = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val sync get() = (targetContext.applicationContext as ClickerApp).timeSync
    private val base get() = "https://127.0.0.1:${options.getString("timePort") }"
    private var previousServices = ""
    private var previousEnabled = "0"

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }

    override fun onStart() {
        val ssl = HttpsURLConnection.getDefaultSSLSocketFactory()
        val preferences = targetContext.getSharedPreferences("clicker", 0)
        val savedPreferences = preferences.all
        var success = false
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            targetContext.filesDir.resolve("mvp-failure.png").delete()
            previousServices = shell("settings get secure enabled_accessibility_services").trim().let { if (it == "null") "" else it }
            previousEnabled = shell("settings get secure accessibility_enabled").trim().let { if (it == "null") "0" else it }
            val certificate = context.assets.open("acceptance.crt").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("local-acceptance", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            // Trust a generated CA only inside this test process; normal hostname/PKIX validation stays on.
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            serviceEnabled(false)
            openMain()
            if (options.getString("only") == "process_death") {
                serviceEnabled(true)
                showTarget(); receipt("reset")
                configure(step("A", 250), 100, 1200, 15000); launchTask()
                await("Process-death task did not start") { count() >= 2 }
                // Restore the user's configuration before intentionally killing this process.
                preferences.edit().clear().apply { savedPreferences.forEach { (key, value) -> putString(key, value as String) } }.commit()
                targetContext.filesDir.resolve("mvp-death-checkpoint.json").writeText(JSONObject()
                    .put("oldPid", android.os.Process.myPid()).put("receivedBeforeDeath", count()).toString())
                android.os.Process.killProcess(android.os.Process.myPid())
                error("Process termination returned")
            }
            case("permission_and_disclosure") { permissionAndDisclosure() }
            case("https_time_formats_periodic_failure_recovery") { timeSources() }
            serviceEnabled(true)
            case("single_point_count_and_start_time") { singlePoint() }
            case("deadline_limits_count") { deadline() }
            case("actual_recording_and_multistep_replay") { recordingAndReplay() }
            case("recording_repeat_and_cancel") { repeatRecording() }
            case("floating_stop_waiting_and_running") { stopPaths() }
            case("rotation_screen_off_and_service_disconnect") { interruptions() }
            case("source_change_clears_old_time") { sourceChange() }
            check(cases.length() > 0) { "Unknown acceptance case" }
            success = true
        } catch (error: Throwable) {
            runCatching { saveScreenshot("mvp-failure.png") }
            runCatching { ui { evidence.put("failureService", ClickService.current?.statusText() ?: "disconnected") }; evidence.put("failureReceipts", events()) }
            runCatching { evidence.put("failureRecordedSteps", SettingsStore(targetContext).get("steps")) }
            runCatching { evidence.put("failureTargetTouches", JSONArray(receipt().getString("touches", "[]"))) }
            runCatching { ui { evidence.put("windows", JSONArray(WindowInspector.getGlobalWindowViews().map { "${it.visibility}/${it.isShown}/${it.layoutParams}" })) } }
            cases.put(JSONObject().put("name", "failure").put("error", android.util.Log.getStackTraceString(error)))
        } finally {
            if (::automation.isInitialized) {
                runCatching { ui { ClickService.current?.stopAll() }; sync.pause() }
                runCatching { shell("settings put system user_rotation 0"); shell("settings put system accelerometer_rotation 1"); shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
                runCatching { writeServices(previousServices); shell("settings put secure accessibility_enabled $previousEnabled") }
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl)
            preferences.edit().clear().apply {
                savedPreferences.forEach { (key, value) -> putString(key, value as String) }
            }.commit()
            val report = JSONObject().put("passed", success).put("cases", cases).put("evidence", evidence).toString()
            targetContext.filesDir.resolve("mvp-acceptance.json").writeText(report)
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("acceptance_json", report) })
        }
    }

    private fun case(name: String, test: () -> Unit) {
        if (options.getString("only", "").let { it.isNotBlank() && it != name }) return
        android.util.Log.i("MvpAcceptance", name)
        sendStatus(1, Bundle().apply { putString("case", name) })
        val before = SystemClock.elapsedRealtime()
        test()
        cases.put(JSONObject().put("name", name).put("passed", true).put("durationMillis", SystemClock.elapsedRealtime() - before))
    }
    private fun saveScreenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            targetContext.filesDir.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun ui(action: () -> Unit) = runOnMainSync(Runnable(action))
    private fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { message }
            Thread.sleep(40)
        }
    }
    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun button(id: Int): Button = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == targetContext.getString(id) }
    private fun edit(id: Int): EditText {
        val label = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == targetContext.getString(id) }
        return activity.findViewById(label.labelFor)
    }
    private fun openMain() {
        activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("Main activity did not receive focus") { activity.hasWindowFocus() }
    }
    private fun receipt(method: String = "state") = checkNotNull(targetContext.contentResolver.call(target, method, null, null))
    private fun events() = JSONArray(receipt().getString("events", "[]"))
    private fun count() = receipt().getInt("count")
    private fun point(name: String) = checkNotNull(receipt().getIntArray(name))
    private fun showTarget() {
        targetContext.startActivity(Intent().setComponent(ComponentName("com.ahao.clicker.acceptance.target", "com.ahao.clicker.acceptance.target.TargetActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Target coordinates or input focus missing", 15000) { receipt().let { it.containsKey("C") && it.getBoolean("focused") } }
        Thread.sleep(200)
    }
    private fun touch(x: Int, y: Int) {
        shell("input touchscreen swipe $x $y $x $y 80")
    }
    private fun touchPoint(name: String) { val p = point(name); touch(p[0], p[1]) }
    private fun captureReady() {
        await("Recording capture window did not become ready") {
            var ready = false
            ui { ready = WindowInspector.getGlobalWindowViews().any {
                it.isShown && it.contentDescription == targetContext.getString(R.string.record_hint) &&
                    (it.layoutParams as android.view.WindowManager.LayoutParams).let { p -> p.alpha == 1f && p.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 }
            } }
            ready
        }
        Thread.sleep(150) // Allow the published input-window update to reach system_server.
    }
    private fun overlayButton(id: Int) {
        var xy = intArrayOf()
        ui {
            val found = WindowInspector.getGlobalWindowViews().asSequence()
                .filter { (it.layoutParams as android.view.WindowManager.LayoutParams).type == android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
                .flatMap(::descendants).filterIsInstance<Button>()
                .first { it.isShown && it.text.toString() == targetContext.getString(id) }
            val p = IntArray(2); found.getLocationOnScreen(p); xy = intArrayOf(p[0] + found.width / 2, p[1] + found.height / 2)
        }
        touch(xy[0], xy[1])
        evidence.put("lastFloatingControl", JSONObject().put("text", targetContext.getString(id)).put("x", xy[0]).put("y", xy[1]))
    }
    private fun serviceEnabled(enabled: Boolean) {
        val component = "com.ahao.clicker/com.ahao.clicker.ClickService"
        val services = previousServices.split(':').filter { it.isNotBlank() && it != component }.toMutableList()
        if (enabled) services.add(component)
        writeServices(services.joinToString(":"))
        shell("settings put secure accessibility_enabled ${if (services.isEmpty()) 0 else 1}")
        check(shell("settings get secure enabled_accessibility_services").trim() == services.joinToString(":").ifEmpty { "null" })
        await("Accessibility connection did not change") { (ClickService.current != null) == enabled }
    }
    private fun writeServices(services: String) {
        if (services.isBlank()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $services")
    }
    private fun permissionAndDisclosure() {
        check(ClickService.current == null) { "Start acceptance with this service disabled" }
        ui { button(R.string.record_points).performClick() }
        check(ClickService.current == null)
        ui { button(R.string.accessibility_settings).performClick() }
        ui {
            val roots = WindowInspector.getGlobalWindowViews()
            check(roots.asSequence().flatMap(::descendants).filterIsInstance<TextView>().any { it.text.toString().contains("录制时会真实执行点击") })
            roots.asSequence().flatMap(::descendants).filterIsInstance<Button>().first { it.id == android.R.id.button2 }.performClick()
        }
        val monitor = addMonitor(IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS), ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            ui { button(R.string.accessibility_settings).performClick() }
            ui { WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<Button>().first { it.id == android.R.id.button1 }.performClick() }
            await("Disclosure did not open accessibility settings", 2500) { monitor.hits > 0 }
        } finally { removeMonitor(monitor) }
        openMain()
    }

    private fun request(path: String): JSONObject {
        val connection = java.net.URL(base + path).openConnection() as HttpsURLConnection
        connection.connectTimeout = 3000; connection.readTimeout = 4000
        return try { connection.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { connection.disconnect() }
    }
    private fun source(path: String, format: Int = 1, periodic: Boolean = false) {
        ui {
            edit(R.string.source_url).setText(base + path)
            edit(R.string.source_field).setText("data.timestamp")
            edit(R.string.sync_interval).setText("1000")
            descendants(activity.window.decorView).filterIsInstance<Spinner>().first().setSelection(format)
            button(R.string.save_source).performClick()
        }
        if (periodic) ui { descendants(activity.window.decorView).filterIsInstance<android.widget.CheckBox>().first().isChecked = true }
    }
    private fun synchronize() {
        val old = sync.state.lastSuccessElapsed
        ui { button(R.string.sync_now).performClick() }
        await("Time sync failed: " + sync.state, 8000) { sync.state.lastSuccessElapsed > old }
    }
    private fun timeSources() {
        request("/control?mode=good&offset=5000") // Warm TLS before measuring the app's bounded request.
        for ((path, format) in listOf("/millis" to 1, "/seconds" to 2, "/date" to 0)) {
            source(path, format); synchronize()
            val offset = sync.reference().now(SystemClock.elapsedRealtime()) - System.currentTimeMillis()
            // HttpsServer emits its own current Date header; JSON deliberately differs by +5 seconds.
            val range = if (format == 0) -1500L..1500L else 3300L..6500L
            check(sync.reference().synced && offset in range) { "Wrong external time offset: $offset, format=$format" }
        }
        source("/millis", periodic = true)
        await("Initial periodic sync missing") { sync.reference().synced && !sync.state.syncing }
        val first = sync.state.lastSuccessElapsed
        await("Configured periodic interval did not run", 6000) { sync.state.lastSuccessElapsed > first }
        val remembered = sync.state.lastSuccessElapsed
        request("/control?mode=error")
        await("HTTP error not reported") { sync.state.failed }
        check(sync.reference().synced && sync.state.lastSuccessElapsed >= remembered)
        val failedAt = sync.state.lastSuccessElapsed
        request("/control?mode=good")
        await("Periodic sync did not recover") { sync.state.lastSuccessElapsed > failedAt && !sync.state.failed }
        ui { descendants(activity.window.decorView).filterIsInstance<android.widget.CheckBox>().first().isChecked = false }
        Thread.sleep(300)
        val requests = request("/stats").getInt("requests")
        Thread.sleep(1300)
        check(request("/stats").getInt("requests") == requests) { "Periodic pause kept issuing requests" }
        for (failure in listOf("malformed", "oversize", "age", "slow")) {
            request("/control?mode=good"); synchronize()
            request("/control?mode=$failure")
            val lastSuccess = sync.state.lastSuccessElapsed
            ui { button(R.string.sync_now).performClick() }
            await("$failure response not rejected", 7000) { sync.state.failed && !sync.state.syncing }
            check(sync.state.lastSuccessElapsed == lastSuccess && sync.reference().synced)
        }
        request("/control?mode=good"); synchronize()
    }
    private fun configure(steps: String, total: Int, delay: Long, duration: Long): Long {
        openMain()
        val elapsed = SystemClock.elapsedRealtime()
        val start = sync.reference().now(elapsed) + delay
        ui {
            edit(R.string.steps).setText(steps); edit(R.string.click_count).setText(total.toString())
            edit(R.string.start_time).setText(date.format(Instant.ofEpochMilli(start)))
            edit(R.string.end_time).setText(date.format(Instant.ofEpochMilli(start + duration)))
        }
        return elapsed + delay
    }
    private fun step(name: String, wait: Int): String { val p = point(name); return "${p[0]},${p[1]},$wait" }
    private fun launchTask() {
        ui { button(R.string.start_task).performClick() }
        await("Click target did not regain input focus") { receipt().getBoolean("focused") }
    }
    private fun phase(): RunPhase? { var value: RunPhase? = null; ui { value = ClickService.current?.status?.phase }; return value }
    private fun stableCount() { val before = count(); Thread.sleep(600); check(count() == before) { "Clicks continued after stop" } }

    private fun singlePoint() {
        showTarget(); receipt("reset")
        val scheduled = configure(step("A", 150), 5, 1500, 8000)
        launchTask()
        Thread.sleep(350); check(count() == 0) { "Click before start time" }
        await("Five target clicks missing", 10000) { phase() == RunPhase.COMPLETED }
        await("Five completed clicks did not reach target") { count() == 5 }
        val received = events(); check(received.length() == 5)
        evidence.put("singlePointReceipts", received)
        check(received.getJSONObject(0).getLong("elapsed") >= scheduled - 30)
        check((0 until 5).all { received.getJSONObject(it).getString("point") == "A" })
        stableCount()
    }
    private fun deadline() {
        showTarget(); receipt("reset")
        val scheduled = configure(step("A", 180), 100, 1200, 900)
        launchTask(); await("Deadline did not end task") { phase() == RunPhase.EXPIRED }
        Thread.sleep(200)
        val received = events(); check(received.length() in 1..5) { "Deadline count=${received.length()}" }
        evidence.put("deadlineReceipts", received)
        check((0 until received.length()).all { received.getJSONObject(it).getLong("elapsed") < scheduled + 1000 })
        stableCount()
    }
    private fun recordingAndReplay() {
        showTarget(); receipt("reset"); openMain()
        ui { button(R.string.record_points).performClick() }
        await("Recording target did not regain focus") { receipt().getBoolean("focused") }
        captureReady()
        saveScreenshot("mvp-recording.png")
        touchPoint("A"); await("Recorded A was not really clicked") { count() == 1 }; awaitRecorded(1); captureReady()
        touchPoint("B"); await("Recorded B was not really clicked") { count() == 2 }; awaitRecorded(2); captureReady()
        touchPoint("A"); await("Third recorded point missing") { count() == 3 }; awaitRecorded(3); captureReady()
        overlayButton(R.string.finish_recording); Thread.sleep(150)
        await("Finish recording control did not stop capture") { var stopped = false; ui { stopped = ClickService.current?.statusText() == targetContext.getString(R.string.status_idle) }; stopped }
        val recorded = StepText.parse(SettingsStore(targetContext).get("steps"))
        check(recorded.size == 3 && recorded[1].waitBeforeMillis >= 200 && recorded[2].waitBeforeMillis >= 400)
        check((0 until 3).map { events().getJSONObject(it).getString("point") } == listOf("A", "B", "A"))
        receipt("reset")
        configure(listOf(step("A", 150), step("B", 450), step("A", 200)).joinToString("\n"), 6, 1500, 10000)
        launchTask(); await("Replay did not complete", 12000) { phase() == RunPhase.COMPLETED }
        await("Completed replay did not reach target") { count() == 6 }
        val received = events(); check(received.length() == 6)
        check((0 until 6).map { received.getJSONObject(it).getString("point") } == listOf("A", "B", "A", "A", "B", "A"))
        val gaps = (1 until 6).map { received.getJSONObject(it).getLong("elapsed") - received.getJSONObject(it - 1).getLong("elapsed") }
        val waits = listOf(450, 200, 150, 450, 200)
        check(gaps.indices.all { gaps[it] >= waits[it] + 20 && gaps[it] <= waits[it] + 1200 }) { "Replay gaps=$gaps" }
        evidence.put("recordedSteps", StepText.format(recorded)).put("replayReceipts", received).put("replayGapsMillis", JSONArray(gaps))
        stableCount()
    }
    private fun awaitRecorded(count: Int) {
        await("Completed recording not persisted") {
            val store = SettingsStore(targetContext)
            store.get("count") == count.toString() && runCatching { StepText.parse(store.get("steps")).size == count }.getOrDefault(false)
        }
    }
    private fun repeatRecording() {
        showTarget(); receipt("reset"); openMain()
        ui { button(R.string.record_points).performClick() }
        await("Repeat recording did not return to target") { receipt().getBoolean("focused") }
        captureReady()
        repeat(12) { index ->
            touchPoint(if (index % 2 == 0) "A" else "B")
            await("Repeated recording lost point ${index + 1}") { count() == index + 1 }
            awaitRecorded(index + 1); captureReady()
        }
        evidence.put("repeatedRecordingReceipts", events())
        overlayButton(R.string.finish_recording)
        await("Repeat recording did not finish") { var idle = false; ui { idle = ClickService.current?.statusText() == targetContext.getString(R.string.status_idle) }; idle }
        stableCount()
        openMain(); ui { button(R.string.record_points).performClick() }
        await("Cancel recording did not return to target") { receipt().getBoolean("focused") }
        captureReady(); touchPoint("C"); overlayButton(R.string.stop)
        await("Stop left a capture window attached") {
            var attached = false
            ui { attached = WindowInspector.getGlobalWindowViews().any { it.contentDescription == targetContext.getString(R.string.record_hint) } }
            !attached
        }
        Thread.sleep(300); stableCount()
        Thread.sleep(700)
        ui { check(WindowInspector.getGlobalWindowViews().none { it.contentDescription == targetContext.getString(R.string.record_hint) }) }
    }
    private fun stopPaths() {
        showTarget(); receipt("reset"); configure(step("A", 180), 100, 1800, 10000)
        launchTask(); Thread.sleep(250); overlayButton(R.string.stop)
        await("Floating stop did not stop waiting task") { phase() == RunPhase.STOPPED }
        Thread.sleep(1900); check(count() == 0); check(phase() == RunPhase.STOPPED)
        configure(step("A", 250), 100, 1200, 10000); launchTask()
        await("Running stop task never clicked") { count() >= 2 }
        overlayButton(R.string.stop); await("Floating stop did not stop running task") { phase() == RunPhase.STOPPED }; stableCount()
    }
    private fun interruptions() {
        showTarget(); receipt("reset"); configure(step("A", 250), 100, 1200, 12000); launchTask()
        await("Rotation task did not start") { count() >= 2 }
        shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 1")
        await("Rotation did not stop task") { phase() == RunPhase.STOPPED }; stableCount()
        shell("settings put system user_rotation 0"); Thread.sleep(800)
        showTarget(); receipt("reset"); configure(step("A", 250), 100, 1200, 12000); launchTask()
        await("Screen-off task did not start") { count() >= 2 }; shell("input keyevent KEYCODE_SLEEP")
        await("Screen off did not stop task") { phase() == RunPhase.STOPPED }; stableCount()
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard"); Thread.sleep(500)
        showTarget(); receipt("reset"); configure(step("A", 250), 100, 1200, 12000); launchTask()
        await("Disconnect task did not start") { count() >= 2 }; serviceEnabled(false); stableCount()
        val before = count(); serviceEnabled(true); Thread.sleep(900)
        check(count() == before && phase() == RunPhase.STOPPED) { "Reconnect resumed an old task" }
    }
    private fun sourceChange() {
        openMain(); source("/different-source", 1)
        check(!sync.reference().synced) { "Source change retained the old reference" }
        synchronize(); check(sync.reference().synced)
    }
}
