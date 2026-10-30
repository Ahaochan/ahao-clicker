package com.ahao.clicker.time

import android.os.SystemClock
import com.ahao.clicker.core.SyncedTime
import com.ahao.clicker.core.TimeFormat
import com.ahao.clicker.core.TimeReference
import com.ahao.clicker.core.TimeSourceConfig
import com.ahao.clicker.core.NtpPacket
import com.ahao.clicker.core.SyncPacing
import com.ahao.clicker.core.TimingObservation
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Independent provider state; all providers share two bounded network workers. */
class TimeSyncManager {
    private val time = SyncedTime()
    private val pacing = SyncPacing()
    companion object {
        private val worker = ScheduledThreadPoolExecutor(2).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var config: TimeSourceConfig? = null
    private var enabled = false
    private var generation = 0L
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var lookup: AddressLookup? = null
    @Volatile var state = SyncState()
        private set

    fun reference(): TimeReference = time.snapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis())

    @Synchronized fun configure(value: TimeSourceConfig, periodic: Boolean) {
        if (value == config && periodic == enabled) return
        generation++
        if (config?.url != value.url || config?.format != value.format || config?.field != value.field || config?.builtin != value.builtin) {
            time.reset()
            state = SyncState()
        }
        config = value
        enabled = periodic
        state = state.copy(syncing = false, nextAttemptElapsed = -1)
        scheduled?.cancel(true)
        connection?.disconnect()
        socket?.close()
        lookup?.close()
        scheduled = null
        if (enabled) enqueue(generation, 0)
    }

    @Synchronized fun pause() {
        generation++
        enabled = false
        scheduled?.cancel(true)
        connection?.disconnect()
        socket?.close()
        lookup?.close()
        state = state.copy(syncing = false, nextAttemptElapsed = -1)
    }

    @Synchronized fun syncNow(value: TimeSourceConfig, periodic: Boolean) {
        configure(value, periodic)
        generation++
        scheduled?.cancel(true)
        connection?.disconnect()
        socket?.close()
        lookup?.close()
        enqueue(generation, 0)
    }

    @Synchronized private fun enqueue(token: Long, delay: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        val wait = maxOf(delay, pacing.delay(config?.builtin, elapsed))
        state = state.copy(nextAttemptElapsed = elapsed + wait)
        scheduled = worker.schedule({ synchronize(token) }, wait, TimeUnit.MILLISECONDS)
    }

