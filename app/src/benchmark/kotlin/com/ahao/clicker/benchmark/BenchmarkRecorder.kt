package com.ahao.clicker.benchmark

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.os.Looper
import androidx.core.os.HandlerCompat
import com.ahao.clicker.core.TimingBuffer
import com.ahao.clicker.core.TimingObservation
import com.ahao.clicker.core.TimingObserver
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Benchmark-only fixed binary journal. No per-frame JSON, formatting, or disk access on the UI thread. */
internal object BenchmarkRecorder : TimingObserver {
    @Volatile private var active = false
    val isActive: Boolean get() = active
    private var mode = "off"
    private val stallHandler = HandlerCompat.createAsync(Looper.getMainLooper())
    @Volatile private var stallGeneration = 0L
    @Volatile private var stallTask: Runnable? = null
    @Volatile private var stallStartedNs = 0L
    @Volatile private var stallEndedNs = 0L
    @Volatile private var stallAtMs = 0L
    @Volatile private var failureEvent = 0
    @Volatile private var failureReason = 0L
    @Volatile private var failureDetail = ""
    private val failureSink = object : TimingObserver {
        override fun record(event: Int, a: Long, b: Long, c: Long, d: Long) {
            if (!active) return
            failureReason = a; failureEvent = event
        }
        override fun text(event: Int, value: String) {
            if (active) failureDetail = value.take(2048)
        }
    }
    private var clockMarker = 0L
    @Volatile var attempt = 0L
        private set
    @Volatile private var criticalStart = Long.MAX_VALUE
    @Volatile private var criticalEnd = Long.MIN_VALUE
    @Volatile private var deadline = 0L
    @Volatile private var armed = false
    @Volatile private var terminalNs = 0L
    @Volatile private var terminalPhase = -1L
    @Volatile private var terminal = java.util.concurrent.CountDownLatch(1)
    @Volatile private var written = 0L
    @Volatile private var lastFlush = 0L
    @Volatile private var failure = ""
    @Volatile private var traceFrames = false
    private var frames = 0L
    private var buffer: TimingBuffer? = null
    private var output: BufferedOutputStream? = null
    private var textOutput: BufferedOutputStream? = null
    private var executor: java.util.concurrent.ScheduledExecutorService? = null
    private val headers = ArrayBlockingQueue<String>(128)
    private val drain = LongArray(8 * 1024)
    private val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private val tids = ThreadLocal.withInitial { Process.myTid().toLong() }

