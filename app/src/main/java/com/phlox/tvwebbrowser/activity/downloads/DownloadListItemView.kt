package com.phlox.tvwebbrowser.activity.downloads

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.Download
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Created by PDT on 24.01.2017.
 */

class DownloadListItemView(private val downloadsActivity: DownloadsActivity, private val viewType: Int) : FrameLayout(downloadsActivity), ActiveDownloadsModel.Listener {
    private var defaultTextColor: Int = 0
    private var tvDate: TextView? = null
    private var tvTitle: TextView? = null
    private var tvURL: TextView? = null
    private var tvTime: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressBar2: ProgressBar? = null
    private var tvSize: TextView? = null
    private var tvStats: TextView? = null
    var download: Download? = null
    set(value) {
        field = value
        if (field == null) return
        when (viewType) {
            DownloadListAdapter.VIEW_TYPE_HEADER -> {
                val df = SimpleDateFormat.getDateInstance()
                tvDate!!.text = df.format(Date(field!!.time))
            }
            DownloadListAdapter.VIEW_TYPE_DOWNLOAD_ITEM -> {
                tvTitle!!.text = field!!.filename
                tvURL!!.text = field!!.url
                val sdf = SimpleDateFormat("HH:mm")
                tvTime!!.text = sdf.format(Date(field!!.time))
                updateUI(field!!)
            }
        }
    }

    init {
        LayoutInflater.from(downloadsActivity).inflate(
                if (viewType == DownloadListAdapter.VIEW_TYPE_HEADER)
                    R.layout.view_history_header_item
                else
                    R.layout.view_download_item, this)
        when (viewType) {
            DownloadListAdapter.VIEW_TYPE_HEADER -> tvDate = findViewById(R.id.tvDate)
            DownloadListAdapter.VIEW_TYPE_DOWNLOAD_ITEM -> {
                tvTitle = findViewById(R.id.tvTitle)
                tvURL = findViewById(R.id.tvURL)
                tvTime = findViewById(R.id.tvTime)
                tvSize = findViewById(R.id.tvSize)
                tvStats = findViewById(R.id.tvStats)
                defaultTextColor = tvSize!!.currentTextColor
                progressBar = findViewById(R.id.progressBar)
                progressBar2 = findViewById(R.id.progressBar2)
            }
        }
    }

