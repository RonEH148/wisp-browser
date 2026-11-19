package com.phlox.tvwebbrowser.service.downloads

import com.phlox.tvwebbrowser.AppContext

/**
 * Lumen: the download speed limit (Settings → Downloads), shared by all downloads and their
 * connections. Each connection reports what it read; when the total gets ahead of the limit, it
 * waits as long as needed to fall back to it.
 */
object DownloadRate {
    private var tokens = 0.0
    private var last = System.nanoTime()

    /** Bytes per second, 0 = no limit. */
    fun limit(): Long = AppContext.provideConfig().lumenSpeedLimitKBps.toLong() * 1024

    fun acquire(bytes: Int) {
        val rate = limit()
        if (rate <= 0 || bytes <= 0) return
        val sleepMs: Long
        synchronized(this) {
            val now = System.nanoTime()
            //at most one second of unused allowance is kept
            tokens = minOf(rate.toDouble(), tokens + (now - last) / 1e9 * rate)
            last = now
            tokens -= bytes
            sleepMs = if (tokens < 0) (-tokens / rate * 1000).toLong() else 0L
        }
        if (sleepMs > 0) Thread.sleep(sleepMs.coerceAtMost(3000))
    }
}