    @Synchronized fun start(context: Context, id: String, tracing: Boolean, recordingMode: String = if (tracing) "frame" else "light"): Bundle {
        require(recordingMode in setOf("off", "light", "frame"))
        require(!active && executor == null) { "An observation session is already active" }
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "v3/$id")
        require(!directory.exists() && directory.mkdirs()) { "Session exists or cannot be created" }
        resetStall()
        val metadata = JSONObject().put("protocolVersion", "timing-v3-repair").put("mode", recordingMode).put("recordBytes", 64)
            .put("byteOrder", "little_endian").put("pid", Process.myPid())
            .put("bootCount", android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1))
            .put("startedElapsedNs", SystemClock.elapsedRealtimeNanos())
            .put("packedEvent", "attemptId in high32, eventCode in low32")
        File(directory, "metadata.json").writeText(metadata.toString())
        mode = recordingMode; buffer = if (mode == "off") null else TimingBuffer(8192)
        TimingObservation.windowTitle = "clock_$id"
        headers.clear(); frames = 0; attempt = 0; clockMarker = 0
        failureEvent = 0; failureReason = 0; failureDetail = ""
        criticalStart = Long.MAX_VALUE; criticalEnd = Long.MIN_VALUE; deadline = 0
        armed = false; terminalNs = 0; terminalPhase = -1; terminal = java.util.concurrent.CountDownLatch(1)
        failure = ""; written = 0; lastFlush = SystemClock.elapsedRealtimeNanos(); traceFrames = tracing && mode == "frame"
        output = BufferedOutputStream(File(directory, "events.bin").outputStream(), 64 * 1024)
        textOutput = BufferedOutputStream(File(directory, "headers.jsonl").outputStream(), 4096)
        active = true
        TimingObservation.observer = if (mode == "off") null else this
        TimingObservation.failureObserver = if (mode == "off") failureSink else null
        executor = if (mode == "off") null else Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "timing-journal").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay({ flush(false) }, 250, 250, TimeUnit.MILLISECONDS) }
        return status().apply { putString("directory", directory.absolutePath) }
    }

    /** No tasks/drawing during calibration; full installed observer path is measured. */
    fun calibrate(): Bundle {
        require(active && mode != "off")
        repeat(10_000) { if (it % 1000 == 0) flush(true); TimingObservation.emit(310) }
        val raw = LongArray(50_000)
        val control = LongArray(50_000)
        for (batch in 0 until 5) {
            flush(true)
            // Drain between chunks to test the normal bounded recorder, never overflow it.
            for (index in 0 until 10_000) {
                if (index % 1000 == 0) flush(true)
                val empty = System.nanoTime()
                control[batch * 10_000 + index] = System.nanoTime() - empty
                val before = System.nanoTime()
                TimingObservation.emit(310)
                raw[batch * 10_000 + index] = System.nanoTime() - before
            }
        }
        flush(true)
        return Bundle().apply { putLongArray("recordNs", raw); putLongArray("controlNs", control) }
    }

    fun clockBracket(): Bundle {
        require(active)
        val id = ++clockMarker
        val before = SystemClock.elapsedRealtimeNanos()
        val uptime = SystemClock.uptimeMillis()
        if (android.os.Build.VERSION.SDK_INT >= 29) Trace.setCounter("clock_bracket", id)
        val after = SystemClock.elapsedRealtimeNanos()
        record(210, id, before, after, uptime)
        return Bundle().apply {
            putLong("id", id); putLong("beforeNs", before); putLong("afterNs", after); putLong("uptimeMs", uptime)
        }
    }

    @Synchronized fun beginAttempt(value: Long) {
        require(value in 1..1_000_000 && active)
        resetStall()
        attempt = value
        failureEvent = 0; failureReason = 0; failureDetail = ""
        armed = false; deadline = 0; terminalNs = 0; terminalPhase = -1
        terminal = java.util.concurrent.CountDownLatch(1)
        criticalStart = Long.MAX_VALUE; criticalEnd = Long.MIN_VALUE
        record(300, value, 0, 0, 0)
    }

    override fun record(event: Int, a: Long, b: Long, c: Long, d: Long) {
        if (!active || mode == "off" || (mode == "light" && (event == 130 || event in 200..204))) return
        val stamp = SystemClock.elapsedRealtimeNanos()
        var second = b
        when (event) {
            TimingObservation.ARM -> { armed = true; deadline = b; criticalStart = (b - 750) * 1_000_000; criticalEnd = Long.MAX_VALUE }
            TimingObservation.REBASE_ACCEPT -> { deadline = b; criticalStart = (b - 750) * 1_000_000 }
            TimingObservation.TERMINAL -> if (deadline > 0) {
                criticalEnd = maxOf((deadline + 3_000) * 1_000_000, stamp + 500_000_000)
                if (armed) { terminalNs = stamp; terminalPhase = a; armed = false }
            }
            TimingObservation.FRAME_BEGIN -> {
                frames++; second = frames
                if (traceFrames) {
                    Trace.beginSection("clock_draw")
                    if (android.os.Build.VERSION.SDK_INT >= 29) Trace.setCounter("clock_draw_id", frames)
                }
            }
            TimingObservation.FRAME_VALUE -> {
                second = frames
                if (traceFrames && android.os.Build.VERSION.SDK_INT >= 29) Trace.setCounter("clock_epoch", a)
            }
            TimingObservation.FRAME_END -> { second = frames; if (traceFrames) Trace.endSection() }
        }
        if (buffer?.record(stamp, tids.get(), event, a, second, c, d, attempt) == false) failure = "event_buffer_overflow"
        if (event == TimingObservation.TERMINAL && terminalNs == stamp) terminal.countDown()
    }

    override fun text(event: Int, value: String) {
        if (!active || mode == "off") return
        val row = JSONObject().put("event", event).put("attemptId", attempt)
            .put("elapsedNs", SystemClock.elapsedRealtimeNanos()).put("value", value.take(2048))
        if (value.length > 2048 || !headers.offer(row.toString())) failure = "header_buffer_overflow"
    }

    @Synchronized private fun flush(force: Boolean) {
        val stamp = SystemClock.elapsedRealtimeNanos()
        if (!force && stamp >= criticalStart && stamp <= criticalEnd) {
            if (stamp - lastFlush > 30_000_000_000) failure = "journal_flush_stalled"
            return
        }
        try {
            while (true) {
                val count = buffer?.drain(drain) ?: 0
                if (count == 0) break
                bytes.clear()
                repeat(count * 8) { bytes.putLong(drain[it]) }
                output?.write(bytes.array(), 0, count * 64)
                written += count
            }
            while (true) {
                val row = headers.poll() ?: break
                textOutput?.write((row + "\n").toByteArray(Charsets.UTF_8))
            }
            output?.flush(); textOutput?.flush(); lastFlush = stamp
        } catch (error: Exception) { failure = error.javaClass.simpleName + ":" + error.message }
    }

    fun status() = Bundle().apply {
        putAll(failureState())
        putString("mode", mode); putBoolean("active", active); putLong("attemptId", attempt); putLong("deadline", deadline)
        putLong("dropped", buffer?.dropped ?: 0); putInt("queued", buffer?.size ?: 0); putLong("written", written)
        putLong("lastFlushNs", lastFlush); putLong("frames", frames)
        putLong("observerFailures", TimingObservation.failures); putString("error", failure)
        putLong("elapsedNs", SystemClock.elapsedRealtimeNanos())
        putLong("terminalNs", terminalNs); putLong("terminalPhase", terminalPhase)
    }

    fun awaitTerminal(timeoutMs: Long): Bundle {
        require(timeoutMs in 1..30_000)
        val pending = terminal
        check(pending.await(timeoutMs, TimeUnit.MILLISECONDS)) { "Terminal event wait timed out" }
        return status()
    }

    fun stop(): Bundle {
        synchronized(this) {
            active = false; stallGeneration++
            stallTask?.let(stallHandler::removeCallbacks); stallTask = null
        }
        TimingObservation.observer = null; TimingObservation.windowTitle = null
        TimingObservation.failureObserver = null
        val worker = executor; executor = null
        worker?.shutdown(); worker?.awaitTermination(5, TimeUnit.SECONDS)
        synchronized(this) {
            flush(true); output?.close(); textOutput?.close(); output = null; textOutput = null
        }
        return status()
    }

    fun failureState() = Bundle().apply {
        putInt("failureEvent", failureEvent); putLong("failureReason", failureReason)
        putString("failureDetail", failureDetail)
        putLong("stallAtMs", stallAtMs); putLong("stallStartedNs", stallStartedNs); putLong("stallEndedNs", stallEndedNs)
        putBoolean("stallPending", stallTask != null)
    }

    private fun resetStall() {
        stallGeneration++
        stallTask?.let(stallHandler::removeCallbacks); stallTask = null
        stallAtMs = 0; stallStartedNs = 0; stallEndedNs = 0
    }

    /** Explicit benchmark-only fault injection; no ordinary APK exposes this command. */
    @Synchronized fun scheduleStall(atElapsed: Long, duration: Long): Bundle {
        require(active && mode == "off" && !traceFrames && duration == 670L)
        require(stallTask == null && stallStartedNs == 0L)
        val delay = atElapsed - SystemClock.elapsedRealtime()
        require(delay in 100..30_000)
        val token = stallGeneration
        val task = object : Runnable {
            override fun run() {
                synchronized(this@BenchmarkRecorder) {
                    if (!active || token != stallGeneration || stallTask !== this) return
                    stallTask = null; stallStartedNs = SystemClock.elapsedRealtimeNanos()
                }
                SystemClock.sleep(duration)
                synchronized(this@BenchmarkRecorder) {
                    if (active && token == stallGeneration) stallEndedNs = SystemClock.elapsedRealtimeNanos()
                }
            }
        }
        stallAtMs = atElapsed; stallTask = task
        check(stallHandler.postAtTime(task, SystemClock.uptimeMillis() + delay)) {
            stallTask = null; "Fault scheduling rejected"
        }
        return failureState()
    }
}