    private fun synchronize(token: Long) {
        val attemptElapsed = SystemClock.elapsedRealtime()
        val source = synchronized(this) {
            if (token != generation) return
            state = state.copy(syncing = true, failed = false, nextAttemptElapsed = -1)
            pacing.attempted(config?.builtin, SystemClock.elapsedRealtime())
            config ?: return
        }
        var client: HttpURLConnection? = null
        try {
            TimingObservation.emit(TimingObservation.SYNC_BEGIN, (source.builtin?.ordinal ?: -1).toLong(), token)
            if (source.builtin != null && source.url.isEmpty()) {
                synchronizeNtp(source, token)
                return
            }
            var sent = SystemClock.elapsedRealtime()
            val headerTime = source.format == TimeFormat.JD_REQUEST_ID
            val url = if (source.builtin == null || headerTime) source.url else source.url +
                (if ('?' in source.url) "&" else "?") + "_=" + SystemClock.elapsedRealtimeNanos()
            client = URL(url).openConnection() as HttpURLConnection
            synchronized(this) {
                if (token != generation) return
                connection = client
            }
            client.connectTimeout = 3_000
            client.readTimeout = 3_000
            client.requestMethod = if (headerTime) "OPTIONS" else "GET"
            client.instanceFollowRedirects = false
            client.useCaches = false
            client.setRequestProperty("Cache-Control", "no-cache, no-store")
            client.setRequestProperty("User-Agent", "AhaoClicker/0.2 Android")
            if (headerTime) {
                // Android prepares the connection here; measure the bodyless request after TLS setup.
                TimingObservation.emit(TimingObservation.SYNC_CONNECT_BEGIN)
                client.connect()
                TimingObservation.emit(TimingObservation.SYNC_CONNECT_END)
                sent = SystemClock.elapsedRealtime()
            }
            require(client.responseCode in 200..299)
            require((client.getHeaderField("Age")?.toLongOrNull() ?: 0) == 0L)
            val body = if (source.format == TimeFormat.HTTP_DATE || headerTime) "" else {
                client.inputStream.use { input ->
                    val buffer = ByteArray(65_537)
                    var size = 0
                    while (size < buffer.size) {
                        require(SystemClock.elapsedRealtime() - sent <= 3_000)
                        val read = input.read(buffer, size, buffer.size - size)
                        if (read < 0) break
                        size += read
                    }
                    require(size <= 65_536)
                    String(buffer, 0, size, Charsets.UTF_8)
                }
            }
            val received = SystemClock.elapsedRealtime()
            TimingObservation.emit(TimingObservation.SYNC_RESPONSE, sent, received, token)
            TimingObservation.observer?.text(TimingObservation.SYNC_HEADERS,
                listOf(source.builtin?.name ?: "CUSTOM", client.getHeaderField("X-API-Request-Id") ?: "",
                    client.getHeaderField("Date") ?: "", client.getHeaderField("Age") ?: "", client.responseCode.toString()).joinToString("\t"))
            val epoch = TimeResponseParser.parse(source.format, source.field, client.getHeaderField("Date"), body,
                if (headerTime) client.getHeaderField("X-API-Request-Id") else null)
            synchronized(this) {
                if (token == generation) {
                    require(time.accept(epoch, sent, received))
                    state = SyncState(lastSuccessElapsed = received, rttMillis = received - sent)
                    TimingObservation.emit(TimingObservation.SYNC_ACCEPT, epoch, sent, received, token)
                }
            }
        } catch (error: Exception) {
            TimingObservation.emit(TimingObservation.SYNC_ERROR, token)
            synchronized(this) { if (token == generation) state = state.copy(syncing = false, failed = true,
                failure = if (error is java.net.SocketTimeoutException) SyncFailure.TIMEOUT else SyncFailure.NETWORK_OR_SAMPLE) }
        } finally {
            client?.disconnect()
            synchronized(this) {
                if (connection === client) connection = null
                if (token == generation && enabled) enqueue(token,
                    (source.intervalMillis - (SystemClock.elapsedRealtime() - attemptElapsed)).coerceAtLeast(0))
            }
        }
    }

    private fun synchronizeNtp(source: TimeSourceConfig, token: Long) {
        val requestLookup = AddressLookup()
        val address = requestLookup.use { dns ->
            synchronized(this) {
                if (token != generation) return
                lookup = dns
            }
            try { dns.resolve(checkNotNull(source.builtin).host) }
            finally { synchronized(this) { if (lookup === dns) lookup = null } }
        }
        DatagramSocket().use { udp ->
            synchronized(this) {
                if (token != generation) return
                socket = udp
            }
            try {
                udp.soTimeout = 2_000
                udp.connect(address, 123)
                val epoch = System.currentTimeMillis()
                val request = NtpPacket.request(epoch)
                // Preserve the seconds part, randomize fractional bits for response correlation.
                val nonce = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
                nonce.copyInto(request, 44)
                val sent = SystemClock.elapsedRealtime()
                udp.send(DatagramPacket(request, request.size))
                val packet = DatagramPacket(ByteArray(512), 512)
                udp.receive(packet)
                val received = SystemClock.elapsedRealtime()
                val sample = NtpPacket.receive(request, packet.data, packet.length, sent, received, epoch)
                synchronized(this) {
                    if (token != generation) return
                    require(time.accept(sample.epochAtReceipt, received, received))
                    state = SyncState(lastSuccessElapsed = received, rttMillis = received - sent)
                }
            } finally { synchronized(this) { if (socket === udp) socket = null } }
        }
    }

    fun hasFreshSample(): Boolean = reference().synced && state.lastSuccessElapsed >= 0 &&
        SystemClock.elapsedRealtime() - state.lastSuccessElapsed in 0..300_000
}

enum class SyncFailure { NONE, TIMEOUT, NETWORK_OR_SAMPLE }
data class SyncState(val syncing: Boolean = false, val failed: Boolean = false, val lastSuccessElapsed: Long = -1, val rttMillis: Long = 0,
    val failure: SyncFailure = SyncFailure.NONE, val nextAttemptElapsed: Long = -1)
