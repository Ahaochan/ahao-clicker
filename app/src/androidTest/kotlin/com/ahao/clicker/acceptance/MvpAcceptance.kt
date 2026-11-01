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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.ahao.clicker.*
import com.ahao.clicker.time.*
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
    private var fixtureSource = TimeSourceConfig("https://example.invalid")
    private var fixturePeriodic = false
    private var classicPlan: ClickPlan? = null
    private val physical get() = options.getString("physical") == "true"
    private val physicalSkipped = setOf("permission_and_disclosure", "v3_setup_steps", "rotation_screen_off_and_service_disconnect", "v2_landscape_large_font_stop_visible", "v3_time_page_large_font_dark", "v16_feedback_interrupt")

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
            if (physical && options.getString("only") == "physical_device_permissions") {
                inspectDevicePermissions()
                success = true
                return
            }
            val certificate = context.assets.open("acceptance.crt").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("local-acceptance", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            // Trust a generated CA only inside this test process; normal hostname/PKIX validation stays on.
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            evidence.put("physicalDevice", physical)
            if (!physical) serviceEnabled(false)
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
            case("v3_setup_steps") { setupSteps() }
            case("permission_and_disclosure") { permissionAndDisclosure() }
            case("https_time_formats_periodic_failure_recovery") { timeSources() }
            case("v3_jd_options_protocol") { jdOptions() }
            if (options.getString("only") !in setOf("physical_pages", "v3_builtin_source_reachability", "v3_jd_live_source")) serviceEnabled(true)
            case("v7_two_step_entries") { twoStepEntries() }
            case("v12_multipoint_overlay") { multiPointOverlay() }
            case("v13_overlay_resize") { overlayResize() }
            case("v14_responsive_overlay") { responsiveOverlay() }
            case("v15_overlay_size_memory") { overlaySizeMemory() }
            case("v15_overlay_size_restart") { overlaySizeRestart() }
            case("v16_feedback_interrupt") { feedbackInterrupt() }
            case("v8_overlay_defaults_to_now") { overlayDefaultsToNow() }
            case("v9_long_wait_clock_discipline") { longWaitClockDiscipline() }
            case("v10_completed_clock_resynchronizes") { completedClockResynchronizes() }
            case("v10_background_clock_recovery") { backgroundClockRecovery() }
            case("v10_clock_recovers_after_stall") { clockRecoversAfterStall() }
            case("v11_clock_draw_freshness") { clockDrawFreshness() }
            case("single_point_count_and_start_time") { singlePoint() }
            case("deadline_limits_count") { deadline() }
            case("actual_recording_and_multistep_replay") { recordingAndReplay() }
            case("recording_repeat_and_cancel") { repeatRecording() }
            case("floating_stop_waiting_and_running") { stopPaths() }
            case("rotation_screen_off_and_service_disconnect") { interruptions() }
            case("source_change_clears_old_time") { sourceChange() }
            case("v3_independent_samples_and_cancellation") { independentSamples() }
            case("v3_same_display_preserves_point") { sameDisplayPreservesPoint() }
            case("v4_time_wheels_layout") { timeWheelsLayout() }
            case("v3_tabs_and_time_sources") { pickerAndSettings() }
            case("v2_crosshair_and_one_shot") { timedSingle() }
            case("v2_cancelled_one_shot_never_replays") { timedCancel() }
            case("v5_crosshair_cancel_before_dispatch") { cancelBeforeDispatch() }
            case("v2_floating_clock_lifecycle") { floatingClock() }
            case("v4_floating_play_stop") { floatingPlayStop() }
            case("v6_minimal_overlay_time") { minimalOverlayTime() }
            case("v2_landscape_large_font_stop_visible") { largeFontLayout() }
            case("v3_time_page_large_font_dark") { timePageLayout() }
            case("v3_builtin_source_reachability") { builtinSources() }
            case("v3_jd_live_source") { jdLiveSource() }
            check(cases.length() > 0) { "Unknown acceptance case" }
            success = true
        } catch (error: Throwable) {
            runCatching { saveScreenshot("mvp-failure.png") }
            runCatching { ui { evidence.put("failureService", ClickService.current?.statusText() ?: "disconnected") }; evidence.put("failureReceipts", events()) }
            runCatching { evidence.put("failureRecordedSteps", SettingsStore(targetContext).get("steps")) }
            runCatching { evidence.put("failureSavedPoint", JSONObject(listOf("pointX", "pointY", "pointDisplay").associateWith { SettingsStore(targetContext).get(it) })) }
            runCatching { evidence.put("failureSavedMultiPoints", SettingsStore(targetContext).get("multiPoints")) }
            runCatching { ui { evidence.put("failureOverlayFeedback", JSONArray(windowViews().filterIsInstance<TextView>()
                .filter { it.id == R.id.overlay_feedback }.map { it.text.toString() }.toList())) } }
            runCatching { evidence.put("failureTargetTouches", JSONArray(receipt().getString("touches", "[]"))) }
            runCatching { ui { evidence.put("windows", JSONArray(WindowInspector.getGlobalWindowViews().map { "${it.visibility}/${it.isShown}/${it.layoutParams}" })) } }
            cases.put(JSONObject().put("name", "failure").put("error", android.util.Log.getStackTraceString(error)))
        } finally {
            if (::automation.isInitialized) {
                runCatching { ui { ClickService.current?.stopAll() }; sync.pause() }
                if (!physical) {
                    runCatching { shell("settings put system user_rotation 0"); shell("settings put system accelerometer_rotation 1"); shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
                    runCatching { writeServices(previousServices); shell("settings put secure accessibility_enabled $previousEnabled") }
                }
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl)
            val retainedSizes = if (success && options.getString("only") == "v15_overlay_size_memory" &&
                options.getString("keepOverlaySizes") == "true")
                OverlayPanelKind.entries.associate { it.sizeKey to preferences.getString(it.sizeKey, "")!! } else emptyMap()
            preferences.edit().clear().apply {
                savedPreferences.forEach { (key, value) -> putString(key, value as String) }
                retainedSizes.forEach { (key, value) -> putString(key, value) }
            }.commit()
            val report = JSONObject().put("passed", success).put("cases", cases).put("evidence", evidence).toString()
            targetContext.filesDir.resolve("mvp-acceptance.json").writeText(report)
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("acceptance_json", report) })
        }
    }

    private fun case(name: String, test: () -> Unit) {
        if (physical && name in physicalSkipped) return
        val selected = options.getString("only", "")
        if (selected == "physical_pages") {
            if (name !in setOf("v3_tabs_and_time_sources", "v3_builtin_source_reachability")) return
        } else if (selected.isNotBlank() && selected != name) return
        android.util.Log.i("MvpAcceptance", name)
        sendStatus(1, Bundle().apply { putString("case", name) })
        val before = SystemClock.elapsedRealtime()
        test()
        cases.put(JSONObject().put("name", name).put("passed", true).put("durationMillis", SystemClock.elapsedRealtime() - before))
    }
    private fun inspectDevicePermissions() {
        shell("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        fun nodes(root: android.view.accessibility.AccessibilityNodeInfo): Sequence<android.view.accessibility.AccessibilityNodeInfo> = sequence {
            yield(root)
            for (i in 0 until root.childCount) root.getChild(i)?.let { yieldAll(nodes(it)) }
        }
        await("Developer settings did not open") { automation.rootInActiveWindow?.packageName == "com.android.settings" }
        val rows = linkedSetOf<String>()
        repeat(12) {
            val root = checkNotNull(automation.rootInActiveWindow)
            val visible = nodes(root).toList()
            visible.filter { it.text?.toString()?.contains("USB", ignoreCase = true) == true }.forEach { item ->
                rows.add(nodes(item.parent ?: item).filter { it.text != null || it.isCheckable }
                    .joinToString(" | ") { "${it.text ?: "switch"}${if (it.isCheckable) "=${it.isChecked}" else ""}" })
            }
            if (rows.any { it.contains("USB") && it.contains("安全") }) {
                evidence.put("developerUsbRows", JSONArray(rows.toList()))
                return
            }
            visible.firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(300)
        }
        evidence.put("developerUsbRows", JSONArray(rows.toList()))
    }
    private fun saveScreenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            targetContext.filesDir.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun ui(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync(Runnable { try { action() } catch (error: Throwable) { failure = error } })
        failure?.let { throw it }
    }
    private fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { message }
            Thread.sleep(40)
        }
    }
    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }.also { output -> check("SecurityException" !in output && "Permission denial" !in output) { "Device denied test command $command: $output" } }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun button(id: Int): Button = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == targetContext.getString(id) }
    private fun openMain() {
        activity = if (physical) {
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            try {
                shell("am start -n com.ahao.clicker/.MainActivity -f 0x10008000")
                checkNotNull(monitor.waitForActivityWithTimeout(15_000)) { "Main activity launch timed out" } as MainActivity
            } finally { removeMonitor(monitor) }
        } else startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("Main activity did not receive focus") { activity.hasWindowFocus() }
    }
    private fun receipt(method: String = "state") = checkNotNull(targetContext.contentResolver.call(target, method, null, null))
    private fun events() = JSONArray(receipt().getString("events", "[]"))
    private fun count() = receipt().getInt("count")
    private fun point(name: String) = checkNotNull(receipt().getIntArray(name))
    private fun showTarget() {
        // Recreating a target that is already focused races the new overlay's input-window publication.
        if (receipt().let { it.containsKey("C") && it.getBoolean("focused") }) {
            Thread.sleep(200)
            return
        }
        if (physical) shell("am start -n com.ahao.clicker.acceptance.target/.TargetActivity -f 0x10008000")
        else targetContext.startActivity(Intent().setComponent(ComponentName("com.ahao.clicker.acceptance.target", "com.ahao.clicker.acceptance.target.TargetActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
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
            ui { ready = ClickService.current?.statusText() == targetContext.getString(R.string.status_recording) && WindowInspector.getGlobalWindowViews().any {
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
                .flatMap(::descendants)
                .first { it.isShown && it.isClickable && (it is Button && it.text.toString() == targetContext.getString(id) ||
                    it.contentDescription?.toString() == targetContext.getString(id)) }
            val p = IntArray(2); found.getLocationOnScreen(p); xy = intArrayOf(p[0] + found.width / 2, p[1] + found.height / 2)
        }
        touch(xy[0], xy[1])
        evidence.put("lastFloatingControl", JSONObject().put("text", targetContext.getString(id)).put("x", xy[0]).put("y", xy[1]))
    }
    private fun serviceEnabled(enabled: Boolean) {
        if (physical) {
            check(enabled) { "Physical acceptance cannot disable the user's service" }
            sendStatus(1, Bundle().apply { putString("action", "Toggle the clicker accessibility service off/on while this test is running") })
            await("Enable the clicker accessibility service on the phone first", 90_000) { ClickService.current != null }
            return
        }
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
    private fun setupSteps() {
        fun status(id: Int): TextView = activity.findViewById(id)
        fun expect(ready: Boolean) {
            await("Setup availability did not become $ready", 2500) {
                var matches = false
                ui {
                    val a = status(R.id.accessibility_status)
                    val b = status(R.id.overlay_status)
                    val colorId = if (ready) R.color.status_ready else R.color.status_unavailable
                    val label = if (ready) "可用" else "不可用"
                    matches = a.text.startsWith(label) && b.text.startsWith(label) &&
                        a.currentTextColor == targetContext.getColor(colorId) && b.currentTextColor == targetContext.getColor(colorId)
                }
                matches
            }
        }
        serviceEnabled(false)
        expect(false)
        ui {
            val a = status(R.id.accessibility_status)
            val b = status(R.id.overlay_status)
            val controls = activity.findViewById<View>(R.id.show_clock)
            val locationA = IntArray(2); val locationB = IntArray(2); val locationC = IntArray(2)
            a.getLocationOnScreen(locationA); b.getLocationOnScreen(locationB); controls.getLocationOnScreen(locationC)
            check(locationA[1] < locationB[1] && locationB[1] < locationC[1]) { "Setup steps are out of order" }
            activity.findViewById<Button>(R.id.show_clock).performClick()
            val dialog = WindowInspector.getGlobalWindowViews().flatMap { descendants(it).toList() }
                .filterIsInstance<Button>().first { it.id == android.R.id.button2 }
            dialog.performClick()
        }
        val original = activity
        serviceEnabled(true)
        expect(true)
        check(activity === original && !activity.isFinishing) { "Availability required reopening the page" }
        check(!Settings.canDrawOverlays(targetContext)) { "Test requires ordinary overlay permission to remain denied" }
        ui { activity.findViewById<Button>(R.id.show_clock).performClick() }
        await("Step two did not display an accessibility overlay") {
            var shown = false
            ui { shown = WindowInspector.getGlobalWindowViews().any {
                it.isShown && (it.layoutParams as android.view.WindowManager.LayoutParams).type ==
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } }
            shown
        }
        ui { check(ClickService.current?.hasActiveTask == false); ClickService.current?.stopAll() }
        saveScreenshot("v3-setup-ready.png")
        serviceEnabled(false)
        expect(false)
        // Returning from another activity must also recheck, without recreating MainActivity.
        ui { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        await("Settings did not take focus") { !activity.hasWindowFocus() }
        serviceEnabled(true)
        shell("input keyevent KEYCODE_BACK")
        await("Main did not resume") { activity.hasWindowFocus() }
        expect(true)
        check(activity === original && !activity.isFinishing)
        serviceEnabled(false)
        expect(false)
        // A hidden click page must refresh immediately when selected again.
        ui { activity.findViewById<android.widget.RadioButton>(R.id.tab_time).performClick() }
        serviceEnabled(true)
        ui { activity.findViewById<android.widget.RadioButton>(R.id.tab_click).performClick() }
        expect(true)
        serviceEnabled(false)
        expect(false)
        saveScreenshot("v3-setup-unavailable.png")
        evidence.put("setupSteps", JSONObject().put("liveTransitions", "red-green-red")
            .put("ordinaryOverlayPermission", false).put("realAccessibilityOverlay", true)
            .put("clickTaskStarted", false).put("sameActivity", true).put("resumeRefresh", true))
    }
    private fun permissionAndDisclosure() {
        check(ClickService.current == null) { "Start acceptance with this service disabled" }
        ui {
            val record = activity.findViewById<Button>(R.id.show_recording_overlay)
            check(record.isEnabled)
            record.performClick()
            windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button2 && it.isShown }.performClick()
        }
        check(ClickService.current == null)
        ui { button(R.string.accessibility_settings).performClick() }
        ui {
            val roots = WindowInspector.getGlobalWindowViews()
            check(roots.asSequence().flatMap(::descendants).filterIsInstance<TextView>().any { it.text.toString().contains("播放按钮后才执行") })
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
        fixtureSource = TimeSourceConfig(base + path, TimeFormat.entries[format], "data.timestamp", 1_000)
        fixturePeriodic = periodic
        sync.configure(fixtureSource, periodic)
    }
    private fun synchronize() {
        val old = sync.state.lastSuccessElapsed
        sync.syncNow(fixtureSource, fixturePeriodic)
        await("Time sync failed: " + sync.state, 8000) { sync.state.lastSuccessElapsed > old }
    }
    private fun timeSources() {
        request("/control?mode=good&offset=5000") // Warm TLS before measuring the app's bounded request.
        for ((path, format) in listOf("/millis" to 1, "/seconds" to 2, "/date" to 0)) {
            source(path, format); synchronize()
            val expected = request("/millis").getJSONObject("data").getLong("timestamp")
            val offset = sync.reference().now(SystemClock.elapsedRealtime()) - expected
            // HttpsServer emits its own current Date header; JSON deliberately differs by +5 seconds.
            val range = if (format == 0) -6500L..-3300L else -1500L..1500L
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
        sync.pause(); fixturePeriodic = false
        Thread.sleep(300)
        val requests = request("/stats").getInt("requests")
        Thread.sleep(1300)
        check(request("/stats").getInt("requests") == requests) { "Periodic pause kept issuing requests" }
        for (failure in listOf("malformed", "oversize", "age", "slow")) {
            request("/control?mode=good"); synchronize()
            request("/control?mode=$failure")
            val lastSuccess = sync.state.lastSuccessElapsed
            sync.syncNow(fixtureSource, false)
            await("$failure response not rejected", 7000) { sync.state.failed && !sync.state.syncing }
            check(sync.state.lastSuccessElapsed == lastSuccess && sync.reference().synced)
        }
        request("/control?mode=good"); synchronize()
    }
    private fun jdOptions() {
        val manager = TimeSyncManager()
        val config = TimeSourceConfig(base + "/jd", TimeFormat.JD_REQUEST_ID, "X-API-Request-Id", 1_000)
        try {
            request("/control?mode=good&offset=800")
            val before = request("/stats").getInt("jdOptions")
            manager.syncNow(config, true)
            await("JD OPTIONS did not accept an empty 204 response") { manager.reference().synced }
            val first = manager.state.lastSuccessElapsed
            await("JD periodic OPTIONS did not run", 6_000) { manager.state.lastSuccessElapsed > first }
            check(request("/stats").getInt("jdOptions") >= before + 2) { "Wrong HTTP method" }
            val expected = request("/millis").getJSONObject("data").getLong("timestamp")
            val difference = manager.reference().now(SystemClock.elapsedRealtime()) - expected
            check(difference in -500..500) { "JD differs from fixture clock by $difference ms" }
            val offset = manager.reference().now(SystemClock.elapsedRealtime()) - System.currentTimeMillis()
            manager.pause()
            for (mode in listOf("missing", "malformed", "stale", "age", "error", "slow")) {
                val sample = manager.reference()
                request("/control?mode=$mode")
                manager.syncNow(config, false)
                await("JD $mode header was not rejected", 7_000) { manager.state.failed && !manager.state.syncing }
                check(manager.reference() == sample) { "JD $mode overwrote the last valid sample" }
            }
            request("/control?mode=good")
            val previous = manager.state.lastSuccessElapsed
            manager.syncNow(config, false)
            await("JD failed to recover") { manager.state.lastSuccessElapsed > previous }
            val sample = manager.reference()
            request("/control?mode=slow")
            manager.syncNow(config, true)
            await("JD cancellation request did not start") { manager.state.syncing }
            manager.pause()
            Thread.sleep(2_700)
            check(manager.reference() == sample && !manager.state.syncing && manager.state.nextAttemptElapsed == -1L)
            val requests = request("/stats").getInt("jdOptions")
            Thread.sleep(1_100)
            check(request("/stats").getInt("jdOptions") == requests)
            evidence.put("jdOptionsProtocol", JSONObject().put("empty204Accepted", true).put("method", "OPTIONS")
                .put("offsetMillis", offset).put("fixtureDifferenceMillis", difference)
                .put("failureModesRejected", JSONArray(listOf("missing", "malformed", "stale", "age", "error", "slow")))
                .put("cancelledResponseIgnored", true).put("periodicCancelled", true))
        } finally { manager.pause(); request("/control?mode=good&offset=5000") }
    }
    private fun configure(steps: String, total: Int, delay: Long, duration: Long): Long {
        openMain()
        val elapsed = SystemClock.elapsedRealtime()
        val start = sync.reference().now(elapsed) + delay
        ui {
            classicPlan = ClickPlan(StepText.parse(steps), total, start, start + duration)
        }
        return elapsed + delay
    }
    private fun step(name: String, wait: Int): String { val p = point(name); return "${p[0]},${p[1]},$wait" }
    private fun launchTask() {
        ui {
            val plan = checkNotNull(classicPlan)
            ClickService.current!!.startTask(plan.steps, plan.count, plan.startMillis, plan.endMillis)
            activity.moveTaskToBack(true)
        }
        await("Click target did not regain input focus") { receipt().getBoolean("focused") }
    }
    private fun phase(): RunPhase? { var value: RunPhase? = null; ui { value = ClickService.current?.status?.phase }; return value }
    private fun stableCount() {
        val before = count()
        val mayHaveInFlight = phase() == RunPhase.STOPPED
        // Stopping cancels future scheduling; an already injected UP / queued onClick cannot be undone.
        Thread.sleep(350)
        val drained = count()
        check(drained - before in 0..(if (mayHaveInFlight) 1 else 0)) { "More than the in-flight gesture arrived after stop" }
        Thread.sleep(600)
        check(count() == drained) { "Clicks continued after the in-flight gesture drained" }
    }

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
    // Engine regression only: the new recording entry remains disabled and has no listener.
    private fun startLegacyRecording() {
        ui { ClickService.current!!.record(); activity.moveTaskToBack(true) }
    }

    private fun recordingAndReplay() {
        showTarget(); receipt("reset"); openMain()
        startLegacyRecording()
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
        val touches = JSONArray(receipt().getString("touches", "[]"))
        val downs = (0 until touches.length()).map { touches.getJSONObject(it) }.filter { it.getInt("action") == 0 }
        val ups = (0 until touches.length()).map { touches.getJSONObject(it) }.filter { it.getInt("action") == 1 }
        check(downs.size == 6 && ups.size == 6)
        val inputGaps = (1 until 6).map { downs[it].getLong("eventUptime") - ups[it - 1].getLong("eventUptime") }
        check(inputGaps.indices.all { inputGaps[it] >= waits[it] - 20 && inputGaps[it] <= waits[it] + 1200 }) { "Input gaps=$inputGaps" }
        // Target onClick queueing can compress processing gaps; preserve those separately as observations.
        evidence.put("recordedSteps", StepText.format(recorded)).put("replayReceipts", received)
            .put("replayReceiptGapsMillis", JSONArray(gaps)).put("replayInputGapsMillis", JSONArray(inputGaps))
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
        startLegacyRecording()
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
        openMain(); startLegacyRecording()
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

    private fun windowViews(): Sequence<View> = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
    private fun dialogOk() {
        ui { windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button1 && it.isShown }.performClick() }
        Thread.sleep(300) // Let the modal input window detach before a real tap on the floating control.
    }
    private fun dialogCancel() {
        ui { windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button2 && it.isShown }.performClick() }
        await("Cancelled picker still visible") {
            var gone = false
            ui { gone = windowViews().none { it is android.widget.NumberPicker && it.isShown } }
            gone
        }
        Thread.sleep(300) // Wait for the modal input window's closing transition before real touch.
    }

    private fun openTriggerPicker() {
        await("Service not connected for floating picker") { ClickService.current != null }
        ui {
            if (windowViews().none { it.id == R.id.click_start_time && it.isShown }) ClickService.current!!.toggleClock()
        }
        await("Floating time row missing") {
            var found = false
            ui { found = windowViews().any { it.id == R.id.click_start_time && it.isShown } }
            found
        }
        ui { windowViews().first { it.id == R.id.click_start_time && it.isShown }.performClick() }
        await("Floating time picker did not open") {
            var found = false
            ui { found = windowViews().filterIsInstance<android.widget.NumberPicker>().count { it.isShown } == 4 }
            found
        }
    }

    private fun timeWheelsLayout() {
        val originalOrientation = targetContext.resources.configuration.orientation
        val font = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        try {
            for (orientation in 0..1) {
                shell("settings put system font_scale 1.5")
                shell("settings put system accelerometer_rotation 0")
                shell("settings put system user_rotation $orientation")
                await("Wheel configuration not applied") {
                    targetContext.resources.configuration.let {
                        it.fontScale == 1.5f && it.orientation == if (orientation == 0)
                            android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    }
                }
                openMain()
                openTriggerPicker()
                val saved = SettingsStore(targetContext).get("target")
                Thread.sleep(350)
                val point = IntArray(2)
                var height = 0
                ui {
                    val wheels = windowViews().filterIsInstance<android.widget.NumberPicker>().toList()
                    check(wheels.size == 4 && windowViews().none { it is android.widget.DatePicker })
                    val rectangles = wheels.map { wheel ->
                        android.graphics.Rect().also {
                            check(wheel.getGlobalVisibleRect(it) && it.width() == wheel.width && it.height() == wheel.height)
                        }
                    }
                    check(rectangles.zipWithNext().all { (a, b) -> a.right <= b.left && a.top == b.top })
                    wheels.last().apply { value = 0; getLocationOnScreen(point); point[0] += width / 2; height = this.height }
                }
                shell("input touchscreen swipe ${point[0]} ${point[1] + height * 3 / 4} ${point[0]} ${point[1] + height / 4} 350")
                await("Millisecond wheel did not scroll") {
                    var changed = false
                    ui { changed = windowViews().filterIsInstance<android.widget.NumberPicker>().last().value != 0 }
                    changed
                }
                Thread.sleep(350)
                saveScreenshot("v4-time-wheels-$orientation.png")
                ui { windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button2 && it.isShown }.performClick() }
                check(SettingsStore(targetContext).get("target") == saved)
            }
            evidence.put("timeWheelLayout", JSONObject().put("fontScale", 1.5).put("portraitAndLandscape", true)
                .put("fourColumnsFullyVisible", true).put("realMillisecondSwipe", true).put("cancelPreservesTarget", true))
        } finally {
            shell("input keyevent KEYCODE_BACK")
            if (font == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $font")
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $auto")
            await("Wheel configuration did not restore") {
                targetContext.resources.configuration.let {
                    it.fontScale == (font.toFloatOrNull() ?: 1f) && it.orientation == originalOrientation
                }
            }
            waitForIdleSync()
        }
    }

    private fun pickerAndSettings() {
        openMain()
        val chosen = java.time.Instant.ofEpochMilli(sync.reference().now(SystemClock.elapsedRealtime()) + 120_000).atZone(ZoneId.systemDefault()).withNano(750_000_000)
        openTriggerPicker()
        ui {
            val pickers = windowViews().filterIsInstance<android.widget.NumberPicker>().toList()
            check(windowViews().none { it is android.widget.DatePicker }) { "Timed click must not show a date selector" }
            check(pickers.size == 4) { "Expected four time wheels, found ${pickers.size}" }
            check(pickers.map { it.maxValue } == listOf(23, 59, 59, 999))
            val values = listOf(chosen.hour, chosen.minute, chosen.second, 750)
            pickers.forEachIndexed { index, picker ->
                check(picker.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS)
                picker.value = values[index]
            }
        }
        Thread.sleep(300)
        saveScreenshot("v4-time-wheels.png")
        dialogOk()
        check(SettingsStore(targetContext).get("target").toLong() == chosen.toInstant().toEpochMilli())
        ui {
            val text = windowViews().filterIsInstance<TextView>().first { it.id == R.id.click_start_time }.text.toString()
            check(text.contains(ClockDigits.format(chosen.toInstant().toEpochMilli(), ZoneId.systemDefault())) && !text.contains("2026-"))
        }
        openTriggerPicker()
        ui {
            val ms = windowViews().filterIsInstance<android.widget.NumberPicker>().last()
            check(ms.value == 750)
            ms.value = 999
            windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button2 && it.isShown }.performClick()
        }
        check(SettingsStore(targetContext).get("target").toLong() == chosen.toInstant().toEpochMilli())
        evidence.put("timeWheels", JSONObject().put("columns", 4).put("millisecondMaximum", 999)
            .put("noDatePicker", true).put("cancelPreservesTarget", true))
        ui { ClickService.current?.stopAll() }
        val app = targetContext.applicationContext as ClickerApp
        ui {
            check(activity.findViewById<View>(R.id.click_page).isShown)
            check(!activity.findViewById<View>(R.id.time_page).isShown)
            activity.findViewById<android.widget.RadioButton>(R.id.tab_time).performClick()
            check(!activity.findViewById<View>(R.id.click_page).isShown)
            val grid = activity.findViewById<android.widget.GridLayout>(R.id.sources)
            check(grid.columnCount == 2 && grid.childCount == 5)
            check(descendants(activity.window.decorView).filterIsInstance<EditText>().count { it.isShown } == 1)
            activity.findViewById<EditText>(R.id.sync_interval).setText("999")
            activity.findViewById<Button>(R.id.sync_now).performClick()
            check(activity.findViewById<EditText>(R.id.sync_interval).error != null)
            activity.findViewById<EditText>(R.id.sync_interval).setText("1000")
            activity.findViewById<Button>(R.id.sync_now).performClick()
            check(SettingsStore(targetContext).source().intervalMillis == 1_000L)
            activity.findViewById<EditText>(R.id.sync_interval).setText("60000")
            activity.findViewById<Button>(R.id.sync_now).performClick()
            descendants(grid).filterIsInstance<android.widget.RadioButton>().first { it.tag == "JD" }.performClick()
        }
        check(SettingsStore(targetContext).source().builtin == BuiltinTimeSource.JD)
        request("/control?mode=good&offset=800")
        for (source in listOf(BuiltinTimeSource.JD, BuiltinTimeSource.TAOBAO)) {
            app.timeSources.manager(source).syncNow(TimeSourceConfig(base + "/millis", TimeFormat.JSON_MILLIS, "data.timestamp"), false)
        }
        await("Comparison page samples missing") { listOf(BuiltinTimeSource.JD, BuiltinTimeSource.TAOBAO).all { app.timeSources.manager(it).reference().synced } }
        Thread.sleep(200)
        var text = ""
        ui {
            val grid = activity.findViewById<android.widget.GridLayout>(R.id.sources)
            val jd = grid.getChildAt(0); val taobao = grid.getChildAt(1)
            check(jd.width == taobao.width && jd.left < taobao.left && jd.top == taobao.top)
            text = descendants(jd).filterIsInstance<TextView>().first { it.tag == "JD_time" }.text.toString().replace("\n", "")
            check(text.matches(Regex("[0-2][0-9]:[0-5][0-9]:[0-5][0-9]:[0-9]{3}\\([+-][0-9]+\\)"))) { text }
        }
        Thread.sleep(250)
        ui {
            check(descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.tag == "JD_time" }.text.toString().replace("\n", "") != text)
            activity.findViewById<EditText>(R.id.sync_interval).setText("2500")
        }
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        try {
            ui { activity.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000)) as MainActivity
        } finally { removeMonitor(monitor) }
        await("Recreated time page did not focus") { activity.hasWindowFocus() }
        ui {
            check(activity.findViewById<android.widget.RadioButton>(R.id.tab_time).isChecked)
            check(activity.findViewById<EditText>(R.id.sync_interval).text.toString() == "2500")
        }
        saveScreenshot("v3-time-tab.png")
        ui {
            activity.findViewById<android.widget.RadioButton>(R.id.tab_click).performClick()
            check(!activity.findViewById<View>(R.id.time_page).isShown)
        }
        check(BuiltinTimeSource.entries.filter { it != BuiltinTimeSource.JD }.all { app.timeSources.manager(it).state.nextAttemptElapsed == -1L })
        SettingsStore(targetContext).save(mapOf("source" to "ALIYUN", "syncInterval" to "60000"))
        ui { app.refreshTimeSources() }
        request("/control?mode=good&offset=5000")
        openMain()
        evidence.put("pickerValue", chosen.toString()).put("timePageEditableFields", 1)
            .put("sourceColumns", 2).put("sourceCount", 5).put("sourceClockExample", text)
            .put("tabAndUnsavedIntervalRestored", true).put("hiddenSourceSchedulesCancelled", true)
    }

    private fun feedbackInterrupt() {
        val level = shell("getprop log.tag.AhaoOverlayDiag").trim()
        val variants = JSONArray()
        try {
            shell("setprop log.tag.AhaoOverlayDiag DEBUG")
            showTarget(); receipt("reset")
            request("/control?mode=good&offset=0"); source("/millis"); synchronize()
            SettingsStore(targetContext).save(mapOf("timedOverlaySize" to "", "multiOverlaySize" to ""))
            for (waiting in listOf(false, true)) for (kind in OverlayPanelKind.entries) {
                val at = point("A").let { TapPoint(it[0].toFloat(), it[1].toFloat()) }
                ui {
                    ClickService.current!!.stopAll()
                    if (kind == OverlayPanelKind.MULTI_POINT) {
                        SettingsStore(targetContext).save(mapOf("multiPoints" to "${at.x},${at.y},60000",
                            "multiPointDisplay" to displayKey(targetContext)))
                        ClickService.current!!.showMultiPointOverlay()
                    } else if (waiting) ClickService.current!!.prepareTimed(at,
                        sync.reference().now(SystemClock.elapsedRealtime()) + 60_000)
                    else ClickService.current!!.showTimedOverlay()
                }
                Thread.sleep(350)
                if (waiting) {
                    moveControlAway()
                    source("/millis"); synchronize(); overlayButton(R.string.auto_click_start)
                    await("Task did not enter waiting phase") { phase() == RunPhase.WAITING }
                }
                var root: View? = null
                var markers = emptyList<View>()
                var service: ClickService? = null
                ui {
                    root = sizePanel(); service = ClickService.current
                    markers = WindowInspector.getGlobalWindowViews().filter { view ->
                        view.contentDescription == targetContext.getString(R.string.crosshair_description) ||
                            view.javaClass.simpleName == "NumberedMarker"
                    }
                    check(markers.isNotEmpty())
                }
                val sent = SystemClock.elapsedRealtime()
                ui { targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java).interrupt() }
                await("System did not deliver feedback interruption") {
                    Regex("event=feedback_interrupt elapsed=(\\d+)")
                        .findAll(shell("logcat -d -v raw -s AhaoOverlayDiag:D '*:S'"))
                        .any { it.groupValues[1].toLong() >= sent }
                }
                ui {
                    check(ClickService.current === service)
                    check(root!!.isAttachedToWindow && root!!.isShown && sizePanel() === root) {
                        "Feedback interruption closed the $kind panel (waiting=$waiting)"
                    }
                    check(markers.all { it.isAttachedToWindow && it.isShown }) { "Feedback interruption removed markers" }
                    if (waiting) check(service!!.status.phase == RunPhase.WAITING)
                }
                if (waiting) {
                    serviceEnabled(false)
                    ui { check(!root!!.isAttachedToWindow && markers.none { it.isAttachedToWindow }) }
                    serviceEnabled(true)
                    Thread.sleep(700)
                    check(phase() == RunPhase.STOPPED)
                    ui { check(windowViews().none { it.id == R.id.resize_bottom_right && it.isShown }) }
                } else ui { ClickService.current!!.stopAll() }
                check(count() == 0)
                variants.put(JSONObject().put("kind", kind.name).put("waiting", waiting)
                    .put("callbackDelivered", true).put("originalWindowsPreserved", true)
                    .put("disconnectStopsAndDoesNotResume", waiting))
            }
            evidence.put("feedbackInterrupt", JSONObject().put("variants", variants).put("actualReceived", count()))
        } finally {
            ui { ClickService.current?.stopAll() }
            shell("setprop log.tag.AhaoOverlayDiag ${if (level.isEmpty()) "\"\"" else level}")
        }
    }

    private fun sameDisplayPreservesPoint() {
        openMain()
        val store = SettingsStore(targetContext)
        val previous = listOf("pointX", "pointY", "pointDisplay").associateWith { store.get(it) }
        try { ui {
            val service = checkNotNull(ClickService.current)
            val key = displayKey(service)
            check(key == displayKey(activity)) { "Activity and accessibility service disagree about physical display" }
            val size = screenSize(service)
            store.save(mapOf("pointX" to (size.first / 2).toString(), "pointY" to (size.second / 2).toString(), "pointDisplay" to key))
            service.toggleClock()
            service.onConfigurationChanged(android.content.res.Configuration(service.resources.configuration))
            check(store.get("pointDisplay") == key) { "An unchanged physical display invalidated the selected point" }
            check(windowViews().any { it.contentDescription == targetContext.getString(R.string.crosshair_description) }) {
                "An unchanged physical display closed the crosshair"
            }
            service.stopAll()
        } } finally { ui { ClickService.current?.stopAll() }; store.save(previous) }
        evidence.put("sameDisplayPreservesSelectedPoint", true)
    }
    private fun independentSamples() {
        val catalog = TimeSources()
        val first = catalog.manager(BuiltinTimeSource.JD)
        val second = catalog.manager(BuiltinTimeSource.TAOBAO)
        val config = TimeSourceConfig(base + "/millis", TimeFormat.JSON_MILLIS, "data.timestamp")
        try {
            request("/control?mode=good&offset=800")
            first.syncNow(config, false)
            await("First source did not sync") { first.reference().synced }
            val firstReference = first.reference()
            request("/control?mode=good&offset=-800")
            second.syncNow(config, false)
            await("Second source did not sync") { second.reference().synced }
            check(first.reference() == firstReference)
            check(first.reference().now(100_000) - second.reference().now(100_000) in 800..2400)
            val secondReference = second.reference()
            request("/control?mode=malformed")
            second.syncNow(config, false)
            await("Second source error not reported") { second.state.failed }
            check(first.reference() == firstReference && second.reference() == secondReference)
            request("/control?mode=slow")
            first.syncNow(config, true)
            await("Cancellation source did not begin") { first.state.syncing }
            first.pause()
            Thread.sleep(2_700)
            check(first.reference() == firstReference && !first.state.syncing && first.state.nextAttemptElapsed == -1L)
            val requests = request("/stats").getInt("requests")
            Thread.sleep(1_100)
            check(request("/stats").getInt("requests") == requests)
            evidence.put("sourceSamplesIsolated", true).put("cancelledResponseIgnored", true)
        } finally {
            first.pause(); second.pause()
            request("/control?mode=good&offset=5000")
        }
    }

    private fun overlayLocation(predicate: (View) -> Boolean): IntArray {
        var xy = intArrayOf()
        ui {
            val view = windowViews().first { it.isShown && predicate(it) }
            val at = IntArray(2); view.getLocationOnScreen(at)
            xy = intArrayOf(at[0] + view.width / 2, at[1] + view.height / 2)
        }
        return xy
    }
    private fun moveControlAway(targetY: Int = 1900) {
        Thread.sleep(250) // Publish the new overlay input window before sending its first touch.
        ui {
            val handle = checkNotNull(windowViews().firstOrNull { it.isShown && it.id == R.id.overlay_drag })
            check(handle is ImageView && handle.drawable != null && handle.contentDescription == targetContext.getString(R.string.clock_drag)) {
                "Floating drag handle must be an accessible icon instead of text"
            }
            val touch = targetContext.resources.getDimensionPixelSize(R.dimen.overlay_button_target)
            check(handle.width >= touch && handle.height >= touch)
        }
        val xy = overlayLocation { it.id == R.id.overlay_drag }
        shell("input touchscreen swipe ${xy[0]} ${xy[1]} 1000 $targetY 300")
        await("Dragging the icon did not move the floating window") {
            val moved = overlayLocation { it.id == R.id.overlay_drag }
            kotlin.math.abs(moved[0] - xy[0]) + kotlin.math.abs(moved[1] - xy[1]) > 20
        }
        Thread.sleep(200) // Window geometry can update before its native input region; settle before the next touch.
        evidence.put("floatingDragIconMovedWindow", true)
    }
    private fun timedSingle() {
        showTarget(); receipt("reset")
        SettingsStore(targetContext).save(mapOf("pointDisplay" to "", "pointX" to "", "pointY" to ""))
        openMain()
        ui {
            check(descendants(activity.window.decorView).filterIsInstance<Button>().none { it.text.toString() == "显示准星，拖动定位" }) {
                "Separate crosshair location button must be removed"
            }
            activity.findViewById<Button>(R.id.show_clock).performClick()
        }
        showTarget()
        await("Crosshair or target input focus not ready") { var yes = false; ui { yes = windowViews().any {
            it.contentDescription == targetContext.getString(R.string.crosshair_description) && it.isShown && it.width > 0 && it.height > 0
        } }; yes && receipt().getBoolean("focused") }
        Thread.sleep(200)
        val from = overlayLocation { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
        val destination = point("A")
        shell("input touchscreen swipe ${from[0]} ${from[1]} ${destination[0]} ${destination[1]} 400")
        await("Crosshair did not reach the dragged position") {
            val actual = overlayLocation { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
            kotlin.math.abs(actual[0] - destination[0]) <= 2 && kotlin.math.abs(actual[1] - destination[1]) <= 2
        }
        check(count() == 0) { "Dragging the crosshair clicked the target" }
        await("Latest dragged point was not saved automatically") {
            val saved = SettingsStore(targetContext)
            saved.get("pointDisplay") == displayKey(targetContext) &&
                kotlin.math.abs((saved.get("pointX").toFloatOrNull() ?: -1f) - destination[0]) <= 2 &&
                kotlin.math.abs((saved.get("pointY").toFloatOrNull() ?: -1f) - destination[1]) <= 2
        }
        val store = SettingsStore(targetContext)
        evidence.put("crosshairDrag", JSONObject().put("from", JSONArray(from.toList())).put("target", JSONArray(destination.toList()))
            .put("savedX", store.get("pointX")).put("savedY", store.get("pointY")))
        check(kotlin.math.abs(store.get("pointX").toFloat() - destination[0]) <= 2) {
            "Crosshair saved X=${store.get("pointX")}, target X=${destination[0]}"
        }
        check(kotlin.math.abs(store.get("pointY").toFloat() - destination[1]) <= 2) {
            "Crosshair saved Y=${store.get("pointY")}, target Y=${destination[1]}"
        }
        check(count() == 0) { "Dragging the crosshair clicked the target" }
        request("/control?mode=good&offset=0")
        store.save(mapOf("target" to (request("/millis").getJSONObject("data").getLong("timestamp") + 7_000).toString()))
        openMain()
        check(kotlin.math.abs(store.get("pointX").toFloat() - destination[0]) <= 2)
        check(kotlin.math.abs(store.get("pointY").toFloat() - destination[1]) <= 2)
        ui { activity.moveTaskToBack(true) }
        await("Preparation did not return to target") { receipt().getBoolean("focused") }
        moveControlAway()
        source("/millis"); synchronize()
        check(count() == 0) { "Preparation injected a gesture" }
        overlayButton(R.string.auto_click_start)
        await("One-shot not armed") { var yes = false; ui { yes = ClickService.current?.oneShotStatus?.phase == RunPhase.WAITING }; yes }
        var frozen: TimeReference? = null
        ui {
            frozen = ClickService.current!!.taskReference
            val marker = windowViews().first { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
            check((marker.layoutParams as android.view.WindowManager.LayoutParams).flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
            val xy = IntArray(2); marker.getLocationOnScreen(xy)
            check(kotlin.math.abs(xy[0] + marker.width / 2 - store.get("pointX").toFloat()) <= 1)
            check(kotlin.math.abs(xy[1] + marker.height / 2 - store.get("pointY").toFloat()) <= 1)
        }
        val latest = point("B")
        val previousPoint = overlayLocation { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
        shell("input touchscreen swipe ${previousPoint[0]} ${previousPoint[1]} ${latest[0]} ${latest[1]} 400")
        await("Armed crosshair did not move") {
            val actual = overlayLocation { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
            kotlin.math.abs(actual[0] - latest[0]) <= 2 && kotlin.math.abs(actual[1] - latest[1]) <= 2
        }
        check(count() == 0) { "Dragging while armed injected a click" }
        await("Final reference lock window not reached", 8_000) {
            var finalWindow = false
            ui { finalWindow = (ClickService.current!!.oneShotTiming!!.plannedElapsed - SystemClock.elapsedRealtime()) in 1..4_500 }
            finalWindow
        }
        request("/control?mode=good&offset=10000"); synchronize()
        ui { check(ClickService.current!!.taskReference == frozen) }
        ui {
            check(ClickService.current!!.oneShotStatus.phase == RunPhase.WAITING)
            check(windowViews().any { it.javaClass.simpleName == "ClockFace" && it.isShown })
        }
        await("One-shot target did not receive exactly one click", 9000) { count() == 1 }
        await("One-shot did not finish") { phase() == RunPhase.COMPLETED }
        Thread.sleep(650); check(count() == 1)
        ui {
            val marker = windowViews().first { it.contentDescription == targetContext.getString(R.string.crosshair_description) && it.isShown }
            check((marker.layoutParams as android.view.WindowManager.LayoutParams).flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        }
        val restored = overlayLocation { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
        check(kotlin.math.abs(restored[0] - latest[0]) <= 2 && kotlin.math.abs(restored[1] - latest[1]) <= 2) { "Restored marker moved from latest point" }
        val received = events()
        check(received.getJSONObject(0).getString("point") == "B") { "Timed click did not use latest crosshair" }
        var timing: OneShotTiming? = null
        ui { timing = ClickService.current!!.oneShotTiming }
        val observed = checkNotNull(timing)
        val delay = received.getJSONObject(0).getLong("elapsed") - observed.plannedElapsed
        check(delay in -50..800) { "Unexpected one-shot receipt deviation: $delay" }
        evidence.put("oneShotReceipts", received).put("oneShotReceiptDeviationMillis", delay).put("frozenClockAcrossSync", true).put("latestCrosshairReceived", "B").put("dragDidNotClick", true)
            .put("oneShotTiming", JSONObject().put("targetEpoch", observed.targetEpoch).put("plannedElapsed", observed.plannedElapsed)
                .put("dispatchedElapsed", observed.dispatchedElapsed).put("callbackElapsed", observed.callbackElapsed))
        saveScreenshot("v2-one-shot.png")
        ui { ClickService.current!!.stopAll() }
        request("/control?mode=good&offset=0")
    }
    private fun cancelBeforeDispatch() {
        showTarget(); receipt("reset")
        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        val p = point("A")
        ui { ClickService.current!!.prepareTimed(TapPoint(p[0].toFloat(), p[1].toFloat()),
            sync.reference().now(SystemClock.elapsedRealtime()) + 2_500) }
        moveControlAway(); source("/millis"); synchronize()
        overlayButton(R.string.auto_click_start)
        await("Cancellation task did not reach armed state") {
            var ready = false
            ui { ready = ClickService.current?.oneShotStatus?.phase == RunPhase.WAITING && ClickService.current?.oneShotTiming != null }
            ready
        }
        var cancelledDuringRemoval = false
        ui {
            val service = ClickService.current!!
            val button = windowViews().filterIsInstance<ImageButton>().first { it.id == R.id.toggle_click && it.isShown }
            val delay = service.oneShotTiming!!.plannedElapsed - SystemClock.elapsedRealtime() - 75
            check(delay > 0)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                cancelledDuringRemoval = service.oneShotStatus.phase == RunPhase.WAITING &&
                    windowViews().none { it.contentDescription == targetContext.getString(R.string.crosshair_description) && it.isShown }
                if (ClickService.current === service && service.oneShotStatus.phase == RunPhase.WAITING) button.performClick()
            }, delay)
        }
        await("Stop during marker removal did not cancel", 4_000) {
            var stopped = false
            ui { stopped = cancelledDuringRemoval && ClickService.current!!.oneShotStatus.phase == RunPhase.STOPPED &&
                windowViews().any { it.contentDescription == targetContext.getString(R.string.crosshair_description) && it.isShown } }
            stopped
        }
        Thread.sleep(650)
        check(count() == 0) { "Cancelled input-window settling still injected a click" }
        evidence.put("cancelledDuringMarkerRemoval", true).put("markerRestoredAfterCancellation", true)
        ui { ClickService.current!!.stopAll() }
        Thread.sleep(600)
        ui { check(windowViews().none { it.contentDescription == targetContext.getString(R.string.crosshair_description) && it.isShown }) }
    }

    private fun timedCancel() {
        showTarget(); receipt("reset")
        val point = point("A").let { TapPoint(it[0].toFloat(), it[1].toFloat()) }
        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        ui { ClickService.current!!.prepareTimed(point, sync.reference().now(SystemClock.elapsedRealtime()) + 5_000) }
        moveControlAway(); source("/millis"); synchronize(); overlayButton(R.string.auto_click_start)
        await("Cancellation task did not arm") { var yes = false; ui { yes = ClickService.current!!.oneShotStatus.phase == RunPhase.WAITING }; yes }
        overlayButton(R.string.auto_click_stop)
        Thread.sleep(5_200); check(count() == 0)
        ui { ClickService.current!!.prepareTimed(point, sync.reference().now(SystemClock.elapsedRealtime()) + 8_000) }
        moveControlAway(); source("/millis"); synchronize(); overlayButton(R.string.auto_click_start)
        await("Icon cancellation task did not arm") { var yes = false; ui { yes = ClickService.current!!.oneShotStatus.phase == RunPhase.WAITING }; yes }
        overlayButton(R.string.close_overlay_stop)
        Thread.sleep(8_200); check(count() == 0) { "Closing the armed overlay failed to cancel the click" }
        evidence.put("armedOverlayCloseCancelledClick", true)
        if (!physical) { serviceEnabled(false); serviceEnabled(true); Thread.sleep(300); check(count() == 0) }
        evidence.put("cancelledOneShotReconnectChecked", !physical)
        evidence.put("cancelledOneShotReceived", count())
    }
    private fun builtinSources() {
        val fixtureTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getDefault().socketFactory)
        try {
        val probes = JSONArray()
        val manager = TimeSyncManager()
        val dnsStart = SystemClock.elapsedRealtime()
        AddressLookup().use { lookup ->
            check(runCatching { lookup.resolve("clicker-${System.nanoTime()}.invalid") }.isFailure)
        }
        check(SystemClock.elapsedRealtime() - dnsStart < 3_000) { "DNS failure exceeded its bounded wait" }
        evidence.put("dnsFailureBoundedMillis", SystemClock.elapsedRealtime() - dnsStart)
        for (source in BuiltinTimeSource.entries) {
            manager.syncNow(TimeSourceConfig.builtin(source), false)
            await("Built-in probe ${source.name} did not settle", 10000) { !manager.state.syncing && (manager.state.failed || manager.reference().synced) }
            probes.put(JSONObject().put("source", source.name).put("host", source.host)
                .put("succeeded", manager.reference().synced && !manager.state.failed)
                .put("rttMillis", manager.state.rttMillis).put("failure", manager.state.failure.name))
        }
        manager.pause()
        evidence.put("builtinSourceProbes", probes)
        } finally { HttpsURLConnection.setDefaultSSLSocketFactory(fixtureTls) }
    }
    private fun jdLiveSource() {
        val fixtureTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val store = SettingsStore(targetContext)
        val previous = store.get("source", "ALIYUN")
        val app = targetContext.applicationContext as ClickerApp
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getDefault().socketFactory)
        try {
            openMain()
            ui {
                activity.findViewById<android.widget.RadioButton>(R.id.tab_time).performClick()
                descendants(activity.findViewById(R.id.sources)).filterIsInstance<android.widget.RadioButton>()
                    .first { it.tag == "JD" }.performClick()
            }
            val manager = app.timeSources.manager(BuiltinTimeSource.JD)
            val start = SystemClock.elapsedRealtime()
            manager.syncNow(TimeSourceConfig.builtin(BuiltinTimeSource.JD, store.source().intervalMillis), true)
            await("Live JD OPTIONS did not yield a valid fresh sample: ${manager.state}", 15_000) {
                manager.hasFreshSample() && !manager.state.failed && manager.state.lastSuccessElapsed >= start
            }
            var display = ""
            await("JD card did not display its real millisecond clock") {
                var shown = false
                ui {
                    display = descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .first { it.tag == "JD_time" }.text.toString().replace("\n", "")
                    shown = display.matches(Regex("[0-2][0-9]:[0-5][0-9]:[0-5][0-9]:[0-9]{3}\\([+-][0-9]+\\)"))
                }
                shown
            }
            check(app.timeSync === manager && SettingsStore(targetContext).source().builtin == BuiltinTimeSource.JD)
            evidence.put("jdLiveSource", JSONObject().put("url", BuiltinTimeSource.JD.url).put("method", "OPTIONS")
                .put("fresh", manager.hasFreshSample()).put("rttMillis", manager.state.rttMillis).put("display", display))
            saveScreenshot("jd-live-source.png")
        } finally {
            ui {
                activity.findViewById<android.widget.RadioButton>(R.id.tab_click).performClick()
                store.save(mapOf("source" to previous)); app.refreshTimeSources()
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(fixtureTls)
        }
    }
    private fun timePageLayout() {
        val font = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        val night = shell("cmd uimode night").substringAfter(":").trim()
        try {
            shell("settings put system font_scale 1.5")
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            shell("cmd uimode night yes")
            await("Time page configuration did not apply") {
                targetContext.resources.configuration.let {
                    it.fontScale == 1.5f && it.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                        it.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }
            waitForIdleSync()
            openMain()
            ui { activity.findViewById<android.widget.RadioButton>(R.id.tab_time).performClick() }
            Thread.sleep(250)
            ui {
                check(activity.resources.configuration.fontScale == 1.5f)
                check(activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                check(activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                val grid = activity.findViewById<android.widget.GridLayout>(R.id.sources)
                check(grid.columnCount == 2 && grid.childCount == 5)
                val first = grid.getChildAt(0); val second = grid.getChildAt(1)
                check(first.width > 0 && first.width == second.width && first.top == second.top && first.left < second.left)
                check(descendants(grid).filterIsInstance<android.widget.RadioButton>().count { it.text.isNotEmpty() } == 5)
                activity.findViewById<android.widget.ScrollView>(R.id.time_page).fullScroll(View.FOCUS_DOWN)
            }
            Thread.sleep(250)
            ui {
                val save = activity.findViewById<Button>(R.id.sync_now)
                val rect = android.graphics.Rect()
                check(save.getGlobalVisibleRect(rect) && rect.height() == save.height) { "Save button clipped in large-font landscape" }
            }
            saveScreenshot("v3-time-large-font-dark.png")
            evidence.put("timePageLargeFontScale", 1.5).put("timePageLandscapeDark", true).put("timePageSaveReachable", true)
        } finally {
            if (font == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $font")
            if (rotation == "null") shell("settings delete system user_rotation") else shell("settings put system user_rotation $rotation")
            if (auto == "null") shell("settings delete system accelerometer_rotation") else shell("settings put system accelerometer_rotation $auto")
            if (night in setOf("auto", "yes", "no")) shell("cmd uimode night $night")
            Thread.sleep(500)
        }
    }
    private fun twoStepEntries() {
        openMain()
        ui {
            ClickService.current!!.stopAll()
            val shown = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.toList()
            check(shown.none { it.text.toString().startsWith("第三步") || it.text.toString() == "点击位置" || it.text.toString().startsWith("准星中心") }) {
                "Third step and coordinate summary must be removed"
            }
            val record = checkNotNull(shown.filterIsInstance<Button>().firstOrNull { it.id == R.id.show_recording_overlay }) {
                "Second step needs a multipoint entry"
            }
            check(record.isEnabled && record.text.toString().contains("多点点击"))
            record.performClick()
            check(!ClickService.current!!.hasActiveTask)
            check(windowViews().any { it.id == R.id.add_point && it.isShown })
            check(windowViews().none { it.id == R.id.click_start_time && it.isShown })
            val timed = activity.findViewById<Button>(R.id.show_clock)
            check(timed.text.toString() == "开启定时点击浮窗")
            timed.performClick()
        }
        await("Timed entry did not show clock and crosshair") {
            var ready = false
            ui { ready = windowViews().any { it.id == R.id.toggle_click && it.isShown } &&
                windowViews().any { it.contentDescription == targetContext.getString(R.string.crosshair_description) && it.isShown } }
            ready
        }
        var original: View? = null
        ui {
            original = WindowInspector.getGlobalWindowViews().first { root -> descendants(root).any { it.id == R.id.toggle_click } }
            activity.findViewById<Button>(R.id.show_clock).performClick()
            check(!ClickService.current!!.hasActiveTask)
        }
        Thread.sleep(250)
        ui {
            check(WindowInspector.getGlobalWindowViews().any { it === original && it.isShown }) { "Open entry toggled an existing window closed" }
            ClickService.current!!.stopAll()
        }
        Thread.sleep(250); saveScreenshot("v7-two-step-home.png")
        evidence.put("twoStepEntries", JSONObject().put("thirdStepRemoved", true).put("multipointEnabled", true)
            .put("mutuallyExclusive", true).put("timedEntryOpensWithoutClicking", true).put("openingIsIdempotent", true))
    }

    private fun sizePanel(): View = WindowInspector.getGlobalWindowViews().first { root ->
        descendants(root).any { it.id == R.id.resize_bottom_right && it.isShown }
    }
    private fun sizePanelRect(): OverlayRect {
        var result = OverlayRect(0, 0, 0, 0)
        ui { val root = sizePanel(); val xy = IntArray(2); root.getLocationOnScreen(xy)
            result = OverlayRect(xy[0], xy[1], root.width, root.height) }
        return result
    }
    private fun openSizePanel(kind: OverlayPanelKind): OverlayRect {
        ui { ClickService.current!!.stopAll()
            if (kind == OverlayPanelKind.TIMED) ClickService.current!!.showTimedOverlay()
            else ClickService.current!!.showMultiPointOverlay() }
        Thread.sleep(350)
        return sizePanelRect()
    }
    private fun checkSizeControls(kind: OverlayPanelKind) = ui {
        val root = sizePanel()
        val views = descendants(root).toList()
        val ids = listOf(R.id.toggle_click, R.id.overlay_drag, R.id.close_overlay) +
            if (kind == OverlayPanelKind.MULTI_POINT) listOf(R.id.add_point) else emptyList()
        val controls = ids.map { id -> views.first { it.id == id } }
        check(controls.all { it.isShown && it.top == controls.first().top && it.height > 0 })
        (controls + views.filter { it.id in listOf(R.id.resize_bottom_left, R.id.resize_bottom_right, R.id.click_start_time) })
            .forEach { view -> val rect = android.graphics.Rect()
                check(view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height) }
        val safe = overlayAvailableBounds(root)
        val xy = IntArray(2); root.getLocationOnScreen(xy)
        val at = OverlayRect(xy[0], xy[1], root.width, root.height)
        check(at.left >= safe.left && at.top >= safe.top && at.left + at.width <= safe.left + safe.width &&
            at.top + at.height <= safe.top + safe.height)
    }
    private fun overlaySizeMemory() {
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        val store = SettingsStore(targetContext)
        val values = JSONObject()
        val sizes = mutableMapOf<OverlayPanelKind, OverlayRect>()
        try {
            ui { ClickService.current!!.stopAll() }
            shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 0")
            await("Portrait not ready") { targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            openMain(); showTarget(); receipt("reset")
            store.save(OverlayPanelKind.entries.associate { it.sizeKey to "" })
            for (kind in OverlayPanelKind.entries) {
                val initial = openSizePanel(kind)
                val at = overlayLocation { it.id == R.id.resize_bottom_right }
                val desiredWidth = if (kind == OverlayPanelKind.TIMED) 420 else 650
                val desiredHeight = if (kind == OverlayPanelKind.TIMED) 600 else 1_500
                shell("input touchscreen swipe ${at[0]} ${at[1]} ${at[0] + desiredWidth - initial.width} ${at[1] + desiredHeight - initial.height} 400")
                Thread.sleep(300)
                val right = sizePanelRect()
                check(right.width == desiredWidth && right.height == desiredHeight)
                val left = overlayLocation { it.id == R.id.resize_bottom_left }
                shell("input touchscreen swipe ${left[0]} ${left[1]} ${left[0] + 60} ${left[1] + 80} 350")
                Thread.sleep(300)
                val resized = sizePanelRect()
                check(resized.width == desiredWidth - 60 && resized.height == desiredHeight + 80)
                sizes[kind] = resized
                values.put(kind.sizeKey, store.get(kind.sizeKey))
                val decoded = checkNotNull(SettingsStore(targetContext).overlaySize(kind))
                val density = targetContext.resources.displayMetrics.density
                check(kotlin.math.abs(decoded.widthDp * density - resized.width) < 1 &&
                    kotlin.math.abs(decoded.heightDp * density - resized.height) < 1)
                overlayButton(if (kind == OverlayPanelKind.TIMED) R.string.clock_close else R.string.multi_close)
                Thread.sleep(250)
                val reopened = openSizePanel(kind)
                check(reopened.width == resized.width && reopened.height == resized.height)
                checkSizeControls(kind)
                saveScreenshot("v15-${kind.name.lowercase()}-reopened.png")
            }
            ui { ClickService.current!!.stopAll() }
            shell("settings put system user_rotation 1")
            await("Landscape not ready") { targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            for (kind in OverlayPanelKind.entries) {
                val cropped = openSizePanel(kind)
                checkSizeControls(kind)
                check(store.get(kind.sizeKey) == values.getString(kind.sizeKey)) { "Automatic crop overwrote saved size" }
                if (kind == OverlayPanelKind.MULTI_POINT) check(cropped.height < sizes.getValue(kind).height)
            }
            ui { ClickService.current!!.stopAll() }
            shell("settings put system user_rotation 0")
            await("Portrait not restored") { targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            for (kind in OverlayPanelKind.entries) {
                val restored = openSizePanel(kind)
                check(restored.width == sizes.getValue(kind).width && restored.height == sizes.getValue(kind).height)
            }
            check(count() == 0)
            val checkpoint = JSONObject().put("pid", android.os.Process.myPid()).put("values", values)
            sizes.forEach { (kind, rect) -> checkpoint.put(kind.name, JSONArray(listOf(rect.width, rect.height))) }
            targetContext.filesDir.resolve("v15-size-checkpoint.json").writeText(checkpoint.toString())
            evidence.put("overlaySizeMemory", checkpoint.put("bothCornersSaved", true).put("reopenRestored", true)
                .put("modesIndependent", true).put("landscapeCropDoesNotOverwrite", true).put("noInjectedClick", true))
        } finally {
            ui { ClickService.current?.stopAll() }
            shell(if (rotation == "null") "settings delete system user_rotation" else "settings put system user_rotation $rotation")
            shell(if (auto == "null") "settings delete system accelerometer_rotation" else "settings put system accelerometer_rotation $auto")
        }
    }
    private fun overlaySizeRestart() {
        val checkpoint = JSONObject(targetContext.filesDir.resolve("v15-size-checkpoint.json").readText())
        check(checkpoint.getInt("pid") != android.os.Process.myPid()) { "Process must restart between cases" }
        shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 0")
        await("Portrait not ready") { targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        for (kind in OverlayPanelKind.entries) {
            check(SettingsStore(targetContext).get(kind.sizeKey) == checkpoint.getJSONObject("values").getString(kind.sizeKey))
            val restored = openSizePanel(kind)
            val expected = checkpoint.getJSONArray(kind.name)
            check(restored.width == expected.getInt(0) && restored.height == expected.getInt(1))
            checkSizeControls(kind)
            saveScreenshot("v15-${kind.name.lowercase()}-restarted.png")
        }
        evidence.put("overlaySizeRestart", JSONObject().put("previousPid", checkpoint.getInt("pid"))
            .put("currentPid", android.os.Process.myPid()).put("bothSizesRestoredFromDisk", true))
    }

    private fun responsiveOverlay() {
        val font = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        val results = JSONArray()
        fun panel() = WindowInspector.getGlobalWindowViews().first { root ->
            descendants(root).any { it.id == R.id.resize_bottom_right && it.isShown }
        }
        fun assertLayout(compact: Boolean, multi: Boolean) = ui {
            val root = panel()
            val ids = mutableListOf(R.id.toggle_click, R.id.overlay_drag, R.id.close_overlay,
                R.id.resize_bottom_left, R.id.resize_bottom_right)
            if (multi) ids.add(R.id.add_point) else ids.add(R.id.click_start_time)
            val controls = descendants(root).filter { it.id in ids }.toList()
            for (view in controls) {
                val rect = android.graphics.Rect()
                check(view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height) {
                    "Compact control clipped: ${view.id}"
                }
                if (view.id == R.id.click_start_time) {
                    check(view.height >= targetContext.resources.getDimensionPixelSize(R.dimen.overlay_button_target))
                }
            }
            val play = controls.first { it.id == R.id.toggle_click }
            val close = controls.first { it.id == R.id.close_overlay }
            val move = controls.first { it.id == R.id.overlay_drag }
            val headerIds = listOf(R.id.toggle_click, R.id.add_point, R.id.overlay_drag, R.id.close_overlay)
            val header = controls.filter { it.id in headerIds }.sortedBy { it.left }
            check(header.map { it.id } == headerIds.filter { multi || it != R.id.add_point })
            check(header.all { it.top == play.top && it.bottom == play.bottom && it.width >= play.height })
            check(header.zipWithNext().all { (a, b) -> a.right <= b.left })
            check(move.top == play.top && close.top == play.top)
            header.filterIsInstance<ImageView>().forEach { view ->
                check(view.paddingLeft > 0 && view.width > view.paddingLeft + view.paddingRight)
                check(view.scaleType == ImageView.ScaleType.FIT_CENTER)
            }
            check(play.left + play.width <= close.left)
            val face = descendants(root).first { it.javaClass.simpleName == "ClockFace" }
            val rect = android.graphics.Rect()
            check(face.width > 0 && face.height > 0 && face.getGlobalVisibleRect(rect) && rect.height() == face.height)
            check(face.contentDescription?.length == 12)
            if (!multi) {
                val row = controls.filterIsInstance<TextView>().first { it.id == R.id.click_start_time }
                check(row.lineCount == 1 && row.layout.getEllipsisCount(0) == 0)
                check(row.layout.getLineWidth(0) <= row.width - row.compoundPaddingLeft - row.compoundPaddingRight + 1)
                check(row.text.toString().matches(Regex(if (compact) "\\d{2}:\\d{2}:\\d{2}:\\d{3}" else "定时 .*")))
            }
        }
        try {
            for ((orientation, scale) in listOf(0 to "1.0", 1 to "1.5")) {
                ui { ClickService.current!!.stopAll() }
                shell("settings put system font_scale $scale")
                shell("settings put system accelerometer_rotation 0")
                shell("settings put system user_rotation $orientation")
                await("Responsive test configuration not applied") {
                    targetContext.resources.configuration.let { it.fontScale == scale.toFloat() && it.orientation ==
                        if (orientation == 0) android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                }
                openMain(); showTarget(); receipt("reset")
                for (multi in listOf(false, true)) {
                    ui {
                        ClickService.current!!.stopAll()
                        if (multi) {
                            val size = screenSize(targetContext)
                            SettingsStore(targetContext).save(mapOf("multiPoints" to "${size.first * .75f},${size.second * .5f},10000",
                                "multiPointDisplay" to displayKey(targetContext)))
                            ClickService.current!!.showMultiPointOverlay()
                        } else ClickService.current!!.showTimedOverlay()
                    }
                    Thread.sleep(400)
                    val corner = overlayLocation { it.id == R.id.resize_bottom_right }
                    shell("input touchscreen swipe ${corner[0]} ${corner[1]} 0 0 500")
                    await("Window did not reach compact minimum width") {
                        var narrow = false; ui { narrow = panel().width == targetContext.resources.getDimensionPixelSize(R.dimen.overlay_min_width) }; narrow
                    }
                    Thread.sleep(350)
                    assertLayout(true, multi)
                    val dimensions = JSONArray()
                    ui { dimensions.put(panel().width).put(panel().height) }
                    saveScreenshot("v14-${if (multi) "multi" else "timed"}-$orientation.png")
                    if (multi) {
                        overlayButton(R.string.multi_add_point)
                        await("Compact add button unavailable") { var added = false; ui { added = windowViews().count {
                            it.javaClass.simpleName == "NumberedMarker" && it.isShown } == 2 }; added }
                        overlayButton(R.string.auto_click_start)
                        await("Compact play not available") { phase() == RunPhase.WAITING }
                        assertLayout(true, true)
                        overlayButton(R.string.auto_click_stop)
                        await("Compact stop not available") { phase() == RunPhase.STOPPED }
                        Thread.sleep(650)
                    } else {
                        val at = overlayLocation { it.id == R.id.click_start_time }; touch(at[0], at[1])
                        await("Compact trigger time not editable") { var open = false; ui { open = windowViews()
                            .filterIsInstance<android.widget.NumberPicker>().count { it.isShown } == 4 }; open }
                        dialogCancel(); Thread.sleep(300)
                    }
                    val smallCorner = overlayLocation { it.id == R.id.resize_bottom_right }
                    shell("input touchscreen swipe ${smallCorner[0]} ${smallCorner[1]} ${smallCorner[0] + 500} ${smallCorner[1]} 500")
                    Thread.sleep(350)
                    assertLayout(false, multi)
                    for (dp in listOf(204, 208, 216, 224, 232)) {
                        val desired = (dp * targetContext.resources.displayMetrics.density).toInt()
                        var currentWidth = 0; ui { currentWidth = panel().width }
                        val at = overlayLocation { it.id == R.id.resize_bottom_right }
                        // Vertical travel exceeds touch slop even for adjacent width samples.
                        shell("input touchscreen swipe ${at[0]} ${at[1]} ${at[0] + desired - currentWidth} ${at[1] + 40} 180")
                        Thread.sleep(100)
                        ui {
                            check(kotlin.math.abs(panel().width - desired) <= 1) { "Width sample ${dp}dp was not reached" }
                            val views = descendants(panel()).toList()
                            check(views.first { it.id == R.id.overlay_drag }.top == views.first { it.id == R.id.toggle_click }.top) {
                                "Header unexpectedly wraps again at ${dp}dp"
                            }
                        }
                    }
                    check(count() == 0) { "Responsive controls must not click the target" }
                    results.put(JSONObject().put("multi", multi).put("orientation", orientation).put("fontScale", scale)
                        .put("compactSizePixels", dimensions).put("singleRowAt128dp", true).put("singleRowAtWideSize", true)
                        .put("paddingTransitionDoesNotRewrap", true)
                        .put("clockAndControlsVisible", true).put("targetTimeEditableOrLoopControlsWorking", true))
                    ui { ClickService.current!!.stopAll() }
                }
            }
            evidence.put("responsiveOverlay", results)
        } finally {
            ui { ClickService.current?.stopAll() }
            if (font == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $font")
            if (rotation == "null") shell("settings delete system user_rotation") else shell("settings put system user_rotation $rotation")
            if (auto == "null") shell("settings delete system accelerometer_rotation") else shell("settings put system accelerometer_rotation $auto")
            Thread.sleep(500)
        }
    }

    private fun overlayResize() {
        showTarget(); receipt("reset")
        fun panel(): View = WindowInspector.getGlobalWindowViews().first { root ->
            descendants(root).any { it.id == R.id.resize_bottom_right && it.isShown }
        }
        fun bounds(): IntArray {
            var result = intArrayOf()
            ui { val view = panel(); val xy = IntArray(2); view.getLocationOnScreen(xy)
                result = intArrayOf(xy[0], xy[1], view.width, view.height) }
            return result
        }
        fun resize(id: Int, dx: Int, dy: Int) {
            val xy = overlayLocation { it.id == id }
            shell("input touchscreen swipe ${xy[0]} ${xy[1]} ${xy[0] + dx} ${xy[1] + dy} 350")
            Thread.sleep(250)
        }
        fun fullyVisible() = ui {
            val clock = descendants(panel()).first { it.javaClass.simpleName == "ClockFace" }
            check(clock.width > 0 && clock.height > 0 && clock.contentDescription?.length == 12) {
                "Clock not drawn: ${clock.width}x${clock.height}, value=${clock.contentDescription}"
            }
            for (id in listOf(R.id.toggle_click, R.id.overlay_drag, R.id.close_overlay, R.id.resize_bottom_left, R.id.resize_bottom_right)) {
                val view = windowViews().first { it.id == id && it.isShown }
                val rect = android.graphics.Rect()
                check(view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height) {
                    "Floating control clipped: $id"
                }
            }
        }
        val modes = JSONArray()
        for (multi in listOf(false, true)) {
            ui { ClickService.current!!.stopAll()
                if (multi) ClickService.current!!.showMultiPointOverlay() else ClickService.current!!.showTimedOverlay() }
            Thread.sleep(400)
            fullyVisible()
            val markerName = targetContext.getString(if (multi) R.string.multi_point_description else R.string.crosshair_description, 1)
            val markerBefore = overlayLocation { it.contentDescription == markerName }
            ui {
                val root = panel()
                val alpha = android.graphics.Color.alpha((root.background as android.graphics.drawable.ColorDrawable).color)
                check(alpha in 1..254 && root.alpha == 1f)
                val layout = root.layoutParams as android.view.WindowManager.LayoutParams
                check(layout.format == android.graphics.PixelFormat.TRANSLUCENT && layout.alpha == 1f)
            }
            val initial = bounds()
            resize(R.id.resize_bottom_right, 120, 200)
            val grown = bounds()
            check(grown[0] == initial[0] && grown[1] == initial[1] && grown[2] > initial[2] && grown[3] > initial[3])
            resize(R.id.resize_bottom_right, -400, -300)
            val smaller = bounds()
            check(smaller[0] == grown[0] && smaller[2] < grown[2] && smaller[3] < grown[3])
            fullyVisible()
            val drag = overlayLocation { it.id == R.id.overlay_drag }
            shell("input touchscreen swipe ${drag[0]} ${drag[1]} ${drag[0] + 200} ${drag[1] + 150} 350")
            Thread.sleep(250)
            val moved = bounds()
            resize(R.id.resize_bottom_left, -120, 100)
            val leftGrown = bounds()
            check(leftGrown[0] < moved[0] && leftGrown[2] > moved[2] && leftGrown[3] > moved[3]) {
                "Left corner did not grow: ${moved.toList()} -> ${leftGrown.toList()}"
            }
            check(leftGrown[0] + leftGrown[2] == moved[0] + moved[2] && leftGrown[1] == moved[1])
            resize(R.id.resize_bottom_left, 80, -60)
            val leftSmaller = bounds()
            check(leftSmaller[0] > leftGrown[0] && leftSmaller[2] < leftGrown[2] && leftSmaller[3] < leftGrown[3])
            check(leftSmaller[0] + leftSmaller[2] == leftGrown[0] + leftGrown[2])
            fullyVisible()
            val bottomRight = overlayLocation { it.id == R.id.resize_bottom_right }
            val size = screenSize(targetContext)
            resize(R.id.resize_bottom_right, size.first - 2 - bottomRight[0], size.second - 2 - bottomRight[1])
            val maximum = bounds()
            ui {
                val safe = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val insets = targetContext.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics
                        .windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or
                            android.view.WindowInsets.Type.displayCutout())
                    androidx.core.graphics.Insets.of(insets.left, insets.top, insets.right, insets.bottom)
                } else androidx.core.view.ViewCompat.getRootWindowInsets(panel())!!.getInsetsIgnoringVisibility(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                check(maximum[0] >= safe.left && maximum[1] >= safe.top && maximum[0] + maximum[2] <= size.first - safe.right &&
                    maximum[1] + maximum[3] <= size.second - safe.bottom) {
                    "Resize overlaps system bars: ${maximum.toList()} safe=$safe"
                }
            }
            fullyVisible()
            check(overlayLocation { it.contentDescription == markerName }.contentEquals(markerBefore))
            check(count() == 0) { "Resize must not inject a click" }
            saveScreenshot(if (multi) "v13-multi-resized.png" else "v13-timed-resized.png")
            modes.put(JSONObject().put("multi", multi).put("initial", JSONArray(initial.toList()))
                .put("grown", JSONArray(grown.toList())).put("minimum", JSONArray(smaller.toList()))
                .put("leftGrown", JSONArray(leftGrown.toList())).put("leftSmaller", JSONArray(leftSmaller.toList()))
                .put("maximum", JSONArray(maximum.toList())))
            overlayButton(if (multi) R.string.multi_close else R.string.clock_close)
            await("Resize panel did not close") { var closed = false; ui { closed = windowViews().none {
                it.id == R.id.resize_bottom_right && it.isShown } }; closed }
        }
        ui {
            SettingsStore(targetContext).save(mapOf("multiPoints" to "540.0,1200.0,10000", "multiPointDisplay" to displayKey(targetContext)))
            ClickService.current!!.showMultiPointOverlay()
        }
        Thread.sleep(400)
        overlayButton(R.string.auto_click_start)
        await("Multi loop not waiting") { phase() == RunPhase.WAITING }
        val running = bounds()
        resize(R.id.resize_bottom_right, 100, 100)
        check(bounds().contentEquals(running)) { "Running loop must freeze resizing" }
        overlayButton(R.string.auto_click_stop)
        await("Resized panel stop unavailable") { phase() == RunPhase.STOPPED }
        check(count() == 0)
        evidence.put("overlayResize", JSONObject().put("modes", modes).put("backgroundAlpha", 179)
            .put("bothCornersGrowAndShrink", true).put("controlsFullyVisible", true).put("systemBarsRespected", true)
            .put("clockDrawnAfterResize", true)
            .put("markersUnchanged", true).put("noInjectedClick", true).put("resizeFrozenWhilePlaying", true).put("stopReachable", true))
        ui { ClickService.current!!.stopAll() }
    }

    private fun multiPointOverlay() {
        val store = SettingsStore(targetContext)
        ui {
            ClickService.current!!.stopAll()
            store.save(mapOf("multiPoints" to "", "multiPointDisplay" to ""))
            activity.findViewById<Button>(R.id.show_recording_overlay).performClick()
        }
        fun markers(): List<View> = windowViews().filter { it.isShown && it.javaClass.simpleName == "NumberedMarker" }.toList()
        fun expectMarkers(count: Int) = await("Expected $count numbered markers") {
            var yes = false; ui { yes = markers().size == count }; yes
        }
        fun marker(index: Int) = overlayLocation { it.contentDescription == targetContext.getString(R.string.multi_point_description, index + 1) }
        fun edit(index: Int) {
            val xy = marker(index); touch(xy[0], xy[1])
            await("Point tap did not open delay editor") {
                var shown = false; ui { shown = windowViews().any { it.id == R.id.multi_delay_input && it.isShown } }; shown
            }
        }
        fun delay(index: Int, value: Long) {
            edit(index)
            ui { (windowViews().first { it.id == R.id.multi_delay_input && it.isShown } as EditText).setText(value.toString()) }
            dialogOk()
        }
        expectMarkers(1)
        ui {
            check(windowViews().count { it.id == R.id.toggle_click && it.isShown } == 1)
            check(windowViews().none { it.id == R.id.click_start_time && it.isShown })
            val header = windowViews().first { it.id == R.id.add_point && it.isShown }.parent as ViewGroup
            check(header.getChildAt(0).id == R.id.toggle_click && header.getChildAt(1).id == R.id.add_point &&
                header.getChildAt(2).id == R.id.overlay_drag)
        }
        showTarget(); receipt("reset")
        moveControlAway()
        overlayButton(R.string.multi_add_point); expectMarkers(2)
        overlayButton(R.string.multi_add_point); expectMarkers(3)
        for ((index, name) in listOf("A", "B", "C").withIndex()) {
            val from = marker(index); val to = point(name)
            shell("input touchscreen swipe ${from[0]} ${from[1]} ${to[0]} ${to[1]} 350")
            await("Point drag did not reach $name") {
                val at = marker(index); kotlin.math.abs(at[0] - to[0]) <= 2 && kotlin.math.abs(at[1] - to[1]) <= 2
            }
        }
        check(count() == 0) { "Editing or dragging must not click underlying target" }
        edit(0)
        ui {
            val input = windowViews().filterIsInstance<EditText>().first { it.id == R.id.multi_delay_input }
            check(input.text.toString() == "1000")
            input.setText("86400001")
            windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button1 && it.isShown }.performClick()
            check(input.isShown && input.error != null)
            input.setText("999")
        }
        dialogCancel()
        check(StepText.parse(store.get("multiPoints"))[0].waitBeforeMillis == 1_000L)
        delay(0, 1_000); delay(1, 400); delay(2, 650)
        saveScreenshot("v12-numbered-multipoint.png")
        var originalMarkers = emptyList<View>()
        ui { originalMarkers = markers() }
        fun checkMarkerInput(touchable: Boolean) = ui {
            check(markers().size == 3 && originalMarkers.all { original -> markers().any { it === original } })
            for (view in originalMarkers) {
                check(view.isShown && view.alpha == 1f)
                val flags = (view.layoutParams as android.view.WindowManager.LayoutParams).flags
                check((flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) == touchable)
            }
        }
        receipt("reset")
        val started = SystemClock.elapsedRealtime()
        overlayButton(R.string.auto_click_start)
        await("Sequence not running") { phase() in setOf(RunPhase.WAITING, RunPhase.RUNNING) }
        checkMarkerInput(false)
        saveScreenshot("v13-loop-visible.png")
        var observedCount = 0
        await("Two cycles not received", 10_000) {
            val received = count()
            if (received != observedCount) { checkMarkerInput(false); observedCount = received }
            received >= 6
        }
        overlayButton(R.string.auto_click_stop)
        await("Loop did not stop") { phase() == RunPhase.STOPPED }
        Thread.sleep(1_200)
        check(count() == 6) { "Stopping loop allowed another click" }
        check((0 until events().length()).map { events().getJSONObject(it).getString("point") } == listOf("A", "B", "C", "A", "B", "C"))
        val touches = JSONArray(receipt().getString("touches", "[]"))
        val downs = (0 until touches.length()).map { touches.getJSONObject(it) }.filter { it.getInt("action") == 0 }
        check(downs.size == 6)
        val gaps = downs.zipWithNext { previous, next -> next.getLong("elapsed") - previous.getLong("elapsed") }
        val expectedGaps = listOf(400L, 650L, 1_000L, 400L, 650L)
        check(downs[0].getLong("elapsed") - started in 1_000..1_800)
        check(gaps.zip(expectedGaps).all { (actual, expected) -> kotlin.math.abs(actual - expected) <= 150 }) { "Unexpected actual DOWN gaps: $gaps" }
        checkMarkerInput(true)
        delay(0, 2_000)
        receipt("reset")
        overlayButton(R.string.auto_click_start)
        await("Sequence not waiting") { phase() == RunPhase.WAITING }
        overlayButton(R.string.auto_click_stop)
        await("Sequence did not stop") { phase() == RunPhase.STOPPED }
        Thread.sleep(2_200); check(count() == 0)
        expectMarkers(3)
        overlayButton(R.string.auto_click_start)
        await("First point not received", 5_000) { count() == 1 }
        overlayButton(R.string.auto_click_stop)
        Thread.sleep(1_200); check(count() == 1) { "Stopping allowed another point" }
        expectMarkers(3)
        ui { ClickService.current!!.showTimedOverlay() }
        ui {
            check(markers().isEmpty() && windowViews().none { it.id == R.id.add_point && it.isShown })
            check(windowViews().count { it.id == R.id.click_start_time && it.isShown } == 1)
            ClickService.current!!.showMultiPointOverlay()
            check(windowViews().none { it.id == R.id.click_start_time && it.isShown })
        }
        expectMarkers(3)
        edit(1)
        ui { windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button3 && it.isShown }.performClick() }
        expectMarkers(2)
        ui {
            check(markers().map { it.contentDescription.toString() }.toSet() == (1..2).map {
                targetContext.getString(R.string.multi_point_description, it) }.toSet())
        }
        moveControlAway() // A reopened control bar starts at its default position and must not cover point A.
        overlayButton(R.string.auto_click_start)
        await("Final sequence not waiting") { phase() == RunPhase.WAITING }
        overlayButton(R.string.multi_close)
        await("Closing left multi windows") { var closed = false; ui { closed = markers().isEmpty() &&
            windowViews().none { it.id == R.id.add_point && it.isShown } }; closed }
        Thread.sleep(2_200)
        check(count() == 1) { "Closing a waiting sequence allowed another click" }
        ui { check(markers().isEmpty()); check(!ClickService.current!!.hasActiveTask) }
        ui { ClickService.current!!.showMultiPointOverlay() }
        expectMarkers(2)
        moveControlAway()
        overlayButton(R.string.auto_click_start)
        await("Sequence not waiting before service disconnect") { phase() == RunPhase.WAITING }
        serviceEnabled(false)
        Thread.sleep(2_200)
        check(count() == 1) { "Disconnect allowed another click" }
        serviceEnabled(true)
        ui { check(markers().isEmpty()); check(windowViews().none { it.id == R.id.add_point && it.isShown }) }
        evidence.put("multipoint", JSONObject().put("actualOrder", "A,B,C,A,B,C").put("actualDownGapsMillis", JSONArray(gaps))
            .put("firstDelayMillis", downs[0].getLong("elapsed") - started).put("numberedMarkers", true)
            .put("continuousLoop", true).put("manualStopAfterTwoCycles", true).put("markersRemainVisible", true)
            .put("sameMarkerWindows", true).put("runningMarkersPassThrough", true).put("stoppedMarkersTouchable", true)
            .put("stopBeforeFirst", true).put("stopBetweenPoints", true)
            .put("defaultDelay1000", true).put("invalidDelayRejected", true).put("cancelKeepsDelay", true)
            .put("mutuallyExclusive", true).put("deleteRenumbers", true).put("closeCancelsWaiting", true)
            .put("closeDoesNotRestore", true).put("serviceDisconnectCancels", true).put("reconnectDoesNotResume", true))
    }

    private fun clockDrawFreshness() {
        showTarget(); receipt("reset")
        ui { ClickService.current!!.showTimedOverlay() }
        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        var face: View? = null
        var field: java.lang.reflect.Field? = null
        ui {
            face = windowViews().first { it.javaClass.simpleName == "ClockFace" && it.isShown }
            field = face!!.javaClass.getDeclaredField("chars").apply { isAccessible = true }
        }
        val format = DateTimeFormatter.ofPattern("HH:mm:ss:SSS")
        val zone = java.util.TimeZone.getDefault()
        Thread.sleep(200)
        val ages = mutableListOf<Long>()
        repeat(120) {
            Thread.sleep(23) // Not aligned to either the service status timer or display vsync.
            var rendered = ""
            var now = 0L
            ui {
                // Timestamp before parsing; formatting/window traversal must not inflate the measured age.
                now = sync.reference().now(SystemClock.elapsedRealtime())
                rendered = String(field!!.get(face) as CharArray)
            }
            val shown = java.time.LocalTime.parse(rendered, format).toNanoOfDay() / 1_000_000
            val expected = Math.floorMod(now + zone.getOffset(now), 86_400_000L)
            ages.add(Math.floorMod(expected - shown + 43_200_000L, 86_400_000L) - 43_200_000L)
        }
        val sorted = ages.sorted()
        evidence.put("clockDrawFreshness", JSONObject().put("sampleCount", ages.size)
            .put("ageMillis", JSONArray(ages)).put("p50", sorted[60]).put("p95", sorted[113]).put("max", sorted.last()))
        check(sorted.first() >= 0) { "Clock must not invent future digits" }
        evidence.getJSONObject("clockDrawFreshness").put("sampledUnder50Millis", sorted.last() <= 50)
        // Functional check is deterministic: a draw after main-thread blocking must read NOW,
        // rather than waiting for a service tick. Strict runtime limits live in the independent R8 driver.
        ui {
            val view = face!!
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                Thread.sleep(70)
                val before = sync.reference().now(SystemClock.elapsedRealtime())
                view.draw(android.graphics.Canvas(bitmap))
                val after = sync.reference().now(SystemClock.elapsedRealtime())
                val rendered = String(field!!.get(view) as CharArray)
                val dayMillis = java.time.LocalTime.parse(rendered, format).toNanoOfDay() / 1_000_000
                val beforeDay = Math.floorMod(before + zone.getOffset(before), 86_400_000L)
                val advanced = Math.floorMod(dayMillis - beforeDay, 86_400_000L)
                check(advanced <= after - before) { "Draw used an old cached timestamp" }
                evidence.getJSONObject("clockDrawFreshness").put("drawReadsWithinCallInterval", true)
            } finally { bitmap.recycle() }
        }
        check(count() == 0)
        ui { ClickService.current!!.stopAll() }
    }

    private fun renderedClockError(): Long {
        var rendered = ""
        ui {
            val face = windowViews().first { it.javaClass.simpleName == "ClockFace" && it.isShown }
            rendered = String(face.javaClass.getDeclaredField("chars").apply { isAccessible = true }.get(face) as CharArray)
        }
        val external = request("/millis").getJSONObject("data").getLong("timestamp")
        val local = java.time.LocalTime.parse(rendered, DateTimeFormatter.ofPattern("HH:mm:ss:SSS"))
        val expected = Math.floorMod(external + java.util.TimeZone.getDefault().getOffset(external), 86_400_000)
        return Math.floorMod(local.toNanoOfDay() / 1_000_000 - expected + 43_200_000L, 86_400_000L) - 43_200_000L
    }

    private fun backgroundClockRecovery() {
        showTarget(); receipt("reset")
        ui { ClickService.current!!.showTimedOverlay() }
        request("/control?mode=good&offset=0"); source("/millis", periodic = true); synchronize()
        val before = sync.state.lastSuccessElapsed
        request("/control?mode=good&offset=4000")
        await("Background overlay did not periodically resynchronize", 6000) { sync.state.lastSuccessElapsed > before }
        Thread.sleep(300)
        val corrected = renderedClockError()
        check(kotlin.math.abs(corrected) < 500) { "Background clock ignored periodic sample: $corrected" }
        request("/control?mode=error")
        await("Network failure not observed", 6000) { sync.state.failed }
        val failedAt = sync.state.lastSuccessElapsed
        Thread.sleep(3000)
        check(sync.state.lastSuccessElapsed == failedAt && count() == 0)
        request("/control?mode=good&offset=-4000")
        await("Periodic clock did not recover automatically", 6000) { sync.state.lastSuccessElapsed > failedAt && !sync.state.failed }
        Thread.sleep(300)
        val recovered = renderedClockError()
        check(kotlin.math.abs(recovered) < 500) { "Recovered floating clock kept old reference: $recovered" }
        evidence.put("backgroundClockRecovery", JSONObject().put("correctedErrorMillis", corrected)
            .put("recoveredErrorMillis", recovered).put("noManualSyncOrMainResume", true).put("actualReceived", count()))
        ui { ClickService.current!!.stopAll() }; request("/control?mode=good&offset=0")
    }

    private fun clockRecoversAfterStall() {
        showTarget(); receipt("reset")
        ui { ClickService.current!!.showTimedOverlay() }
        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        Thread.sleep(200)
        var charsBefore = ""
        var charsWhileBlocked = ""
        var blockedMillis = 0L
        ui {
            val face = windowViews().first { it.javaClass.simpleName == "ClockFace" && it.isShown }
            val field = face.javaClass.getDeclaredField("chars").apply { isAccessible = true }
            charsBefore = String(field.get(face) as CharArray)
            val started = SystemClock.elapsedRealtime()
            // Fault injection only in instrumentation: drawing cannot progress while the main thread is busy.
            Thread.sleep(2_100)
            blockedMillis = SystemClock.elapsedRealtime() - started
            charsWhileBlocked = String(field.get(face) as CharArray)
        }
        check(charsBefore == charsWhileBlocked && blockedMillis >= 2_000)
        Thread.sleep(250)
        val error = renderedClockError()
        check(kotlin.math.abs(error) < 500) { "UI stall permanently accumulated into clock: $error" }
        check(count() == 0)
        evidence.put("clockStallRecovery", JSONObject().put("injectedMainBlockMillis", blockedMillis)
            .put("digitsFrozenDuringMainBlock", true).put("recoveredErrorMillis", error).put("actualReceived", count()))
        ui { ClickService.current!!.stopAll() }
    }

    private fun completedClockResynchronizes() {
        showTarget(); receipt("reset")
        val point = point("B").let { TapPoint(it[0].toFloat(), it[1].toFloat()) }
        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        ui { ClickService.current!!.prepareTimed(point, sync.reference().now(SystemClock.elapsedRealtime()) + 3_500) }
        moveControlAway(300)
        source("/millis"); synchronize(); overlayButton(R.string.auto_click_start)
        await("Single click did not complete", 8_000) { phase() == RunPhase.COMPLETED && count() == 1 }
        Thread.sleep(400)
        val errors = JSONArray()
        for (offset in listOf(4_000, -4_000, 0)) {
            request("/control?mode=good&offset=$offset"); synchronize()
            Thread.sleep(250)
            // Read the very characters Canvas draws, not the intentionally 1 Hz accessibility label.
            var rendered = ""
            ui {
                val face = windowViews().first { it.javaClass.simpleName == "ClockFace" && it.isShown }
                rendered = String(face.javaClass.getDeclaredField("chars").apply { isAccessible = true }.get(face) as CharArray)
            }
            val external = request("/millis").getJSONObject("data").getLong("timestamp")
            val local = java.time.LocalTime.parse(rendered, DateTimeFormatter.ofPattern("HH:mm:ss:SSS"))
            val shown = local.toNanoOfDay() / 1_000_000
            val expected = Math.floorMod(external + java.util.TimeZone.getDefault().getOffset(external), 86_400_000)
            val error = Math.floorMod(shown - expected + 43_200_000, 86_400_000) - 43_200_000
            errors.put(JSONObject().put("sourceStepMillis", offset).put("rendered", rendered).put("errorMillis", error))
            evidence.put("completedClockErrors", errors)
            check(kotlin.math.abs(error) < 500) { "Completed floating clock kept an old calibration: $error ms" }
            check(count() == 1) { "Resynchronization replayed a completed click" }
        }
        ui { ClickService.current!!.stopAll() }
    }

    private fun longWaitClockDiscipline() {
        showTarget(); receipt("reset")
        val point = point("B").let { TapPoint(it[0].toFloat(), it[1].toFloat()) }
        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        ui { ClickService.current!!.prepareTimed(point, sync.reference().now(SystemClock.elapsedRealtime()) + 12_000) }
        moveControlAway(300)
        source("/millis"); synchronize(); overlayButton(R.string.auto_click_start)
        await("Long wait did not arm") { phase() == RunPhase.WAITING }
        var originalDeadline = 0L
        ui { originalDeadline = ClickService.current!!.oneShotTiming!!.plannedElapsed }
        request("/control?mode=good&offset=300"); synchronize()
        await("Long wait ignored a newer reference") {
            var changed = false
            ui { changed = ClickService.current!!.oneShotTiming!!.plannedElapsed - originalDeadline in -600..-100 }
            changed
        }
        await("Long wait did not enter final lock", 12_000) {
            var locked = false
            ui { locked = ClickService.current!!.oneShotTiming!!.plannedElapsed - SystemClock.elapsedRealtime() in 1..4_500 }
            locked
        }
        var finalReference: TimeReference? = null
        ui { finalReference = ClickService.current!!.taskReference }
        request("/control?mode=good&offset=800"); synchronize()
        ui { check(ClickService.current!!.taskReference == finalReference); ClickService.current!!.stopAll() }
        check(count() == 0)

        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        ui { ClickService.current!!.prepareTimed(point, sync.reference().now(SystemClock.elapsedRealtime()) + 20_000) }
        moveControlAway(300)
        source("/millis"); synchronize(); overlayButton(R.string.auto_click_start)
        await("Jump-check task did not arm") { phase() == RunPhase.WAITING }
        request("/control?mode=good&offset=3000"); synchronize()
        await("Large reference jump did not fail closed") { phase() == RunPhase.FAILED }
        Thread.sleep(300)
        check(kotlin.math.abs(renderedClockError()) < 500) { "Failed task panel kept old calibration" }
        ui { ClickService.current!!.stopAll() }

        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        ui { ClickService.current!!.prepareTimed(point, sync.reference().now(SystemClock.elapsedRealtime()) + 3_500) }
        moveControlAway(300)
        source("/millis"); synchronize(); overlayButton(R.string.auto_click_start)
        await("Freshness-check task did not arm") { phase() == RunPhase.WAITING }
        request("/control?mode=malformed"); source("/seconds")
        await("Missing valid reference did not stop preparation", 4_000) { phase() == RunPhase.FAILED }
        check(count() == 0)
        ui { ClickService.current!!.stopAll() }
        request("/control?mode=good&offset=0")
        evidence.put("longWaitClockDiscipline", JSONObject().put("newSampleRebasesLongWait", true)
            .put("finalFiveSecondsFrozen", true).put("largeJumpStops", true).put("failedPanelUsesLatestReference", true).put("missingFreshReferenceStops", true)
            .put("actualReceived", count()))
    }

    private fun overlayDefaultsToNow() {
        serviceEnabled(true)
        showTarget(); receipt("reset")
        val store = SettingsStore(targetContext)
        val defaults = JSONArray()
        fun closeDefaultOverlay() {
            overlayButton(R.string.clock_close)
            await("Default overlay did not close") {
                var closed = false
                ui { closed = windowViews().none { it.id == R.id.click_start_time && it.isShown } }
                closed
            }
        }
        for (oldTarget in listOf((System.currentTimeMillis() + 3_600_000).toString(), "1", "")) {
            ui { ClickService.current!!.stopAll() }
            request("/control?mode=good&offset=5000"); source("/millis"); synchronize()
            store.save(mapOf("target" to oldTarget, "triggerMinuteMillis" to ""))
            var opened = 0L
            ui {
                val reference = sync.reference()
                check(reference.synced)
                val before = reference.now(SystemClock.elapsedRealtime())
                ClickService.current!!.showTimedOverlay()
                opened = store.get("target").toLongOrNull() ?: 0L
                check(opened in before..reference.now(SystemClock.elapsedRealtime())) {
                    "New overlay must default to current reference time, not the old target or now plus one minute"
                }
                check(!ClickService.current!!.hasActiveTask)
                val row = windowViews().filterIsInstance<TextView>().first { it.id == R.id.click_start_time }
                check(row.text.contains(ClockDigits.format(opened, ZoneId.systemDefault())))
                row.performClick()
            }
            await("Default time picker did not open") {
                var shown = false
                ui { shown = windowViews().filterIsInstance<android.widget.NumberPicker>().count { it.isShown } == 4 }
                shown
            }
            val at = Instant.ofEpochMilli(opened).atZone(ZoneId.systemDefault())
            ui {
                val wheels = windowViews().filterIsInstance<android.widget.NumberPicker>().filter { it.isShown }.toList()
                check(wheels.map { it.value } == listOf(at.hour, at.minute, at.second, at.nano / 1_000_000)) {
                    "All four wheels must start from the default time, including milliseconds"
                }
            }
            dialogCancel()
            check(store.get("target") == opened.toString())
            val adjusted = sync.reference().now(SystemClock.elapsedRealtime()) + 7_260_000
            val next = Instant.ofEpochMilli(adjusted).atZone(ZoneId.systemDefault())
            ui { windowViews().first { it.id == R.id.click_start_time }.performClick() }
            ui {
                val values = listOf(next.hour, next.minute, next.second, next.nano / 1_000_000)
                windowViews().filterIsInstance<android.widget.NumberPicker>().filter { it.isShown }.toList()
                    .forEachIndexed { index, wheel -> wheel.value = values[index] }
            }
            dialogOk()
            check(store.get("target").toLong() == adjusted)
            val remembered = (next.minute * 60_000 + next.second * 1_000 + next.nano / 1_000_000).toString()
            check(SettingsStore(targetContext).get("triggerMinuteMillis") == remembered)
            ui {
                ClickService.current!!.showTimedOverlay()
                check(store.get("target").toLong() == adjusted) { "Opening an already-visible overlay reset a user adjustment" }
                check(!ClickService.current!!.hasActiveTask)
            }
            closeDefaultOverlay()
            ui {
                ClickService.current!!.showTimedOverlay()
                val current = Instant.ofEpochMilli(sync.reference().now(SystemClock.elapsedRealtime())).atZone(ZoneId.systemDefault())
                val reopened = Instant.ofEpochMilli(store.get("target").toLong()).atZone(ZoneId.systemDefault())
                check(reopened.hour == current.hour && reopened.toLocalDate() == current.toLocalDate()) {
                    "Reopened overlay must use current reference date and hour: reopened=$reopened current=$current"
                }
                check(reopened.hour != next.hour) { "Test must distinguish current hour from confirmed hour" }
                check(reopened.minute == next.minute && reopened.second == next.second && reopened.nano == next.nano) {
                    "Confirmed minute, second and millisecond were not remembered"
                }
                windowViews().first { it.id == R.id.click_start_time }.performClick()
            }
            ui {
                val wheels = windowViews().filterIsInstance<android.widget.NumberPicker>().filter { it.isShown }.toList()
                check(wheels.size == 4)
                check(wheels[1].value == next.minute && wheels[2].value == next.second && wheels[3].value == next.nano / 1_000_000)
                wheels[1].value = (next.minute + 1) % 60
                wheels[2].value = (next.second + 1) % 60
                wheels[3].value = (next.nano / 1_000_000 + 1) % 1_000
            }
            dialogCancel()
            check(SettingsStore(targetContext).get("triggerMinuteMillis") == remembered) { "Cancelled adjustment replaced memory" }
            closeDefaultOverlay()
            check(count() == 0) { "Opening, editing or cancelling the default must not inject a click" }
            defaults.put(opened)
        }
        evidence.put("overlayDefaultTime", JSONObject().put("newWindowsUseCurrentReference", true)
            .put("confirmedMinuteSecondMillisPersist", true).put("reopeningUsesCurrentHour", true).put("cancelKeepsMemory", true)
            .put("futurePastAndMissingReplaced", true).put("millisecondWheelSeedPreserved", true)
            .put("adjustmentOnlySaves", true).put("visibleWindowKeepsAdjustment", true)
            .put("actualReceived", count()).put("openedDefaults", defaults))
    }

    private fun minimalOverlayTime() {
        openMain()
        ui {
            val text = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
            check(text.none { it == "准备定时" || it.startsWith("仅点击一次") }) { "Main page still contains removed timed controls" }
            check(text.none { it == "已停止" || it.startsWith("本次手势") }) { "Repeated task status still shown" }
        }
        Thread.sleep(250); saveScreenshot("v6-main-clean.png")
        showTarget(); receipt("reset")
        request("/control?mode=good&offset=0"); source("/millis"); synchronize()
        val store = SettingsStore(targetContext)
        val p = point("A")
        store.save(mapOf("pointDisplay" to displayKey(targetContext), "pointX" to p[0].toString(), "pointY" to p[1].toString(), "target" to ""))
        ui { ClickService.current!!.stopAll(); ClickService.current!!.toggleClock() }
        val openedDefault = store.get("target")
        moveControlAway(300); source("/millis"); synchronize()
        fun hasPicker(): Boolean {
            var found = false
            ui { found = windowViews().filterIsInstance<android.widget.NumberPicker>().count { it.isShown } == 4 }
            return found
        }
        fun edit() { ui { windowViews().first { it.id == R.id.click_start_time && it.isShown }.performClick() } }
        fun cancel() {
            ui { windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button2 && it.isShown }.performClick() }
            await("Picker cancellation did not dismiss") { !hasPicker() }
            Thread.sleep(300)
        }
        fun setFuture(): Long {
            val epoch = sync.reference().now(SystemClock.elapsedRealtime()) + 4_000
            val t = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
            ui {
                val values = listOf(t.hour, t.minute, t.second, t.nano / 1_000_000)
                windowViews().filterIsInstance<android.widget.NumberPicker>().toList().forEachIndexed { i, wheel -> wheel.value = values[i] }
            }
            return epoch
        }
        fun stopped() {
            // Input delivery and the posted header action complete asynchronously.
            await("Stopping did not cancel the task and reset the icon") {
                var idle = false
                ui {
                    idle = !ClickService.current!!.hasActiveTask &&
                        !windowViews().first { it.id == R.id.toggle_click }.isActivated
                }
                idle
            }
        }
        ui {
            val root = WindowInspector.getGlobalWindowViews().first { descendants(it).any { v -> v.id == R.id.toggle_click } }
            val texts = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.toList()
            check(texts.size == 1 && texts.single().id == R.id.click_start_time) { "Overlay still has coordinate/status paragraphs" }
        }
        Thread.sleep(250); saveScreenshot("v6-minimal-overlay.png")
        edit(); await("Time row did not open picker") { hasPicker() }
        Thread.sleep(300); saveScreenshot("v6-floating-time-picker.png")
        cancel(); stopped()
        check(store.get("target") == openedDefault)
        // A missing persisted value must still open the picker rather than start a task.
        store.save(mapOf("target" to ""))
        ui { ClickService.current!!.refreshClockTarget() }
        overlayButton(R.string.auto_click_start); await("Missing time play did not open picker") { hasPicker() }
        cancel(); stopped(); check(count() == 0)
        ui { check(windowViews().filterIsInstance<TextView>().first { it.id == R.id.click_start_time }.text == targetContext.getString(R.string.click_start_unset)) }
        edit(); await("Edit picker missing") { hasPicker() }; val chosen = setFuture(); dialogOk()
        await("Edited time not saved") { store.get("target").toLongOrNull() == chosen }
        stopped(); check(count() == 0)
        overlayButton(R.string.auto_click_start)
        await("Valid time did not arm directly") { phase() == RunPhase.WAITING }
        check(!hasPicker())
        await("Edited target did not receive one click", 6_500) { count() == 1 }
        await("One-shot not completed") { phase() == RunPhase.COMPLETED }
        Thread.sleep(350)
        overlayButton(R.string.auto_click_start); await("Expired time did not open picker") { hasPicker() }
        val next = setFuture(); dialogOk()
        await("Confirm from play did not arm") { phase() == RunPhase.WAITING && store.get("target").toLongOrNull() == next }
        overlayButton(R.string.auto_click_stop); stopped()
        Thread.sleep(4_200); check(count() == 1)
        edit(); await("Picker missing before close") { hasPicker() }
        var oldConfirm: Button? = null
        ui { oldConfirm = windowViews().filterIsInstance<Button>().last { it.id == android.R.id.button1 && it.isShown } }
        ui { ClickService.current!!.stopAll() }
        await("Closing overlay left a picker window") { !hasPicker() }
        Thread.sleep(300)
        ui { ClickService.current!!.toggleClock() }
        val previousTarget = store.get("target")
        edit(); await("New picker missing after reopening") { hasPicker() }
        ui { oldConfirm!!.performClick() }
        check(store.get("target") == previousTarget)
        stopped()
        edit()
        check(hasPicker()) // An old dismiss/confirm must not clear ownership and open a second picker.
        cancel()
        ui { ClickService.current!!.stopAll() }
        check(count() == 1)
        evidence.put("minimalOverlayTime", JSONObject().put("removedRepeatedText", true).put("editOnlySaves", true)
            .put("missingAndExpiredPlayOpenPicker", true).put("cancelDoesNotStart", true).put("confirmFromPlayArms", true)
            .put("validTimePlaysDirectly", true).put("closeDismissesPicker", true).put("stalePickerCannotStartOrReplace", true).put("actualReceived", 1))
    }

    private fun floatingPlayStop() {
        val store = SettingsStore(targetContext)
        val previous = listOf("pointDisplay", "pointX", "pointY", "target").associateWith { store.get(it) }
        fun control(): ImageButton {
            val button = checkNotNull(windowViews().filterIsInstance<ImageButton>().firstOrNull { it.id == R.id.toggle_click && it.isShown }) {
                "Floating header needs a play/stop control"
            }
            val panel = WindowInspector.getGlobalWindowViews().first { root -> descendants(root).any { it === button } }
            check(descendants(panel).none { it is Button }) { "Timed window must not retain legacy text buttons" }
            check(descendants(panel).any { it.javaClass.simpleName == "ClockFace" && it.isShown })
            return button
        }
        fun tapToggle() {
            var at = IntArray(2)
            ui { val view = control(); view.getLocationOnScreen(at); at = intArrayOf(at[0] + view.width / 2, at[1] + view.height / 2) }
            touch(at[0], at[1])
        }
        fun expect(playing: Boolean) {
            await("Play/stop control disagrees with the actual task: $playing") {
                var matches = false
                ui { matches = control().isActivated == playing && control().contentDescription == targetContext.getString(
                    if (playing) R.string.auto_click_stop else R.string.auto_click_start) &&
                    ((ClickService.current!!.oneShotStatus.phase in listOf(RunPhase.WAITING, RunPhase.RUNNING)) == playing) }
                matches
            }
        }
        fun expectFeedback(message: Int) {
            await("Toggle feedback was not visible") {
                var shown = false
                ui {
                    val view = windowViews().filterIsInstance<TextView>().firstOrNull { it.id == R.id.overlay_feedback }
                    shown = view?.isShown == true && view.text == targetContext.getString(message) &&
                        view.getGlobalVisibleRect(android.graphics.Rect())
                }
                shown
            }
        }
        try {
            showTarget(); receipt("reset")
            store.save(mapOf("pointDisplay" to "", "target" to ""))
            ui { ClickService.current!!.toggleClock(); control() }
            moveControlAway(300) // Leave the system Toast area visible below the floating clock.
            tapToggle()
            await("Missing time did not prompt") { var shown = false; ui { shown = windowViews().any { it is android.widget.NumberPicker && it.isShown } }; shown }
            dialogCancel()
            expect(false); check(count() == 0)
            request("/control?mode=good&offset=0"); source("/millis"); synchronize()
            val p = point("A")
            val targetTime = sync.reference().now(SystemClock.elapsedRealtime()) + 8_000
            store.save(mapOf("pointDisplay" to displayKey(targetContext), "pointX" to p[0].toString(),
                "pointY" to p[1].toString(), "target" to targetTime.toString()))
            val markerAt = overlayLocation { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
            shell("input touchscreen swipe ${markerAt[0]} ${markerAt[1]} ${p[0]} ${p[1]} 400")
            await("Floating crosshair not at configured point") {
                val actual = overlayLocation { it.contentDescription == targetContext.getString(R.string.crosshair_description) }
                kotlin.math.abs(actual[0] - p[0]) <= 2 && kotlin.math.abs(actual[1] - p[1]) <= 2
            }
            var original: View? = null
            ui {
                original = WindowInspector.getGlobalWindowViews().first { root -> descendants(root).any { it === control() } }
                val drag = windowViews().first { it.id == R.id.overlay_drag }
                val playAt = IntArray(2); val dragAt = IntArray(2)
                control().getLocationOnScreen(playAt); drag.getLocationOnScreen(dragAt)
                check(playAt[0] < dragAt[0]) { "Play must be left of the drag icon" }
            }
            tapToggle(); expect(true)
            expectFeedback(R.string.auto_click_enabled)
            ui {
                val start = checkNotNull(windowViews().filterIsInstance<TextView>().firstOrNull { it.id == R.id.click_start_time })
                check(start.text.contains(ClockDigits.format(targetTime, ZoneId.systemDefault())))
                check(start.textSize < targetContext.resources.getDimension(R.dimen.clock_text))
            }
            Thread.sleep(250); saveScreenshot("v4-play-enabled.png")
            tapToggle(); expect(false)
            expectFeedback(R.string.auto_click_disabled)
            ui { check(WindowInspector.getGlobalWindowViews().any { it === original && it.isShown }) { "Stop closed the clock" } }
            Thread.sleep(250); saveScreenshot("v4-play-disabled.png")
            repeat(2) { tapToggle(); expect(true); expectFeedback(R.string.auto_click_enabled)
                tapToggle(); expect(false); expectFeedback(R.string.auto_click_disabled) }
            val remaining = targetTime - sync.reference().now(SystemClock.elapsedRealtime())
            Thread.sleep(remaining.coerceAtLeast(0) + 400)
            check(count() == 0) { "Stopped task leaked a click" }
            ui { check(windowViews().first { it.id == R.id.overlay_feedback }.visibility == View.GONE) { "Feedback did not hide" } }
            val next = sync.reference().now(SystemClock.elapsedRealtime()) + 4_000
            store.save(mapOf("target" to next.toString()))
            tapToggle(); expect(true)
            await("Play did not deliver exactly one real click", 6500) { count() == 1 }
            expect(false)
            tapToggle()
            await("Expired time did not prompt") { var shown = false; ui { shown = windowViews().any { it is android.widget.NumberPicker && it.isShown } }; shown }
            dialogCancel()
            expect(false)
            Thread.sleep(400); check(count() == 1) { "Past task replayed" }
            ui { ClickService.current!!.prepareTimed(TapPoint(p[0].toFloat(), p[1].toFloat()),
                sync.reference().now(SystemClock.elapsedRealtime()) + 8_000) }
            moveControlAway(); source("/millis"); synchronize()
            ui { control() }
            Thread.sleep(250); saveScreenshot("v4-compact-prepared.png")
            tapToggle(); expect(true); tapToggle(); expect(false)
            ui { control(); check(!ClickService.current!!.hasActiveTask) { "Paused task still blocks configuration" } }
            evidence.put("floatingPlayStop", JSONObject().put("stoppedWithoutClosing", true)
                .put("rapidToggleCancelled", true).put("invalidConfigurationStayedStopped", true)
                .put("completedResetToPlay", true).put("preparedTaskPaused", true).put("noLegacyTextButtons", true)
                .put("visibleFeedbackOnEveryToggle", true).put("feedbackAutoHidden", true)
                .put("actualReceived", count()).put("targetMillis", targetTime))
        } finally { ui { ClickService.current?.stopAll() }; store.save(previous) }
    }
    private fun clockCloseIcon(): ImageButton {
        val close = checkNotNull(windowViews().filterIsInstance<ImageButton>().firstOrNull {
            it.isShown && it.contentDescription == targetContext.getString(R.string.clock_close)
        }) { "Clock must have an accessible close icon instead of a text button" }
        val panel = WindowInspector.getGlobalWindowViews().first { descendants(it).any { view -> view === close } }
        val iconAt = IntArray(2); val panelAt = IntArray(2)
        close.getLocationOnScreen(iconAt); panel.getLocationOnScreen(panelAt)
        val touch = targetContext.resources.getDimensionPixelSize(R.dimen.overlay_button_target)
        check(close.width >= touch && close.height >= touch && close.drawable != null && close.isEnabled)
        check(iconAt[0] + close.width == panelAt[0] + panel.width - panel.paddingRight &&
            iconAt[1] == panelAt[1] + panel.paddingTop) { "Close icon is not fixed at the upper-right corner" }
        val visible = android.graphics.Rect()
        check(close.getGlobalVisibleRect(visible) && visible.width() == close.width && visible.height() == close.height)
        check(windowViews().filterIsInstance<Button>().none { it.isShown && it.text == targetContext.getString(R.string.clock_close) })
        return close
    }
    private fun floatingClock() {
        showTarget(); receipt("reset")
        ui { ClickService.current!!.toggleClock() }
        await("Floating clock did not display its 24-hour millisecond time") {
            var found = false
            ui { found = windowViews().any { it.contentDescription?.toString()?.matches(Regex("[0-2][0-9]:[0-5][0-9]:[0-5][0-9]:[0-9]{3}")) == true } }
            found
        }
        moveControlAway()
        ui {
            clockCloseIcon()
            val face = windowViews().first { it.javaClass.simpleName == "ClockFace" && it.isShown }
            evidence.put("clockHardwareAccelerated", face.isHardwareAccelerated)
        }
        saveScreenshot("v2-floating-clock.png")
        check(count() == 0)
        overlayButton(R.string.clock_close)
        await("Closing the last clock kept periodic sync scheduled") { sync.state.nextAttemptElapsed == -1L && !sync.state.syncing }
        check(count() == 0)
        evidence.put("clockStandaloneDidNotClick", true).put("clockClosePausedSync", true).put("clockCloseIconTopRight", true)
    }
    private fun largeFontLayout() {
        val font = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        try {
            shell("settings put system font_scale 1.5")
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            await("Landscape or large-font configuration not applied") {
                targetContext.resources.configuration.let { it.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && it.fontScale >= 1.5f }
            }
            openMain()
            ui { activity.findViewById<Button>(R.id.show_clock).performClick() }
            Thread.sleep(400)
            ui {
                val close = clockCloseIcon()
                val clock = windowViews().first { it.javaClass.simpleName == "ClockFace" && it.isShown }
                check(clock.width > 0 && clock.height > 0 && clock.contentDescription?.length == 12)
                val xy = IntArray(2); close.getLocationOnScreen(xy)
                val size = screenSize(targetContext)
                check(close.height > 0 && xy[1] >= 0 && xy[1] + close.height <= size.second)
                check(close.getGlobalVisibleRect(android.graphics.Rect()))
            }
            saveScreenshot("v2-landscape-large-font.png")
            overlayButton(R.string.clock_close)
            await("Timed panel did not close before multi layout check") {
                var closed = false; ui { closed = windowViews().none { it.id == R.id.toggle_click && it.isShown } }; closed
            }
            ui { ClickService.current!!.showMultiPointOverlay() }
            Thread.sleep(400)
            ui {
                val clock = windowViews().first { it.javaClass.simpleName == "ClockFace" && it.isShown }
                check(clock.width > 0 && clock.height > 0 && clock.contentDescription?.length == 12)
                for (id in listOf(R.id.toggle_click, R.id.add_point, R.id.close_overlay, R.id.resize_bottom_left, R.id.resize_bottom_right)) {
                    val view = windowViews().first { it.id == id && it.isShown }
                    val rect = android.graphics.Rect()
                    check(view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height)
                }
            }
            saveScreenshot("v13-multi-landscape-large-font.png")
            overlayButton(R.string.multi_close)
            evidence.put("landscapeFontScale", 1.5).put("pinnedCloseControlVisible", true).put("multiResizeControlsVisible", true)
        } finally {
            ui { ClickService.current?.stopAll() }
            if (font == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $font")
            if (rotation == "null") shell("settings delete system user_rotation") else shell("settings put system user_rotation $rotation")
            if (auto == "null") shell("settings delete system accelerometer_rotation") else shell("settings put system accelerometer_rotation $auto")
            Thread.sleep(500)
        }
    }
}
