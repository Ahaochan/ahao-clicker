package com.ahao.clicker.time

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** API 29+ uses cancellable platform DNS; older systems share one bounded fallback worker. */
class AddressLookup : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val cancellation = CancellationSignal()
    @Volatile private var answer: InetAddress? = null
    @Volatile private var future: Future<*>? = null
    @Volatile private var closed = false

    fun resolve(host: String): InetAddress {
        check(!closed)
        if (Build.VERSION.SDK_INT >= 29) query(host) else {
            future = legacy.submit {
                try { if (!closed) answer = InetAddress.getByName(host) }
                finally { ready.countDown() }
            }
        }
        if (!ready.await(2_000, TimeUnit.MILLISECONDS)) throw SocketTimeoutException("DNS timeout")
        return answer ?: throw UnknownHostException(host)
    }

    @androidx.annotation.RequiresApi(29)
    private fun query(host: String) {
        DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, { it.run() }, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(addresses: List<InetAddress>, rcode: Int) {
                    if (!closed && rcode == 0) answer = addresses.firstOrNull()
                    ready.countDown()
                }
                override fun onError(error: DnsResolver.DnsException) { ready.countDown() }
            })
    }
    override fun close() {
        closed = true
        cancellation.cancel()
        future?.let { it.cancel(true); legacy.purge() }
        ready.countDown()
    }
    companion object {
        private val legacy by lazy {
            ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
        }
    }
}