    companion object {
        /** Speed measurement per download (survives the list being scrolled / rebuilt). */
        private class Meter(var bytes: Long, var time: Long, var speed: Double = 0.0, var startBytes: Long = bytes,
                            var startTime: Long = time, var doneAt: Long = 0L)
        private val meters = HashMap<Long, Meter>()
        @Volatile private var freeText = ""
        private var freeAt = 0L
        @Volatile private var freeBusy = false

        private fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format("%d:%02d", s / 60, s % 60)
        }
    }

    /** Free space of the download folder, measured on a background thread (a busy USB drive can take long). */
    private fun freeSpace(): String {
        val now = System.currentTimeMillis()
        if (now - freeAt >= 5000 && !freeBusy) {
            freeAt = now
            freeBusy = true
            val ctx = context.applicationContext
            Thread {
                val text = try {
                    val dir = com.phlox.tvwebbrowser.sofa.LumenDownloads.customFolderFile()
                        ?: @Suppress("DEPRECATION") android.os.Environment.getExternalStorageDirectory()
                    Formatter.formatShortFileSize(ctx, android.os.StatFs(dir.absolutePath).availableBytes) + " free"
                } catch (e: Exception) { "" }
                freeText = text
                freeBusy = false
            }.start()
        }
        return freeText
    }

    /** Everything a download manager shows: progress, speed, times, connections, place, free space. */
    private fun updateStats(download: Download) {
        val tv = tvStats ?: return
        val now = System.currentTimeMillis()
        val parts = ArrayList<String>()
        val active = download.size >= 0 && (download.size == 0L || download.bytesReceived < download.size) &&
                download.size != Download.CANCELLED_MARK && download.size != Download.BROKEN_MARK &&
                now - download.time < 7 * 24 * 3600_000L
        val m = meters.getOrPut(download.id) { Meter(download.bytesReceived, now) }
        if (download.bytesReceived != m.bytes) {
            val dt = (now - m.time).coerceAtLeast(1)
            val inst = (download.bytesReceived - m.bytes) * 1000.0 / dt
            m.speed = if (m.speed <= 0) inst else m.speed * 0.7 + inst * 0.3
            m.bytes = download.bytesReceived
            m.time = now
        }
        val isRunning = active && downloadsActivity.isDownloadActive(download)
        val saved = if (isRunning) null else com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(download.id)
        val live = downloadsActivity.liveInfo(download)
        if (live != null && live.queued) {
            parts.add("⏳ Queued: starts when a download finishes (Settings → Downloads at once)")
            parts.add("press OK to start it now")
        } else if (saved != null) {
            //paused, or stopped by an error / the app being closed: can continue
            val got = saved.received
            parts.add(if (saved.paused) "⏸ Paused" else "Interrupted")
            if (saved.size > 0) {
                parts.add("${got * 100 / saved.size}%")
                parts.add(Formatter.formatShortFileSize(context, got) + " of " + Formatter.formatShortFileSize(context, saved.size))
            } else {
                parts.add("not started yet")
            }
            val retry = com.phlox.tvwebbrowser.service.downloads.DownloadService.retryAt[download.id]
            if (retry != null && !saved.paused) parts.add("trying again in " + duration(maxOf(0, retry - now)))
            parts.add("press OK to resume")
        } else if (isRunning) {
            if (download.size > 0) parts.add("${download.bytesReceived * 100 / download.size}%")
            parts.add(Formatter.formatShortFileSize(context, download.bytesReceived) +
                    (if (download.size > 0) " of " + Formatter.formatShortFileSize(context, download.size) else ""))
            if (download.paused) parts.add("pausing…")
            if (download.cancelled) parts.add("stopping…")
            com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity.phaseOf(downloadsActivity.liveInfo(download))?.let { parts.add(it) }
            if (m.speed > 0) parts.add(Formatter.formatShortFileSize(context, m.speed.toLong()) + "/s")
            parts.add(duration(now - download.time) + " elapsed")
            if (download.size > 0 && m.speed > 1) {
                parts.add(duration(((download.size - download.bytesReceived) / m.speed * 1000).toLong()) + " left")
            }
            when {
                live == null -> {}
                live.connections > 1 -> parts.add("${live.connections} connections")
                live.connections == 1 && download.size >= 8L * 1024 * 1024 ->
                    parts.add(if (live.canResume) "1 connection" else "1 connection (server doesn't allow more)")
            }
            freeSpace().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        } else if (download.size > 0 && download.bytesReceived >= download.size) {
            if (m.doneAt == 0L) m.doneAt = now
            parts.add("Done · " + Formatter.formatShortFileSize(context, download.size))
            //average speed only for downloads watched in this session
            if (m.startBytes < download.size && m.doneAt > m.startTime) {
                val took = m.doneAt - m.startTime
                parts.add("took " + duration(took))
                parts.add("avg " + Formatter.formatShortFileSize(context, ((download.size - m.startBytes) * 1000 / took.coerceAtLeast(1))) + "/s")
            }
            parts.add(placeOf(download))
        } else if (download.size == Download.BROKEN_MARK) {
            parts.add("Failed after " + Formatter.formatShortFileSize(context, download.bytesReceived))
        } else if (download.size == Download.CANCELLED_MARK) {
            parts.add("Cancelled")
        } else if (download.size == 0L && !isRunning) {
            parts.add(Formatter.formatShortFileSize(context, download.bytesReceived) + " · stopped")
        }
        tv.text = parts.joinToString("  ·  ")
        tv.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun placeOf(download: Download): String {
        val p = download.filepath
        return when {
            p.startsWith("content://media") -> "in Download/${com.phlox.tvwebbrowser.Brand.NAME}"
            p.startsWith("content://") -> "in the chosen folder"
            p.startsWith("/") -> "in " + com.phlox.tvwebbrowser.sofa.LumenDownloads.pathLabel(java.io.File(p).parent ?: p)
            else -> ""
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI(download: Download) {
        updateStats(download)
        if (tvTitle?.text != download.filename) {
            tvTitle?.text = download.filename
        }
        this.download?.size = download.size
        this.download?.bytesReceived = download.bytesReceived
        tvSize!!.setTextColor(defaultTextColor)
        val saved = if (downloadsActivity.isDownloadActive(download)) null
            else com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(download.id)
        if (saved != null && saved.size > 0) {
            val got = saved.received
            tvSize!!.text = (if (saved.paused) "Paused\n" else "Interrupted\n") + Formatter.formatShortFileSize(context, got)
            progressBar!!.visibility = View.VISIBLE
            progressBar2!!.visibility = View.GONE
            progressBar!!.progress = (got * 100 / saved.size).toInt()
        } else if (download.size == Download.CANCELLED_MARK) {
            tvSize!!.setText(R.string.cancelled)
            progressBar!!.visibility = View.INVISIBLE
            progressBar2!!.visibility = View.GONE
        } else if (download.size == Download.BROKEN_MARK) {
            tvSize!!.setText(R.string.error)
            tvSize!!.setTextColor(Color.RED)
            progressBar!!.visibility = View.INVISIBLE
            progressBar2!!.visibility = View.GONE
        } else if (download.size == 0L) {
            tvSize!!.text = Formatter.formatShortFileSize(context, download.bytesReceived)
            progressBar!!.visibility = View.INVISIBLE
            progressBar2!!.visibility = View.VISIBLE
        } else if (download.size > 0) {
            if (download.size == download.bytesReceived) {
                tvSize!!.text = Formatter.formatShortFileSize(context, download.size)
                progressBar!!.visibility = View.INVISIBLE
                progressBar2!!.visibility = View.GONE
            } else {
                tvSize!!.text = Formatter.formatShortFileSize(context, download.bytesReceived) + "/\n" +
                        Formatter.formatShortFileSize(context, download.size)
                progressBar!!.visibility = View.VISIBLE
                progressBar2!!.visibility = View.GONE
                if (download.bytesReceived > download.size) {
                    download.size = 0L//wrong value from server - ignore it for future updates
                } else {
                    progressBar!!.progress = (download.bytesReceived * 100 / download.size).toInt()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        downloadsActivity.registerListener(this)
    }

    override fun onDetachedFromWindow() {
        downloadsActivity.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onDownloadUpdated(downloadInfo: Download) {
        if (downloadInfo.id == this.download?.id) {
            updateUI(downloadInfo)
        }
    }

    override fun onDownloadError(downloadInfo: Download, responseCode: Int, responseMessage: String) {
        if (downloadInfo.id == this.download?.id) {
            updateUI(downloadInfo)
        }
    }

    override fun onAllDownloadsComplete() {}
}
