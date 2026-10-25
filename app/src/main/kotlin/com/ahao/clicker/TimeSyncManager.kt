package com.ahao.clicker

import android.os.SystemClock
import com.ahao.clicker.core.SyncedTime
import com.ahao.clicker.core.TimeFormat
import com.ahao.clicker.core.TimeReference
import com.ahao.clicker.core.TimeSourceConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One bounded network request at a time; no retained Activity or per-sync thread creation. */
class TimeSyncManager {
    private val time = SyncedTime()
    private val worker = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var config: TimeSourceConfig? = null
    private var enabled = false
    private var generation = 0L
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var state = SyncState()
        private set

    fun reference(): TimeReference = time.snapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis())

    @Synchronized fun configure(value: TimeSourceConfig, periodic: Boolean) {
        if (value == config && periodic == enabled) return
        generation++
        if (config?.url != value.url || config?.format != value.format || config?.field != value.field) {
            time.reset()
            state = SyncState()
        }
        config = value
        enabled = periodic
        state = state.copy(syncing = false)
        scheduled?.cancel(true)
        connection?.disconnect()
        scheduled = null
        if (enabled) enqueue(generation, 0)
    }

    @Synchronized fun pause() {
        generation++
        enabled = false
        scheduled?.cancel(true)
        connection?.disconnect()
        state = state.copy(syncing = false)
    }

    @Synchronized fun syncNow(value: TimeSourceConfig, periodic: Boolean) {
        configure(value, periodic)
        generation++
        scheduled?.cancel(true)
        connection?.disconnect()
        enqueue(generation, 0)
    }

    @Synchronized private fun enqueue(token: Long, delay: Long) {
        scheduled = worker.schedule({ synchronize(token) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun synchronize(token: Long) {
        val source = synchronized(this) {
            if (token != generation) return
            state = state.copy(syncing = true, failed = false)
            config ?: return
        }
        var client: HttpURLConnection? = null
        try {
            val sent = SystemClock.elapsedRealtime()
            client = URL(source.url).openConnection() as HttpURLConnection
            synchronized(this) {
                if (token != generation) return
                connection = client
            }
            client.connectTimeout = 3_000
            client.readTimeout = 3_000
            client.instanceFollowRedirects = false
            client.useCaches = false
            client.setRequestProperty("Cache-Control", "no-cache, no-store")
            require(client.responseCode in 200..299)
            require((client.getHeaderField("Age")?.toLongOrNull() ?: 0) == 0L)
            val body = if (source.format == TimeFormat.HTTP_DATE) "" else {
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
            val epoch = TimeResponseParser.parse(source.format, source.field, client.getHeaderField("Date"), body)
            val received = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (token == generation) {
                    require(time.accept(epoch, sent, received))
                    state = SyncState(lastSuccessElapsed = received, rttMillis = received - sent)
                }
            }
        } catch (_: Exception) {
            synchronized(this) { if (token == generation) state = state.copy(syncing = false, failed = true) }
        } finally {
            client?.disconnect()
            synchronized(this) {
                if (connection === client) connection = null
                if (token == generation && enabled) enqueue(token, source.intervalMillis)
            }
        }
    }
}

data class SyncState(val syncing: Boolean = false, val failed: Boolean = false, val lastSuccessElapsed: Long = -1, val rttMillis: Long = 0)
