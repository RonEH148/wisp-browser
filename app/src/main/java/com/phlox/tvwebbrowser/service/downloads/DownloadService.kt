package com.phlox.tvwebbrowser.service.downloads

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.downloads.ActiveDownloadsModel
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import java.io.File
import java.util.*
import java.util.concurrent.Executors

/**
 * Created by PDT on 23.01.2017.
 */

class DownloadService : Service() {
    private lateinit var model: ActiveDownloadsModel
    private val executor = Executors.newCachedThreadPool()
    /** Database work in order (a queued download is stored before it can start). */
    private val dbExec = Executors.newSingleThreadExecutor()
    /** Lumen: downloads waiting for a free slot (Settings → Downloads at once). */
    private val queue = ArrayList<DownloadTask>()
    /** Lumen: interrupted downloads waiting to be retried, and how often they were already. */
    private val pendingRetry = HashMap<Long, Download>()
    private val retryRunnables = HashMap<Long, Runnable>()
    private val retryCount = HashMap<Long, Int>()
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var notificationBuilder: NotificationCompat.Builder? = null
    private lateinit var notificationManager: NotificationManager

    internal var downloadTasksListener: DownloadTask.Callback = object : DownloadTask.Callback {
        val MIN_NOTIFY_TIMEOUT = 100
        private var lastNotifyTime = System.currentTimeMillis()
        override fun onProgress(task: DownloadTask) {
            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > MIN_NOTIFY_TIMEOUT) {
                lastNotifyTime = now
                handler.post {
                    model.notifyListenersAboutDownloadProgress(task)
                    notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, updateNotification())
                }
            }
        }

        override fun onError(task: DownloadTask, responseCode: Int, responseMessage: String) {
            AppDatabase.db.downloadDao().update(task.downloadInfo)
            handler.post {
                model.notifyListenersAboutError(task, responseCode, responseMessage)
                //trying again only helps with network trouble or a busy server; a refusal (403, 404…)
                //stays a refusal - it kept the notification "waiting" for many minutes
                val worthRetrying = responseCode == 0 || responseCode >= 500 || responseCode == 408 || responseCode == 429
                com.phlox.tvwebbrowser.sofa.WispLog.warn("download", "failed: ${task.downloadInfo.filename} from " +
                        "${com.phlox.tvwebbrowser.sofa.WispLog.site(task.downloadInfo.url)} · " +
                        (if (responseCode > 0) "server answered $responseCode $responseMessage" else responseMessage) +
                        (if (worthRetrying) " · will retry" else ""))
                if (worthRetrying) scheduleRetry(task.downloadInfo)
                else try {
                    android.widget.Toast.makeText(this@DownloadService,
                        "Download failed: " + task.downloadInfo.filename +
                                (if (responseCode > 0) " (server answered $responseCode $responseMessage)" else " ($responseMessage)"),
                        android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {}
                onTaskEnded(task)
                //shown again once it is no longer running ("Interrupted – press OK to resume")
                model.notifyListenersAboutDownloadProgress(task)
            }
        }

        override fun onDone(task: DownloadTask) {
            AppDatabase.db.downloadDao().update(task.downloadInfo)
            handler.post {
                if (task.downloadInfo.size > 0 && task.downloadInfo.bytesReceived == task.downloadInfo.size) {
                    retryCount.remove(task.downloadInfo.id)
                    if (com.phlox.tvwebbrowser.Brand.phone) confirmDone(task.downloadInfo)
                } else if (com.phlox.tvwebbrowser.Brand.phone && task.downloadInfo.size <= 0 &&
                    task.downloadInfo.bytesReceived > 0 && !task.downloadInfo.paused && !task.downloadInfo.cancelled) {
                    //size not given by the server: done when the task ended normally
                    confirmDone(task.downloadInfo)
                }
                onTaskEnded(task)
                model.notifyListenersAboutDownloadProgress(task)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        model = ActiveModelsRepository.get(ActiveDownloadsModel::class, this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        //network back: interrupted downloads continue at once instead of waiting for their retry time
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    handler.postDelayed({ retryAllNow() }, 2000)
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            networkCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) }
        } catch (e: Exception) {
        }
        ActiveModelsRepository.markAsNeedless(model, this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) startFromIntent(intent)
        return START_STICKY
    }

    /**
     * A download asked for by private mode, which runs in its own process (":incognito") and so
     * can't use this service directly: before, such downloads waited forever and nothing happened.
     * Data held by the page (blob / data: files) arrives as a temporary file.
     */
    private fun startFromIntent(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val file = intent.getStringExtra(EXTRA_DATA_FILE)?.let { java.io.File(it) }
        val stream: java.io.InputStream? = if (file != null && file.exists()) {
            object : java.io.FileInputStream(file) {
                override fun close() {
                    super.close()
                    file.delete()
                }
            }
        } else null
        val download = Download(url, intent.getStringExtra(EXTRA_NAME) ?: "download", null,
            if (intent.getBooleanExtra(EXTRA_INSTALL, false)) Download.OperationAfterDownload.INSTALL
            else Download.OperationAfterDownload.NOP,
            intent.getStringExtra(EXTRA_MIME), intent.getStringExtra(EXTRA_REFERER), intent.getStringExtra(EXTRA_UA),
            null, stream, intent.getLongExtra(EXTRA_SIZE, 0L))
        download.cookies = intent.getStringExtra(EXTRA_COOKIES)
        download.fromPrivateMode = true
        startDownload(download)
    }


    private fun updateNotification(): Notification {
        var title = ""
        var downloaded = 0L
        var total = 0L
        var hasUnknownSizedFiles = false
        for (download in model.activeDownloads) {
            title += download.downloadInfo.filename + ","
            downloaded += download.downloadInfo.bytesReceived
            if (download.downloadInfo.size > 0) {
                total += download.downloadInfo.size
            } else {
                hasUnknownSizedFiles = true
            }
        }
        title = title.trim(',')
        if (title.isEmpty() && pendingRetry.isNotEmpty()) title = "Waiting to continue " + pendingRetry.size + " download(s)…"
        val description = if (hasUnknownSizedFiles) {
            Formatter.formatShortFileSize(this, downloaded)
        } else {
            Formatter.formatShortFileSize(this, downloaded) + " of " +
                    Formatter.formatShortFileSize(this, total)
        }
        if (notificationBuilder == null) {
            notificationBuilder = NotificationCompat.Builder(this, TVBro.CHANNEL_ID_DOWNLOADS)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_stat_app)
        }
        notificationBuilder!!.setContentTitle(title)
                .setContentText(description)
                .setSmallIcon(R.drawable.ic_stat_app)
        if (hasUnknownSizedFiles || total == 0L) {
            notificationBuilder!!.setProgress(0, 0, true)
        } else {
            notificationBuilder!!.setProgress(100, (downloaded * 100 / total).toInt(), false)
        }
        return notificationBuilder!!.build()
    }

    override fun onBind(intent: Intent): IBinder {
        return Binder(this)
    }

    private fun onTaskEnded(task: DownloadTask) {
        val finished = !task.downloadInfo.paused && task.downloadInfo.size > 0 &&
                task.downloadInfo.bytesReceived == task.downloadInfo.size
        if (finished) when (task.downloadInfo.operationAfterDownload) {
            Download.OperationAfterDownload.INSTALL -> {
                val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    packageManager.canRequestPackageInstalls()
                } else {
                    Settings.Secure.getInt(this.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1
                }
                if (canInstallFromOtherSources) {
                    launchInstallAPKActivity(this, task.downloadInfo)
                }
            }
            Download.OperationAfterDownload.NOP -> {
            }
        }
        model.onDownloadEnded(task)
        queue.remove(task)
        startNext()
        if (model.activeDownloads.isEmpty() && pendingRetry.isEmpty()) {
            stopForeground(true)
            stopSelf()
        } else if (model.activeDownloads.isEmpty()) {
            //only waiting to try again: the notification can be swiped away
            stopForeground(STOP_FOREGROUND_DETACH)
            try {
                val n = updateNotification()
                n.flags = n.flags and android.app.Notification.FLAG_ONGOING_EVENT.inv() and
                        android.app.Notification.FLAG_NO_CLEAR.inv()
                notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, n)
            } catch (e: Exception) {}
        }
    }

    // ---------------- Lumen: queue, retries, pause all / resume all ----------------

    private fun running() = model.activeDownloads.count { !it.downloadInfo.queued }

    /** Starts the task, or puts it in the queue when "Downloads at once" are running already. */
    private fun launch(task: DownloadTask) {
        val info = task.downloadInfo
        model.activeDownloads.add(task)
        if (task is FileDownloadTask && running() > com.phlox.tvwebbrowser.AppContext.provideConfig().lumenMaxDownloads) {
            info.queued = true
            queue.add(task)
            //in the list right away, and still there after a restart
            dbExec.execute {
                if (info.id == 0L) info.id = AppDatabase.db.downloadDao().insert(info)
                if (com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(info.id) == null) {
                    com.phlox.tvwebbrowser.sofa.LumenDownloadState.save(info.id, com.phlox.tvwebbrowser.sofa.LumenDownloadState.State(
                        info.url, info.referer, info.userAgentString, info.mimeType, 0, emptyList(), 0, null, false))
                }
                handler.post { model.notifyListenersAboutDownloadProgress(task) }
            }
        } else {
            info.queued = false
            dbExec.execute { executor.execute(task as Runnable) }
        }
        startService(Intent(this, DownloadService::class.java))
        startForeground(DOWNLOAD_NOTIFICATION_ID, updateNotification())
    }

    /** Free slots go to the queue in order. */
    private fun startNext() {
        val max = com.phlox.tvwebbrowser.AppContext.provideConfig().lumenMaxDownloads
        while (queue.isNotEmpty() && running() < max) {
            val t = queue.removeAt(0)
            t.downloadInfo.queued = false
            dbExec.execute { executor.execute(t as Runnable) }
            model.notifyListenersAboutDownloadProgress(t)
        }
    }

    /** "Start now" for a queued download, even if that is one more than "Downloads at once". */
    fun startNow(id: Long) {
        val t = queue.firstOrNull { it.downloadInfo.id == id } ?: return
        queue.remove(t)
        t.downloadInfo.queued = false
        dbExec.execute { executor.execute(t as Runnable) }
        model.notifyListenersAboutDownloadProgress(t)
    }

    /** A queued download leaves the queue: kept as paused, or removed completely. */
    fun unqueue(id: Long, remove: Boolean) {
        val t = queue.firstOrNull { it.downloadInfo.id == id } ?: return
        queue.remove(t)
        model.onDownloadEnded(t)
        val info = t.downloadInfo
        info.queued = false
        dbExec.execute {
            val st = com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(id)
            if (remove) {
                com.phlox.tvwebbrowser.sofa.LumenDownloadState.remove(id)
                info.size = Download.CANCELLED_MARK
                info.bytesReceived = 0
                AppDatabase.db.downloadDao().update(info)
            } else if (st != null) {
                com.phlox.tvwebbrowser.sofa.LumenDownloadState.save(id, com.phlox.tvwebbrowser.sofa.LumenDownloadState.State(
                    st.url, st.referer, st.userAgent, st.mimeType, st.size, st.missing, st.written, st.tempFile, true))
            }
            handler.post {
                model.notifyListenersAboutDownloadProgress(t)
                if (model.activeDownloads.isEmpty() && pendingRetry.isEmpty()) { stopForeground(true); stopSelf() }
            }
        }
    }

    fun pauseAll() {
        for (id in pendingRetry.keys.toList()) cancelRetry(id)
        for (t in model.activeDownloads.toList()) {
            if (t.downloadInfo.queued) unqueue(t.downloadInfo.id, false)
            else if (t is FileDownloadTask && t.downloadInfo.size > 0) t.downloadInfo.paused = true
        }
    }

    /** Paused and interrupted downloads continue (the queue keeps "Downloads at once"). [onlyInterrupted]: not the paused ones. */
    fun resumeAll(onlyInterrupted: Boolean = false) {
        dbExec.execute {
            val list = ArrayList<Download>()
            for (id in com.phlox.tvwebbrowser.sofa.LumenDownloadState.ids()) {
                val st = com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(id) ?: continue
                if (onlyInterrupted && st.paused) continue
                val d = try { AppDatabase.db.downloadDao().getById(id) } catch (e: Exception) { null }
                if (d == null) { com.phlox.tvwebbrowser.sofa.LumenDownloadState.remove(id); continue }
                list.add(d)
            }
            handler.post {
                for (d in list) if (model.activeDownloads.none { it.downloadInfo.id == d.id }) resumeDownload(d)
            }
        }
    }

    /** Once per app start: downloads interrupted by an error or by closing the app continue by themselves. */
    fun resumeInterruptedOnce() {
        if (interruptedResumed) return
        interruptedResumed = true
        resumeAll(onlyInterrupted = true)
    }

    /** Network or server trouble: the download continues by itself after a while (or when the network is back). */
    private fun scheduleRetry(info: Download) {
        if (info.paused || info.cancelled) return
        val st = com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(info.id) ?: return
        if (st.paused) return
        val n = (retryCount[info.id] ?: 0) + 1
        if (n > RETRY_DELAYS_MS.size) { retryCount.remove(info.id); return }
        retryCount[info.id] = n
        val delay = RETRY_DELAYS_MS[n - 1]
        val id = info.id
        val r = Runnable {
            retryRunnables.remove(id)
            val d = pendingRetry.remove(id) ?: return@Runnable
            retryAt.remove(id)
            resumeDownload(d)
        }
        pendingRetry[id] = info
        retryRunnables[id] = r
        retryAt[id] = System.currentTimeMillis() + delay
        handler.postDelayed(r, delay)
    }

    private fun cancelRetry(id: Long) {
        retryRunnables.remove(id)?.let { handler.removeCallbacks(it) }
        pendingRetry.remove(id)
        retryAt.remove(id)
    }

    private fun retryAllNow() {
        for (id in pendingRetry.keys.toList()) {
            val r = retryRunnables[id] ?: continue
            handler.removeCallbacks(r)
            r.run()
        }
    }

    fun launchInstallAPKActivity(context: Context, download: Download) {
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("apk")
        val apkURI = if (com.phlox.tvwebbrowser.sofa.LumenDownloads.isContentPath(download.filepath)) {
            android.net.Uri.parse(download.filepath)
        } else {
            FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider", File(download.filepath))
        }

        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
        install.setDataAndType(apkURI, mimeType)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
        }
    }

    fun startDownload(download: Download) {
        com.phlox.tvwebbrowser.sofa.WispLog.event("download", "start ${download.filename} from " +
                com.phlox.tvwebbrowser.sofa.WispLog.site(download.url))
        //e.g. the USB drive is unplugged: saved to internal storage instead (openOutput). Checked off
        //the screen's thread: a busy or hanging drive must not freeze the browser
        executor.execute {
            if (com.phlox.tvwebbrowser.sofa.LumenDownloads.customFolderMissing()) {
                handler.post { Toast.makeText(this, R.string.lumen_folder_missing, Toast.LENGTH_LONG).show() }
            }
        }
        //a folder picked in settings is handled by LumenDownloads.openOutput()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && com.phlox.tvwebbrowser.sofa.LumenDownloads.customFolder() == null) {
            val extPos = download.filename.lastIndexOf(".")
            val hasExt = extPos != -1
            var ext: String? = null
            var prefix: String? = null
            if (hasExt) {
                ext = download.filename.substring(extPos + 1)
                prefix = download.filename.substring(0, extPos)
            }
            var fileName = download.filename
            var i = 0
            while (File(com.phlox.tvwebbrowser.sofa.LumenDownloads.defaultFolderFile(), fileName).exists()) {
                i++
                if (hasExt) {
                    fileName = prefix + "_(" + i + ")." + ext
                } else {
                    fileName = download.filename + "_(" + i + ")"
                }
            }
            download.filename = fileName

            if (Environment.MEDIA_MOUNTED != Environment.getExternalStorageState()) {
                Toast.makeText(this, R.string.storage_not_mounted, Toast.LENGTH_SHORT).show()
                return
            }
            val downloadsDir = com.phlox.tvwebbrowser.sofa.LumenDownloads.defaultFolderFile()
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                Toast.makeText(this, R.string.can_not_create_downloads, Toast.LENGTH_SHORT)
                    .show()
                return
            }
            download.filepath = downloadsDir.toString() + File.separator + fileName
        }

        download.time = Date().time
        Log.d(TAG, "Start to downloading url: ${download.url}")
        val downloadTask = if (download.base64BlobData != null) {
            BlobDownloadTask(download, download.base64BlobData!!, downloadTasksListener)
        } else if (download.stream != null) {
            StreamDownloadTask(download, download.stream!!, downloadTasksListener)
        } else {
            FileDownloadTask(download, download.userAgentString, downloadTasksListener)
        }
        launch(downloadTask)
    }

    /**
     * Android 15+: a download service may run 6 hours a day. When that is used up, Android asks it
     * to stop at once (else the app is closed with an error): the running downloads are paused
     * with their progress kept, and a notification says how to continue.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        pauseAll()
        try {
            val n = NotificationCompat.Builder(this, TVBro.CHANNEL_ID_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle("Downloads paused")
                .setContentText("Android lets downloads run in the background 6 hours a day. Open ${com.phlox.tvwebbrowser.Brand.NAME} → Downloads to continue.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Android lets downloads run in the background 6 hours a day. Open ${com.phlox.tvwebbrowser.Brand.NAME} → Downloads and " +
                            "press OK on a download to continue it where it stopped."))
                .setAutoCancel(true)
                .build()
            notificationManager.notify(DOWNLOAD_NOTIFICATION_ID + 1, n)
        } catch (e: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Wisp: "Downloaded" for each finished file - a message on screen and a notification that
     * opens the file when tapped (it can be swiped away).
     */
    private fun confirmDone(info: Download) {
        com.phlox.tvwebbrowser.sofa.WispLog.event("download", "done ${info.filename} (${info.bytesReceived / 1024} kB)")
        try {
            android.widget.Toast.makeText(this, "Downloaded: " + info.filename, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {}
        try {
            val uri = if (com.phlox.tvwebbrowser.sofa.LumenDownloads.isContentPath(info.filepath)) android.net.Uri.parse(info.filepath)
                else androidx.core.content.FileProvider.getUriForFile(this, com.phlox.tvwebbrowser.BuildConfig.APPLICATION_ID + ".provider",
                    java.io.File(info.filepath))
            val mime = info.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: contentResolver.getType(uri) ?: "*/*"
            val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(this, info.id.toInt(), open,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, TVBro.CHANNEL_ID_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle("Download complete")
                .setContentText(info.filename)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(DOWNLOAD_NOTIFICATION_ID + 1000 + (info.id % 100_000).toInt(), n)
        } catch (e: Exception) {
            Log.e(TAG, "done notification", e)
        }
    }

    /** Lumen: continues a paused or interrupted download from where it stopped. */
    fun resumeDownload(download: Download): Boolean {
        val state = com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(download.id) ?: return false
        if (model.activeDownloads.any { it.downloadInfo.id == download.id }) return true
        cancelRetry(download.id)
        download.paused = false
        download.cancelled = false
        download.url = state.url
        if (download.referer == null) download.referer = state.referer
        if (download.mimeType == null) download.mimeType = state.mimeType
        val task = if (state.size <= 0 && state.written == 0L && state.missing.isEmpty()) {
            //was queued and never started: a fresh start (the database entry is kept)
            download.size = 0
            download.bytesReceived = 0
            FileDownloadTask(download, state.userAgent, downloadTasksListener)
        } else {
            FileDownloadTask(download, state.userAgent, downloadTasksListener, state)
        }
        launch(task)
        return true
    }

    class Binder(val service: DownloadService) : android.os.Binder()

    companion object {
        val TAG: String = DownloadService::class.java.simpleName
        const val DOWNLOAD_NOTIFICATION_ID = 101101
        /** Waits before an interrupted download is tried again (1st, 2nd, … retry). */
        private val RETRY_DELAYS_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000, 300_000)
        /** When each waiting download is retried (for the list). */
        val retryAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        @Volatile private var interruptedResumed = false
        const val ACTION_START = "com.sofabrowse.action.START_DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_UA = "ua"
        const val EXTRA_SIZE = "size"
        const val EXTRA_INSTALL = "install"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_DATA_FILE = "data_file"
    }
}
