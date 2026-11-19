package com.phlox.tvwebbrowser.service.downloads

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.os.ParcelFileDescriptor
import android.webkit.CookieManager
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.sofa.LumenDownloads
import com.phlox.tvwebbrowser.sofa.LumenDownloadState
import android.os.StatFs
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.DownloadUtils
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Created by PDT on 23.01.2017.
 */

const val MAX_CONNECT_RETRIES = 5

interface DownloadTask {
    var downloadInfo: Download

    interface Callback {
        fun onProgress(task: DownloadTask)
        fun onError(task: DownloadTask, responseCode: Int, responseMessage: String)
        fun onDone(task: DownloadTask)
    }
}

class FileDownloadTask(override var downloadInfo: Download, private val userAgent: String?, val callback: DownloadTask.Callback,
                       /** Lumen: continue a paused or interrupted download instead of starting a new one. */
                       private val resumeFrom: LumenDownloadState.State? = null) : Runnable, DownloadTask {
    companion object {
        val TAG = FileDownloadTask::class.java.simpleName
        /** Files smaller than this are downloaded with one connection. */
        const val MIN_SIZE_FOR_PARTS = 2L * 1024 * 1024
        /** A connection that finished takes over half of the biggest remaining part, if it is at least this big. */
        private const val MIN_SPLIT = 1L * 1024 * 1024
        private const val FAT32_LIMIT = 4L * 1024 * 1024 * 1024 - 1
        private const val BUFFER = 256 * 1024
        private const val STATE_SAVE_MS = 4000L
        /** USB drives: piece size and how many finished pieces may wait for their turn (+ connections). */
        private const val ORDERED_CHUNK = 2 * 1024 * 1024
        private const val ORDERED_EXTRA = 4
    }

    //servers often reject Java's default "Dalvik/..." user agent
    private val effectiveUserAgent: String by lazy {
        userAgent?.takeIf { it.isNotBlank() } ?: try {
            android.webkit.WebSettings.getDefaultUserAgent(AppContext.get())
        } catch (e: Exception) {
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        }
    }

    private fun openConnection(url: URL, range: String? = null): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.readTimeout = 30000
        connection.connectTimeout = 20000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", effectiveUserAgent)
        if (downloadInfo.mimeType != null && downloadInfo.mimeType != "application/octet-stream") {
            connection.setRequestProperty("Accept", downloadInfo.mimeType)
        }
        downloadInfo.referer?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
        connection.useCaches = false
        //downloads from private mode bring that session's cookies along (for their own site only)
        val passed = downloadInfo.cookies?.takeIf {
            try { java.net.URL(downloadInfo.url).host.equals(url.host, ignoreCase = true) } catch (e: Exception) { false }
        }
        //private-mode downloads never touch WebView here (this process must not start WebView
        //while private mode holds it, and the main cookie jar isn't the private session's)
        val cookie = if (downloadInfo.fromPrivateMode) passed
            else passed ?: CookieManager.getInstance().getCookie(url.toString())
        if (cookie != null) connection.setRequestProperty("cookie", cookie)
        if (range != null) {
            connection.setRequestProperty("Range", range)
            //byte ranges must refer to the raw file, not a compressed transfer
            connection.setRequestProperty("Accept-Encoding", "identity")
        }
        connection.connect()
        return connection
    }

    /** Message for the downloads list, with hints for typical USB-drive problems. */
    private fun storageError(e: Exception, written: Long): String {
        val target = if (LumenDownloads.customFolder() != null) "the chosen folder" else "internal storage"
        return when {
            written >= FAT32_LIMIT - BUFFER -> "Stopped at 4 GB: the USB drive is probably formatted as FAT32, " +
                    "which can't hold files over 4 GB. Format it as exFAT or NTFS, or save to internal storage."
            e.message?.contains("ENOSPC", true) == true || e.message?.contains("No space", true) == true ->
                "Not enough free space in $target."
            e.message?.contains("EIO", true) == true || e.message?.contains("ENODEV", true) == true ||
                    e.message?.contains("ENOENT", true) == true -> "The storage was removed or became unavailable."
            else -> e.toString()
        }
    }

    /** Connections per download; with a speed limit only as many as it can use (~512 KB/s each). */
    private fun connections(): Int {
        val set = AppContext.provideConfig().lumenDownloadParts.coerceIn(1, 16)
        val limit = DownloadRate.limit()
        return if (limit > 0) minOf(set, maxOf(1, (limit / (512 * 1024)).toInt())) else set
    }

    private fun saveState(missing: List<LongArray>, written: Long, temp: String?, paused: Boolean) {
        if (downloadInfo.id == 0L || downloadInfo.size <= 0) return
        LumenDownloadState.save(downloadInfo.id, LumenDownloadState.State(downloadInfo.url, downloadInfo.referer,
            userAgent, downloadInfo.mimeType, downloadInfo.size, missing, written, temp, paused))
    }

    /** Paused: the file stays as it is, the database gets the progress, nothing is published. */
    private var pausedNow = false

    override fun run() {
        if (resumeFrom == null) {
            //queued downloads are in the database already
            if (downloadInfo.id == 0L) downloadInfo.id = AppDatabase.db.downloadDao().insert(downloadInfo)
        } else {
            downloadInfo.size = resumeFrom.size
            downloadInfo.bytesReceived = resumeFrom.received
            downloadInfo.mimeType = downloadInfo.mimeType ?: resumeFrom.mimeType
            downloadInfo.referer = downloadInfo.referer ?: resumeFrom.referer
        }

        var fd: ParcelFileDescriptor? = null
        var url = URL(downloadInfo.url)
        var connection: HttpURLConnection? = null
        var written = 0L
        var resumable = false
        try {
            //---- continuing a paused / interrupted download
            if (resumeFrom != null) {
                fd = LumenDownloads.reopenOutput(downloadInfo)
                downloadInfo.canResume = true
                resumable = true
                if (resumeFrom.missing.isNotEmpty()) {
                    when (downloadInParts(url, fd, downloadInfo.size, resumeFrom.missing, resumeFrom.tempFile)) {
                        PartsResult.DONE -> {
                            downloadInfo.bytesReceived = downloadInfo.size
                            LumenDownloadState.remove(downloadInfo.id)
                            return
                        }
                        PartsResult.PAUSED -> { pausedNow = true; return }
                        PartsResult.CANCELLED -> { cancelled(); return }
                        PartsResult.UNSUPPORTED -> throw IOException("The server no longer lets this download continue. Download it again.")
                    }
                }
                written = resumeFrom.written
                if (LumenDownloads.targetIsRemovable(downloadInfo) && AppContext.provideConfig().lumenDownloadParts > 1 &&
                    written < downloadInfo.size) {
                    when (downloadOrdered(url, fd, downloadInfo.size, written)) {
                        PartsResult.DONE -> {
                            downloadInfo.bytesReceived = downloadInfo.size
                            LumenDownloadState.remove(downloadInfo.id)
                            return
                        }
                        PartsResult.PAUSED -> { pausedNow = true; return }
                        PartsResult.CANCELLED -> { cancelled(); return }
                        PartsResult.UNSUPPORTED -> {} //one connection below
                    }
                }
                connection = openConnection(url, "bytes=$written-")
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> FileOutputStream(fd.fileDescriptor).channel.position(written)
                    HttpURLConnection.HTTP_OK -> {
                        //the server starts from the beginning again
                        written = 0
                        FileOutputStream(fd.fileDescriptor).channel.truncate(0)
                    }
                    else -> {
                        downloadInfo.size = Download.BROKEN_MARK
                        callback.onError(this, connection.responseCode, connection.responseMessage)
                        return
                    }
                }
            } else {
                //---- a new download
                var retries = 0
                do {
                    if (retries > 0) Thread.sleep(3000)
                    connection = openConnection(url)
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> break
                        HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
                        HttpURLConnection.HTTP_UNAVAILABLE -> {
                            retries++
                            connection.disconnect()
                            if (retries >= MAX_CONNECT_RETRIES) {
                                downloadInfo.size = Download.BROKEN_MARK
                                callback.onError(this, connection.responseCode, connection.responseMessage)
                                return
                            }
                        }
                        else -> {
                            downloadInfo.size = Download.BROKEN_MARK
                            callback.onError(this, connection.responseCode, connection.responseMessage)
                            return
                        }
                    }
                } while (true)

                //file hosts often redirect to a signed address: every part uses that one
                url = connection!!.url ?: url
                val fileLength = connection.contentLengthLong
                downloadInfo.size = fileLength

                if (connection.headerFields.containsKey("Content-Disposition")) {
                    val mime = connection.getHeaderField("Content-Type")
                    downloadInfo.filename = DownloadUtils.guessFileName(downloadInfo.url, connection.getHeaderField("Content-Disposition"), mime)
                    if (!LumenDownloads.isContentPath(downloadInfo.filepath) && downloadInfo.filepath.isNotEmpty()) {
                        downloadInfo.filepath = File(File(downloadInfo.filepath).parentFile, downloadInfo.filename).absolutePath
                    }
                }
                if (downloadInfo.mimeType.isNullOrBlank()) {
                    downloadInfo.mimeType = connection.getHeaderField("Content-Type")?.substringBefore(';')?.trim()
                }
                //no name from the server and the address has no usable extension ("downloadfile.bin",
                //images from addresses like ".../media?id=5"): take the extension from the file's type
                val served = connection.getHeaderField("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (!connection.headerFields.containsKey("Content-Disposition") && !served.isNullOrBlank() &&
                    served != "application/octet-stream") {
                    val mimeMap = android.webkit.MimeTypeMap.getSingleton()
                    val ext = mimeMap.getExtensionFromMimeType(served)
                    val name = downloadInfo.filename
                    val curExt = name.substringAfterLast('.', "").lowercase()
                    val curType = if (curExt.isEmpty()) null else mimeMap.getMimeTypeFromExtension(curExt)
                    if (ext != null && (curExt.isEmpty() || curExt == "bin" || (curType != null && curType != served))) {
                        downloadInfo.filename = (if (curExt.isEmpty()) name else name.substringBeforeLast('.')) + "." + ext
                        downloadInfo.mimeType = served
                        if (!LumenDownloads.isContentPath(downloadInfo.filepath) && downloadInfo.filepath.isNotEmpty()) {
                            downloadInfo.filepath = File(File(downloadInfo.filepath).parentFile, downloadInfo.filename).absolutePath
                        }
                    }
                }

                fd = LumenDownloads.openOutput(downloadInfo)
                AppDatabase.db.downloadDao().update(downloadInfo)
                Log.d(TAG, "URI: " + downloadInfo.filename)

                val acceptsRanges = connection.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true
                val compressed = connection.getHeaderField("Content-Encoding")?.let { !it.equals("identity", true) } == true
                resumable = acceptsRanges && !compressed && fileLength > 0
                downloadInfo.canResume = resumable

                //Lumen: like a download manager, fetch big files over several connections at once
                val parts = AppContext.provideConfig().lumenDownloadParts
                if (parts > 1 && resumable && fileLength >= MIN_SIZE_FOR_PARTS) {
                    connection.disconnect()
                    connection = null
                    val chunk = fileLength / parts
                    val segments = (0 until parts).map { i ->
                        longArrayOf(i * chunk, if (i == parts - 1) fileLength - 1 else (i + 1) * chunk - 1)
                    }
                    val result = if (LumenDownloads.targetIsRemovable(downloadInfo)) downloadOrdered(url, fd, fileLength, 0)
                        else downloadInParts(url, fd, fileLength, segments, null)
                    when (result) {
                        PartsResult.DONE -> {
                            downloadInfo.size = fileLength
                            downloadInfo.bytesReceived = fileLength
                            LumenDownloadState.remove(downloadInfo.id)
                            return //finally{} publishes the file
                        }
                        PartsResult.PAUSED -> { pausedNow = true; return }
                        PartsResult.CANCELLED -> { cancelled(); return }
                        PartsResult.UNSUPPORTED -> {
                            Log.w(TAG, "Multi-part download not possible, using one connection")
                            try {
                                FileOutputStream(fd.fileDescriptor).channel.truncate(0)
                            } catch (ignored: Exception) {
                            }
                            connection = openConnection(url)
                            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                                downloadInfo.size = Download.BROKEN_MARK
                                callback.onError(this, connection.responseCode, connection.responseMessage)
                                return
                            }
                        }
                    }
                }
            }

            //---- single connection, resuming from the last byte after network drops when possible
            val fileLength = downloadInfo.size
            val output = FileOutputStream(fd!!.fileDescriptor)
            val data = ByteArray(BUFFER)
            var drops = 0
            var lastSave = System.currentTimeMillis()
            var input: InputStream = connection!!.inputStream
            downloadInfo.connections = 1
            while (true) {
                if (downloadInfo.cancelled) { cancelled(); return }
                if (downloadInfo.paused) {
                    output.flush()
                    //without byte ranges the download starts over when resumed
                    saveState(emptyList(), if (resumable) written else 0L, null, true)
                    downloadInfo.bytesReceived = written
                    pausedNow = true
                    return
                }
                val count = try {
                    input.read(data)
                } catch (e: IOException) {
                    if (!resumable || drops >= 20) throw e
                    drops++
                    Log.w(TAG, "Connection dropped at $written bytes, resuming ($drops)")
                    try { input.close() } catch (ignored: IOException) {}
                    connection?.disconnect()
                    Thread.sleep(1500L * drops.coerceAtMost(5))
                    connection = openConnection(url, "bytes=$written-")
                    if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) throw e
                    input = connection.inputStream
                    continue
                }
                if (count == -1) break
                DownloadRate.acquire(count)
                try {
                    output.write(data, 0, count)
                } catch (e: IOException) {
                    throw IOException(storageError(e, written), e)
                }
                written += count
                downloadInfo.bytesReceived = written
                callback.onProgress(this)
                val now = System.currentTimeMillis()
                if (resumable && now - lastSave > STATE_SAVE_MS) {
                    lastSave = now
                    saveState(emptyList(), written, null, false) //continues after the app was closed
                }
            }
            try { input.close() } catch (ignored: IOException) {}
            output.flush()
            if (fileLength > 0 && written != fileLength) {
                throw IOException("Download incomplete ($written of $fileLength bytes)")
            }
            downloadInfo.size = downloadInfo.bytesReceived
            LumenDownloadState.remove(downloadInfo.id)
        } catch (e: Exception) {
            e.printStackTrace()
            //keep what is there, so "Resume" can continue it
            if (resumable && written > 0 && LumenDownloadState.get(downloadInfo.id)?.missing?.isNotEmpty() != true) {
                saveState(emptyList(), written, null, false)
            }
            downloadInfo.bytesReceived = maxOf(downloadInfo.bytesReceived, written)
            downloadInfo.size = Download.BROKEN_MARK
            callback.onError(this, 0, e.message ?: e.toString())
            return
        } finally {
            downloadInfo.connections = 0
            try {
                fd?.close()
            } catch (ignored: IOException) {
            }
            connection?.disconnect()
            if (!pausedNow) {
                cancelDownloadIfNeeded(downloadInfo)
                if (downloadInfo.cancelled) LumenDownloadState.remove(downloadInfo.id)
            }
            if (downloadInfo.size != Download.BROKEN_MARK) {
                callback.onDone(this)
            }
        }
    }

    private fun cancelled() {
        downloadInfo.bytesReceived = 0
        downloadInfo.size = Download.CANCELLED_MARK
    }

    private enum class PartsResult { DONE, PAUSED, CANCELLED, UNSUPPORTED }

    /**
     * USB drives / SD cards: several connections, written strictly in order. Every connection
     * fetches the next small piece of the file (ORDERED_CHUNK); finished pieces wait in memory
     * until it's their turn and are appended to the file. Nothing is ever written far ahead (no
     * zero-filling on FAT32/exFAT), no temporary copy on internal storage, and the memory used is
     * capped (at most ORDERED_AHEAD pieces, reused). [from] = bytes already in the file.
     */
    private fun downloadOrdered(url: URL, fd: ParcelFileDescriptor, length: Long, from: Long): PartsResult {
        val channel = FileOutputStream(fd.fileDescriptor).channel
        val conns = connections()
        val chunk = ORDERED_CHUNK
        val total = (length - from + chunk - 1) / chunk
        val maxAhead = conns + ORDERED_EXTRA
        val lock = Object()
        val done = HashMap<Long, ByteArray>()
        val doneLen = HashMap<Long, Int>()
        val redo = ArrayDeque<Long>()
        val pool = ArrayDeque<ByteArray>()
        var nextTake = 0L
        var writtenIdx = 0L
        var written = from
        val inFlight = AtomicLong(0)
        val unsupported = AtomicBoolean(false)
        val anyPartial = AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val active = AtomicLong(0)
        val stop = { downloadInfo.cancelled || downloadInfo.paused || failure.get() != null || unsupported.get() }

        fun take(): Long? = synchronized(lock) {
            while (!stop()) {
                if (redo.isNotEmpty()) return redo.removeFirst()
                if (nextTake >= total) return null
                if (nextTake < writtenIdx + maxAhead) return nextTake++
                (lock as Object).wait(250)
            }
            null
        }
        fun buffer(): ByteArray = synchronized(lock) { if (pool.isNotEmpty()) pool.removeFirst() else ByteArray(chunk) }

        val workers = (0 until conns).map { index ->
            Thread {
                var refused = 0
                val tries = HashMap<Long, Int>()
                while (!stop()) {
                    val i = take() ?: break
                    val start = from + i * chunk
                    val end = minOf(length, start + chunk) - 1
                    val want = (end - start + 1).toInt()
                    val buf = buffer()
                    var got = 0
                    var c: HttpURLConnection? = null
                    var ok = false
                    try {
                        c = openConnection(url, "bytes=$start-$end")
                        val code = c.responseCode
                        if (code == HttpURLConnection.HTTP_OK && !anyPartial.get()) {
                            unsupported.set(true) //the server ignores ranges: the whole file comes
                        } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                            //too many connections for this server (429, 503, 403…): the others go on
                            refused++
                        } else {
                            anyPartial.set(true)
                            refused = 0
                            active.incrementAndGet()
                            try {
                                c.inputStream.use { inp ->
                                    while (got < want && !stop()) {
                                        val n = inp.read(buf, got, want - got)
                                        if (n == -1) break
                                        got += n
                                        inFlight.addAndGet(n.toLong())
                                        DownloadRate.acquire(n)
                                    }
                                }
                            } finally {
                                active.decrementAndGet()
                            }
                            ok = got == want
                            if (!ok && !stop()) {
                                //the connection ended early: try this piece again (not forever)
                                val t = (tries[i] ?: 0) + 1
                                tries[i] = t
                                if (t > 10) failure.compareAndSet(null, IOException("The server keeps ending the connection early"))
                            }
                        }
                    } catch (e: Exception) {
                        val t = (tries[i] ?: 0) + 1
                        tries[i] = t
                        if (t > 10) failure.compareAndSet(null, e) else Thread.sleep(800L * t.coerceAtMost(5))
                    } finally {
                        c?.disconnect()
                    }
                    synchronized(lock) {
                        if (ok) {
                            done[i] = buf
                            doneLen[i] = got
                        } else {
                            inFlight.addAndGet(-got.toLong())
                            pool.addLast(buf)
                            redo.addFirst(i)
                        }
                        (lock as Object).notifyAll()
                    }
                    if (!ok && refused > 0) {
                        if (refused >= 3) break
                        Thread.sleep(1500L * refused)
                    }
                }
            }.also { it.name = "lumen-dl-o$index"; it.start() }
        }

        //this thread: append the pieces in order
        var lastSave = 0L
        var lastReport = 0L
        try {
            while (writtenIdx < total) {
                var buf: ByteArray? = null
                var len = 0
                synchronized(lock) {
                    while (!done.containsKey(writtenIdx)) {
                        if (downloadInfo.cancelled || failure.get() != null || unsupported.get()) break
                        //paused: everything finished so far is still written
                        if (downloadInfo.paused && workers.none { it.isAlive }) break
                        if (workers.none { it.isAlive }) break
                        (lock as Object).wait(250)
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 300) {
                            lastReport = now
                            downloadInfo.bytesReceived = from + inFlight.get()
                            downloadInfo.connections = active.get().toInt()
                            callback.onProgress(this)
                        }
                    }
                    buf = done.remove(writtenIdx)
                    len = doneLen.remove(writtenIdx) ?: 0
                }
                val b = buf ?: break
                val bb = ByteBuffer.wrap(b, 0, len)
                var pos = written
                try {
                    while (bb.hasRemaining()) pos += channel.write(bb, pos)
                } catch (e: IOException) {
                    failure.compareAndSet(null, IOException(storageError(e, written), e))
                    break
                }
                synchronized(lock) {
                    written += len
                    writtenIdx++
                    pool.addLast(b)
                    (lock as Object).notifyAll()
                }
                val now = System.currentTimeMillis()
                if (now - lastReport > 300) {
                    lastReport = now
                    downloadInfo.bytesReceived = from + inFlight.get()
                    downloadInfo.connections = active.get().toInt()
                    callback.onProgress(this)
                }
                if (now - lastSave > STATE_SAVE_MS) {
                    lastSave = now
                    saveState(emptyList(), written, null, false) //continues after the app was closed
                }
            }
        } finally {
            synchronized(lock) { (lock as Object).notifyAll() }
            if (writtenIdx < total && !stop()) failure.compareAndSet(null, IOException("Download interrupted"))
            workers.forEach { it.join(5000) }
            downloadInfo.connections = 0
        }
        downloadInfo.bytesReceived = written
        callback.onProgress(this)
        if (downloadInfo.cancelled) return PartsResult.CANCELLED
        if (unsupported.get() && written == from) return PartsResult.UNSUPPORTED
        if (downloadInfo.paused) {
            saveState(emptyList(), written, null, true)
            return PartsResult.PAUSED
        }
        if (written < length) {
            saveState(emptyList(), written, null, false) //for "Resume"
            if (!anyPartial.get() && written == from) return PartsResult.UNSUPPORTED
            throw failure.get() ?: IOException("Download interrupted ($written of $length bytes)")
        }
        return PartsResult.DONE
    }

    /** One byte range being downloaded; [end] shrinks when another connection takes over half of it. */
    private class Segment(@Volatile var pos: Long, @Volatile var end: Long) {
        @Volatile var busy = false
        val remaining: Long get() = end - pos + 1
    }

    /** Can this file be written at any position? Some USB/SD providers only allow writing in order. */
    private fun supportsRandomAccess(channel: FileChannel, length: Long): Boolean = try {
        channel.write(ByteBuffer.wrap(byteArrayOf(0)), length - 1)
        channel.position(0)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Target does not support random access: $e")
        false
    }

    /**
     * Downloads the missing byte ranges of a [length]-byte file over several connections at once, like
     * a download manager. A connection that finishes its part takes over half of the biggest part still
     * running, so all connections stay busy until the end (one slow part doesn't hold up the download).
     * Parts are written straight to the target file when it allows random access; otherwise (some USB
     * drives) they go to a temporary file on internal storage that is copied over at the end.
     */
    private fun downloadInParts(url: URL, fd: ParcelFileDescriptor, length: Long, missing: List<LongArray>,
                                resumeTemp: String?): PartsResult {
        val targetChannel = FileOutputStream(fd.fileDescriptor).channel
        var tempFile: File? = null
        var tempRaf: RandomAccessFile? = null
        //USB drives and SD cards: never write the parts straight into the file. Writing at a far
        //position makes FAT32/exFAT fill everything before it with zeros first - minutes for a big
        //video, with the drive (and everything that touches it) stuck meanwhile. The parts go to a
        //file on internal storage and are copied over in order; without room for that: one connection.
        val removable = LumenDownloads.targetIsRemovable(downloadInfo)
        //paused by an older build with the parts written straight into the drive's file
        if (removable && resumeTemp == null && missing.sumOf { it[1] - it[0] + 1 } < length) return PartsResult.UNSUPPORTED
        val channel: FileChannel = if (resumeTemp == null && !removable && supportsRandomAccess(targetChannel, length)) {
            targetChannel
        } else {
            val dir = AppContext.get().getExternalFilesDir(null) ?: AppContext.get().filesDir
            val existing = resumeTemp?.let { File(it) }?.takeIf { it.exists() }
            if (existing == null) {
                if (resumeTemp != null) return PartsResult.UNSUPPORTED //the collected parts are gone
                if (StatFs(dir.absolutePath).availableBytes < length + 64L * 1024 * 1024) {
                    return PartsResult.UNSUPPORTED //not enough room for a temporary copy: one connection
                }
            }
            tempFile = existing ?: File(dir, "lumen_part_${downloadInfo.id}.tmp")
            tempRaf = RandomAccessFile(tempFile, "rw")
            if (tempRaf.length() != length) tempRaf.setLength(length)
            tempRaf.channel
        }
        var keepTemp = false
        try {
            val lock = Object()
            val segments = ArrayList<Segment>()
            for (m in missing) if (m[1] >= m[0]) segments.add(Segment(m[0], m[1]))
            val missingNow = { synchronized(lock) { segments.filter { it.pos <= it.end }.map { longArrayOf(it.pos, it.end) } } }
            val receivedNow = { length - synchronized(lock) { segments.sumOf { maxOf(0L, it.remaining) } } }
            val unsupported = AtomicBoolean(false)
            val anyPartial = AtomicBoolean(false)
            val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
            val active = AtomicLong(0)
            val startReceived = receivedNow()
            val stop = { downloadInfo.cancelled || downloadInfo.paused || failure.get() != null || unsupported.get() }

            //next piece of work: a part nobody downloads, else half of the biggest running part
            fun take(): Segment? = synchronized(lock) {
                segments.firstOrNull { !it.busy && it.pos <= it.end }?.let { it.busy = true; return it }
                val big = segments.filter { it.busy && it.remaining >= 2 * MIN_SPLIT }.maxByOrNull { it.remaining } ?: return null
                val mid = big.pos + big.remaining / 2
                val seg = Segment(mid, big.end)
                big.end = mid - 1
                seg.busy = true
                segments.add(seg)
                seg
            }

            val workers = (0 until connections()).map { index ->
                Thread {
                    val buffer = ByteArray(BUFFER)
                    var refused = 0
                    var errors = 0
                    var seg: Segment? = null
                    while (!stop()) {
                        if (seg == null || seg.pos > seg.end) {
                            seg?.busy = false
                            seg = take() ?: break
                        }
                        var c: HttpURLConnection? = null
                        try {
                            c = openConnection(url, "bytes=${seg.pos}-${seg.end}")
                            val code = c.responseCode
                            if (code == HttpURLConnection.HTTP_OK && !anyPartial.get()) {
                                unsupported.set(true) //the server ignores range requests: the whole file comes
                                seg.busy = false
                                seg = null
                                break
                            }
                            if (code != HttpURLConnection.HTTP_PARTIAL) {
                                //the server allows only a few connections at once (429, 503, 403…):
                                //this connection gives up and the others take over its part
                                refused++
                                seg.busy = false
                                seg = null
                                if (refused >= 3) break
                                Thread.sleep(2000L * refused)
                                continue
                            }
                            anyPartial.set(true)
                            refused = 0
                            active.incrementAndGet()
                            try {
                                c.inputStream.use { inp ->
                                    while (seg!!.pos <= seg!!.end && !stop()) {
                                        val n = inp.read(buffer)
                                        if (n == -1) break
                                        DownloadRate.acquire(n)
                                        //another connection may have taken over the rest of this part
                                        val len = minOf(n.toLong(), seg!!.end - seg!!.pos + 1).toInt()
                                        if (len <= 0) break
                                        val bb = ByteBuffer.wrap(buffer, 0, len)
                                        var writePos = seg!!.pos
                                        try {
                                            while (bb.hasRemaining()) writePos += channel.write(bb, writePos)
                                        } catch (e: IOException) {
                                            failure.compareAndSet(null, IOException(storageError(e, seg!!.pos), e))
                                            break
                                        }
                                        seg!!.pos += len
                                        errors = 0
                                    }
                                }
                            } finally {
                                active.decrementAndGet()
                            }
                        } catch (e: Exception) {
                            errors++
                            if (errors > 10) {
                                failure.compareAndSet(null, e)
                            } else {
                                Thread.sleep(1000L * errors.coerceAtMost(5)) //continue this part where it stopped
                            }
                        } finally {
                            c?.disconnect()
                        }
                    }
                    seg?.busy = false
                }.also { it.name = "lumen-dl-$index"; it.start() }
            }
            var lastSave = 0L
            while (workers.any { it.isAlive }) {
                downloadInfo.bytesReceived = receivedNow()
                downloadInfo.connections = active.get().toInt()
                callback.onProgress(this)
                val now = System.currentTimeMillis()
                if (now - lastSave > STATE_SAVE_MS) {
                    lastSave = now
                    keepTemp = tempFile != null
                    saveState(missingNow(), 0, tempFile?.absolutePath, false) //continues after the app was closed
                }
                Thread.sleep(300)
            }
            workers.forEach { it.join() }
            downloadInfo.connections = 0
            downloadInfo.bytesReceived = receivedNow()
            callback.onProgress(this)
            if (downloadInfo.cancelled) {
                keepTemp = false
                return PartsResult.CANCELLED
            }
            val left = missingNow()
            if (downloadInfo.paused) {
                saveState(left, 0, tempFile?.absolutePath, true)
                keepTemp = tempFile != null
                return PartsResult.PAUSED
            }
            if ((unsupported.get() || !anyPartial.get()) && downloadInfo.bytesReceived == startReceived) {
                keepTemp = false
                return PartsResult.UNSUPPORTED
            }
            if (left.isNotEmpty()) {
                //an error (or the server refused every connection): keep the progress for "Resume"
                saveState(left, 0, tempFile?.absolutePath, false)
                keepTemp = tempFile != null
                throw failure.get() ?: IOException("Download interrupted (" + downloadInfo.bytesReceived + " of " + length + " bytes)")
            }
            if (tempRaf != null) {
                //copy the finished temporary file to the USB/SD folder in order
                tempRaf.channel.position(0)
                targetChannel.position(0)
                var copied = 0L
                while (copied < length) {
                    if (downloadInfo.cancelled) return PartsResult.CANCELLED
                    downloadInfo.phase = "copying to the drive " + (copied * 100 / length) + "%"
                    callback.onProgress(this)
                    val n = try {
                        targetChannel.transferFrom(tempRaf.channel, copied, minOf(8L * 1024 * 1024, length - copied))
                    } catch (e: IOException) {
                        throw IOException(storageError(e, copied), e)
                    }
                    if (n <= 0) throw IOException("Copy to the chosen folder failed")
                    copied += n
                }
                downloadInfo.phase = null
            }
            keepTemp = false
            return PartsResult.DONE
        } finally {
            try { tempRaf?.close() } catch (ignored: IOException) {}
            if (!keepTemp) tempFile?.delete()
        }
    }
}

