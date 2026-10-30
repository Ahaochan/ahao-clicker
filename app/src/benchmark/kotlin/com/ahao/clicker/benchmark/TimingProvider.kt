package com.ahao.clicker.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ahao.clicker.ClickService
import com.ahao.clicker.ClickerApp
import com.ahao.clicker.SettingsStore
import com.ahao.clicker.core.TapPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Signature-protected measurement control; compiled only into the optional R8 benchmark APK. */
class TimingProvider : ContentProvider() {
    private var face: android.view.View? = null
    private var drawListener: android.view.ViewTreeObserver.OnDrawListener? = null
    private var drawn = ""
    private var drawnElapsed = -1L
    private var draws = 0L
    private fun detachClock() {
        drawListener?.let { face?.viewTreeObserver?.takeIf { tree -> tree.isAlive }?.removeOnDrawListener(it) }
        face = null; drawListener = null
    }
    @androidx.annotation.RequiresApi(29)
    private fun observeClock() {
        fun find(view: android.view.View): android.view.View? {
            if (view.javaClass.simpleName == "ClockFace") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val view = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
        if (view == null) { detachClock(); return }
        if (view === face) return
        detachClock(); face = view
        val field = view.javaClass.getDeclaredField("chars").apply { isAccessible = true }
        drawListener = android.view.ViewTreeObserver.OnDrawListener {
            // OnDrawListener runs BEFORE child onDraw. Read its characters after traversal, without forcing a draw.
            val started = SystemClock.elapsedRealtime()
            view.post {
                if (face === view && view.isAttachedToWindow) {
                    drawn = String(field.get(view) as CharArray)
                    drawnElapsed = started; draws++
                }
            }
        }.also { view.viewTreeObserver.addOnDrawListener(it) }
    }

    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method.startsWith("v3_") && method !in setOf("v3_identity", "v3_bracket", "v3_geometry")) return when (method) {
            "v3_start" -> {
                detachClock()
                BenchmarkRecorder.start(requireNotNull(context), requireNotNull(extras?.getString("id")), extras.getBoolean("traceFrames"), extras.getString("mode") ?: if (extras.getBoolean("traceFrames")) "frame" else "light")
            }
            "v3_attempt" -> BenchmarkRecorder.status().also { BenchmarkRecorder.beginAttempt(requireNotNull(extras).getLong("attemptId")) }
            "v3_calibrate" -> BenchmarkRecorder.calibrate()
            "v3_status" -> BenchmarkRecorder.status()
            "v3_waitTerminal" -> BenchmarkRecorder.awaitTerminal(requireNotNull(extras).getLong("timeoutMs", 15_000))
            "v3_stall" -> BenchmarkRecorder.scheduleStall(requireNotNull(extras).getLong("atElapsed"), extras.getLong("durationMillis"))
            "v3_probe" -> BenchmarkRecorder.status().apply {
                val app = requireNotNull(context).applicationContext as ClickerApp
                val reference = app.timeSync.reference()
                putBoolean("connected", ClickService.current != null)
                putBoolean("fresh", app.timeSync.hasFreshSample())
                putLong("referenceEpoch", reference.epochMillis)
                putLong("referenceElapsed", reference.elapsedMillis)
                putLong("lastSuccess", app.timeSync.state.lastSuccessElapsed)
            }
            "v3_end" -> BenchmarkRecorder.stop()
            else -> error("Unknown V3 command")
        }
        var result = Bundle()
        val done = CountDownLatch(1)
        val action = Runnable {
            try {
                val app = context!!.applicationContext as ClickerApp
                val service = ClickService.current
                when (method) {
                    "v3_geometry" -> {
                        check(android.os.Build.VERSION.SDK_INT >= 29) { "Screenshot geometry requires API 29" }
                        check(BenchmarkRecorder.isActive) { "Geometry requires an explicit measurement session" }
                        fun find(view: android.view.View): android.view.View? {
                            if (view.javaClass.simpleName == "ClockFace") return view
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                            return null
                        }
                        val view = android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull(::find).singleOrNull()
                            ?: error("No unique clock")
                        val xy = IntArray(2); view.getLocationOnScreen(xy)
                        val paint = view.javaClass.getDeclaredField("paint").apply { isAccessible = true }.get(view) as android.graphics.Paint
                        val textWidth = view.javaClass.getDeclaredField("textWidth").apply { isAccessible = true }.getFloat(view)
                        result.putInt("x", xy[0]); result.putInt("y", xy[1])
                        result.putInt("width", view.width); result.putInt("height", view.height)
                        result.putFloat("textSize", paint.textSize); result.putInt("textColor", paint.color)
                        result.putFloat("textWidth", textWidth)
                        result.putInt("backgroundColor", requireNotNull(context).getColor(com.ahao.clicker.R.color.control_panel_background))
                    }
                    "v3_identity" -> {
                        if (android.os.Build.VERSION.SDK_INT < 29) error("Window identity requires API 29")
                        fun containsClock(view: android.view.View): Boolean {
                            if (view.javaClass.simpleName == "ClockFace") return true
                            return view is android.view.ViewGroup && (0 until view.childCount).any { containsClock(view.getChildAt(it)) }
                        }
                        val root = android.view.inspector.WindowInspector.getGlobalWindowViews().single(::containsClock)
                        val params = root.layoutParams as android.view.WindowManager.LayoutParams
                        val title = requireNotNull(extras?.getString("title"))
                        require(title.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
                        params.title = title
                        root.context.getSystemService(android.view.WindowManager::class.java).updateViewLayout(root, params)
                        result.putString("windowTitle", title)
                    }
                    "v3_bracket" -> result = BenchmarkRecorder.clockBracket()
                    "setupV3" -> {
                        SettingsStore(app).save(mapOf("source" to "JD", "syncInterval" to "60000"))
                        app.refreshTimeSources()
                    }
                    "sync" -> app.timeSync.syncNow(SettingsStore(app).source(), true)
                    "prepare" -> {
                        checkNotNull(service)
                        check(app.timeSync.hasFreshSample())
                        val delay = extras!!.getLong("delay", 2_000)
                        require(delay in 500..3_600_000)
                        val reference = app.timeSync.reference()
                        val elapsed = SystemClock.elapsedRealtime()
                        val target = reference.now(elapsed) + delay
                        service.prepareTimed(TapPoint(extras.getFloat("x"), extras.getFloat("y")), target)
                        result.putLong("target", target)
                        result.putLong("initialPlannedElapsed", elapsed + delay)
                    }
                    "clock" -> {
                        checkNotNull(service)
                        SettingsStore(app).save(mapOf("source" to "JD", "syncInterval" to "60000"))
                        app.refreshTimeSources()
                        service.showTimedOverlay()
                    }
                    "stop" -> { detachClock(); service?.stopAll() }
                    "state", "diagnose" -> Unit
                    else -> error("Unknown measurement command")
                }
                if (android.os.Build.VERSION.SDK_INT >= 29 && !BenchmarkRecorder.isActive) observeClock()
                result.putString("drawnText", drawn)
                result.putLong("drawnElapsed", drawnElapsed)
                result.putLong("draws", draws)
                val elapsed = SystemClock.elapsedRealtime()
                val reference = app.timeSync.reference()
                result.putBoolean("connected", service != null)
                result.putBoolean("fresh", app.timeSync.hasFreshSample())
                if (method == "diagnose") {
                    result.putBoolean("hasActiveTask", service?.hasActiveTask == true)
                    result.putString("statusPhase", service?.status?.phase?.name)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        fun descendants(view: android.view.View): Sequence<android.view.View> = sequence {
                            yield(view)
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
                        }
                        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        val allViews = roots.asSequence().flatMap(::descendants).toList()
                        val views = allViews.filter { it.isShown }
                        result.putInt("windowCount", roots.size)
                        result.putInt("pickerWheelCount", views.count { it is android.widget.NumberPicker })
                        (allViews.firstOrNull { it.id == com.ahao.clicker.R.id.overlay_feedback } as? android.widget.TextView)?.let {
                            result.putString("lastFeedback", it.text.toString().take(512))
                            result.putBoolean("feedbackVisible", it.isShown)
                        }
                        views.firstOrNull { it.id == com.ahao.clicker.R.id.toggle_click }?.let {
                            result.putBoolean("toggleActivated", it.isActivated)
                            result.putString("toggleDescription", it.contentDescription?.toString())
                        }
                    }
                }
                result.putLong("elapsed", elapsed)
                result.putLong("uptime", SystemClock.uptimeMillis())
                result.putLong("sourceEpoch", reference.now(elapsed))
                result.putLong("sourceAnchorElapsed", reference.elapsedMillis)
                result.putLong("lastSuccessElapsed", app.timeSync.state.lastSuccessElapsed)
                result.putLong("rtt", app.timeSync.state.rttMillis)
                result.putLong("deviceEpoch", System.currentTimeMillis())
                result.putString("phase", service?.oneShotStatus?.phase?.name)
                result.putAll(BenchmarkRecorder.failureState())
                result.putLong("armedElapsed", service?.oneShotArmedElapsed ?: 0L)
                service?.taskReference?.let { result.putLong("taskEpoch", it.now(elapsed)) }
                service?.oneShotTiming?.let {
                    result.putLong("targetEpoch", it.targetEpoch)
                    result.putLong("plannedElapsed", it.plannedElapsed)
                    it.dispatchedElapsed?.let { value -> result.putLong("dispatchedElapsed", value) }
                    it.callbackElapsed?.let { value -> result.putLong("callbackElapsed", value) }
                }
            } catch (error: Exception) { result.putString("error", error.message ?: error.javaClass.simpleName) }
            finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else Handler(Looper.getMainLooper()).post(action)
        check(done.await(5, TimeUnit.SECONDS)) { "Measurement control timed out" }
        return result
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = error("Unsupported")
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = error("Unsupported")
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = error("Unsupported")
}