class BlobDownloadTask(override var downloadInfo: Download, val blobBase64Data: String, val callback: DownloadTask.Callback) : Runnable, DownloadTask {

    override fun run() {
        downloadInfo.id = AppDatabase.db.downloadDao().insert(downloadInfo)

        try {
            val blobAsBytes: ByteArray = Base64.decode(blobBase64Data.replaceFirst("data:${downloadInfo.mimeType};base64,", ""), 0)
            val output = AutoCloseOutputStream(LumenDownloads.openOutput(downloadInfo))
            AppDatabase.db.downloadDao().update(downloadInfo)
            output.buffered().use{ it.write(blobAsBytes) }
            downloadInfo.size = blobAsBytes.size.toLong()
            downloadInfo.bytesReceived = downloadInfo.size
        } catch (e: Exception) {
            downloadInfo.size = Download.BROKEN_MARK
            callback.onError(this, 0, e.toString())
            return
        } finally {
            cancelDownloadIfNeeded(downloadInfo)
        }
        callback.onDone(this)
    }
}

class StreamDownloadTask(override var downloadInfo: Download, val stream: InputStream, val callback: DownloadTask.Callback) : Runnable, DownloadTask {
    override fun run() {
        downloadInfo.id = AppDatabase.db.downloadDao().insert(downloadInfo)

        var output: OutputStream? = null
        try {
            output = AutoCloseOutputStream(LumenDownloads.openOutput(downloadInfo))
            AppDatabase.db.downloadDao().update(downloadInfo)
            val data = ByteArray(4096)
            var total: Long = 0
            var count = stream.read(data)
            while (count != -1) {
                if (downloadInfo.cancelled) {
                    downloadInfo.bytesReceived = 0
                    downloadInfo.size = Download.CANCELLED_MARK
                    callback.onDone(this)
                    return
                }
                total += count.toLong()
                output.write(data, 0, count)
                downloadInfo.bytesReceived = total
                callback.onProgress(this)
                count = stream.read(data)
            }
        } catch (e: Exception) {
            downloadInfo.size = Download.BROKEN_MARK
            callback.onError(this, 0, e.toString())
            return
        } finally {
            try {
                output?.close()
                stream.close()
            } catch (ignored: IOException) {
            }

            cancelDownloadIfNeeded(downloadInfo)
        }
        downloadInfo.size = downloadInfo.bytesReceived
        callback.onDone(this)
    }
}

private fun cancelDownloadIfNeeded(downloadInfo: Download) {
    val filePath = downloadInfo.filepath
    if (filePath.isEmpty()) return
    if (downloadInfo.cancelled) {
        if (!LumenDownloads.delete(filePath)) {
            Log.e(FileDownloadTask.TAG, "Download cancelled but content not deleted??")
        }
    } else {
        LumenDownloads.publish(downloadInfo)
    }
}
