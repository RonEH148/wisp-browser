package com.phlox.tvwebbrowser.activity.downloads

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ActivityDownloadsBinding
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

class DownloadsActivity : AppCompatActivity(), AdapterView.OnItemClickListener, ActiveDownloadsModel.Listener, AdapterView.OnItemLongClickListener{
    private lateinit var vb: ActivityDownloadsBinding
    private lateinit var adapter: DownloadListAdapter
    private val listeners = ArrayList<ActiveDownloadsModel.Listener>()

    private lateinit var activeDownloadsModel: ActiveDownloadsModel
    private lateinit var downloadsHistoryModel: DownloadsHistoryModel

    internal var onListScrollListener: AbsListView.OnScrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {

        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
            if (totalItemCount != 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                downloadsHistoryModel.loadNextItems()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate this:" + System.identityHashCode(this))
        vb = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(vb.root)

        activeDownloadsModel = ActiveModelsRepository.get(ActiveDownloadsModel::class, this)
        downloadsHistoryModel = ActiveModelsRepository.get(DownloadsHistoryModel::class, this)

        adapter = DownloadListAdapter(this)
        vb.listView.adapter = adapter

        vb.btnLumenAddDownload.setOnClickListener { showAddDownloadDialog() }
        vb.btnLumenPauseAll.setOnClickListener {
            withService { it.pauseAll() }
            Utils.showToast(this, "All downloads paused")
            vb.listView.postDelayed({ adapter.notifyDataSetChanged() }, 800)
        }
        vb.btnLumenResumeAll.setOnClickListener {
            withService { it.resumeAll() }
            Utils.showToast(this, "Paused and interrupted downloads continue")
            vb.listView.postDelayed({ adapter.notifyDataSetChanged() }, 800)
        }

        if (com.phlox.tvwebbrowser.Brand.phone) stylePhone()

        vb.listView.setOnScrollListener(onListScrollListener)
        vb.listView.onItemClickListener = this
        vb.listView.onItemLongClickListener = this

        downloadsHistoryModel.lastLoadedItems.subscribe(this, false, {
            //Wisp: only downloads still going (running, queued, paused, waiting to retry); finished
            //ones were confirmed with a notification and are in the Downloads folder
            val shown = if (phone) it.filter { d -> isUnfinished(d) } else it
            if (shown.isNotEmpty()) {
                vb.tvPlaceholder.visibility = View.GONE
                adapter.addItems(shown)
                vb.listView.requestFocus()
            }
            //a whole page of finished ones: look further
            if (phone && it.isNotEmpty()) vb.listView.post { downloadsHistoryModel.loadNextItems() }
            if (phone) updatePlaceholder()
        })

        //always read the list fresh: downloads started since the last visit are in the database now
        downloadsHistoryModel.allItems.clear()
        showRunningDownloads()
        downloadsHistoryModel.loadNextItems()
    }

    /** "Download from link": the main screen starts the download and brings this list back. */
    private fun showAddDownloadDialog() {
        val edit = android.widget.EditText(this)
        edit.hint = getString(R.string.lumen_paste_link_hint)
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        edit.isSingleLine = true
        //pre-fill with a link from the clipboard
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (clip != null && (clip.startsWith("http://") || clip.startsWith("https://"))) edit.setText(clip)
        } catch (ignored: Exception) {
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(edit)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.lumen_download_from_link)
            .setView(box)
            .setPositiveButton(R.string.lumen_download) { _, _ ->
                val url = edit.text.toString().trim()
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    Utils.showToast(this, R.string.lumen_invalid_link)
                    return@setPositiveButton
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_DOWNLOAD_URL, url))
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        edit.requestFocus()
    }

    private val phone = com.phlox.tvwebbrowser.Brand.phone

    private fun isUnfinished(d: Download): Boolean {
        if (d.isDateHeader) return false
        if (isDownloadActive(d)) return true
        if (d.size > 0 && d.bytesReceived >= d.size) return false
        if (com.phlox.tvwebbrowser.service.downloads.DownloadService.retryAt.containsKey(d.id)) return true
        return com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(d.id) != null
    }

    private fun updatePlaceholder() {
        if (adapter.isEmptyList()) {
            vb.tvPlaceholder.setText(R.string.wisp_no_active_downloads)
            vb.tvPlaceholder.visibility = View.VISIBLE
        } else vb.tvPlaceholder.visibility = View.GONE
    }

    /** Downloads running right now, also ones started after this list was loaded. */
    private fun showRunningDownloads() {
        for (task in activeDownloadsModel.activeDownloads.toList()) showRunning(task.downloadInfo)
    }

    private fun showRunning(download: Download) {
        if (download.id == 0L || adapter.contains(download.id)) return
        adapter.addOnTop(download)
        vb.tvPlaceholder.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        showRunningDownloads()
        activeDownloadsModel.registerListener(this)
    }

    override fun onPause() {
        activeDownloadsModel.unregisterListener(this@DownloadsActivity)
        super.onPause()
    }

    override fun onItemClick(adapterView: AdapterView<*>, view: View, i: Int, l: Long) {
        val v = view as DownloadListItemView
        val download = v.download ?: return

        if (download.isDateHeader) {
            return
        }

        if (download.size != download.bytesReceived || download.size <= 0) {
            //Lumen: running, paused or interrupted: pause / resume / stop
            showDownloadControls(download)
            return
        }


        val fileURI = if (com.phlox.tvwebbrowser.sofa.LumenDownloads.isContentPath(download.filepath)) {
            Uri.parse(download.filepath)
        } else {
            val file = File(download.filepath)
            if (!file.exists()) {
                Utils.showToast(this, R.string.file_not_found)
                return
            }

            FileProvider.getUriForFile(this@DownloadsActivity,
                BuildConfig.APPLICATION_ID + ".provider",
                file)
        }


        val openIntent = Intent(Intent.ACTION_VIEW)
        val mimeType = contentResolver.getType(fileURI)
        openIntent.setDataAndType(fileURI, mimeType)
        openIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            startActivity(openIntent)
        } catch (e: ActivityNotFoundException) {
            Utils.showToast(this, getString(R.string.no_app_for_file_type))
        }

    }

    override fun onItemLongClick(adapterView: AdapterView<*>, view: View, i: Int, l: Long): Boolean {
        val v = view as DownloadListItemView
        val download = v.download ?: return false
        if (download.isDateHeader) {
            return true
        }
        if (download.size == Download.BROKEN_MARK || download.size == Download.CANCELLED_MARK ||
                download.size == download.bytesReceived) {
            showFinishedDownloadOptionsPopup(v)
        } else {
            showUnfinishedDownloadOptionsPopup(v)
        }
        return true
    }

    // ---------------- Lumen: pause / resume / stop ----------------

    private var downloadService: com.phlox.tvwebbrowser.service.downloads.DownloadService? = null
    private val pendingActions = ArrayList<(com.phlox.tvwebbrowser.service.downloads.DownloadService) -> Unit>()
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
            val s = (service as? com.phlox.tvwebbrowser.service.downloads.DownloadService.Binder)?.service ?: return
            downloadService = s
            val list = ArrayList(pendingActions)
            pendingActions.clear()
            list.forEach { it(s) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { downloadService = null }
    }
    private var serviceBound = false

    /** Runs [action] with the download service (connecting to it first if needed). */
    private fun withService(action: (com.phlox.tvwebbrowser.service.downloads.DownloadService) -> Unit) {
        val s = downloadService
        if (s != null) { action(s); return }
        pendingActions.add(action)
        if (!serviceBound) {
            serviceBound = bindService(Intent(this, com.phlox.tvwebbrowser.service.downloads.DownloadService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        if (serviceBound) try { unbindService(serviceConnection) } catch (ignored: Exception) {}
        super.onDestroy()
    }

    private fun showDownloadControls(download: Download) {
        val task = activeDownloadsModel.taskOf(download)
        if (task != null && task.downloadInfo.queued) {
            val id = download.id
            val acts = arrayOf("▶  Start now", "⏸  Pause (take it out of the queue)", "✖  Remove from the list")
            AlertDialog.Builder(this)
                .setTitle(download.filename + "\n(queued)")
                .setItems(acts) { _, which ->
                    withService { s ->
                        when (which) {
                            0 -> s.startNow(id)
                            1 -> s.unqueue(id, false)
                            else -> { s.unqueue(id, true); adapter.remove(download) }
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }
        val running = task != null
        val state = com.phlox.tvwebbrowser.sofa.LumenDownloadState.get(download.id)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (running) {
            val info = task!!.downloadInfo
            if (task is com.phlox.tvwebbrowser.service.downloads.FileDownloadTask && info.size > 0) {
                actions.add((if (info.canResume) "⏸  Pause" else "⏸  Pause (this server can't continue: it starts over)") to {
                    activeDownloadsModel.pauseDownload(download)
                })
            }
            actions.add("⏹  Stop and delete the file" to { activeDownloadsModel.cancelDownload(download) })
        } else {
            if (state != null) {
                actions.add("▶  Resume" to { withService { it.resumeDownload(download); adapter.notifyDataSetChanged() } })
            }
            actions.add("⟳  Download again from the start" to {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_DOWNLOAD_URL, state?.url ?: download.url))
                com.phlox.tvwebbrowser.sofa.LumenDownloadState.remove(download.id)
                Thread {
                    com.phlox.tvwebbrowser.sofa.LumenDownloads.delete(download.filepath)
                    download.size = Download.CANCELLED_MARK
                    download.bytesReceived = 0
                    try { AppDatabase.db.downloadDao().update(download) } catch (ignored: Exception) {}
                }.start()
                finish()
            })
            actions.add("⏹  Stop and delete the file" to { lifecycleScope.launch { stopAndDelete(download) } })
        }
        val title = download.filename + when {
            running -> ""
            state?.paused == true -> "\n(paused)"
            state != null -> "\n(interrupted)"
            else -> ""
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private suspend fun stopAndDelete(download: Download) {
        com.phlox.tvwebbrowser.sofa.LumenDownloadState.remove(download.id)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.phlox.tvwebbrowser.sofa.LumenDownloads.delete(download.filepath)
            download.size = Download.CANCELLED_MARK
            download.bytesReceived = 0
            AppDatabase.db.downloadDao().update(download)
        }
        adapter.notifyDataSetChanged()
    }

    private fun showUnfinishedDownloadOptionsPopup(v: DownloadListItemView) {
        v.download?.let { showDownloadControls(it); return }
        val pm = PopupMenu(this, v, Gravity.BOTTOM)
        pm.menu.add(R.string.cancel)
        pm.setOnMenuItemClickListener {
            activeDownloadsModel.cancelDownload(v.download!!)
            true
        }
        pm.show()
    }

    private fun showFinishedDownloadOptionsPopup(v: DownloadListItemView) {
        val download = v.download ?: return
        val pm = PopupMenu(this, v, Gravity.BOTTOM)
        if (download.filename.endsWith(".apk", true) &&
            download.size == download.bytesReceived) {
            pm.menu.add(0, 0, 0, R.string.install)
        }
        pm.menu.add(0, 1, 1, R.string.open_folder)
        pm.menu.add(0, 2, 2, R.string.delete)
        pm.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> installAPK(download)
                1 -> openFolder(v)
                2 -> deleteItem(v)
            }
            true
        }
        pm.show()
    }

    private fun installAPK(download: Download) {
        val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //packageManager.canRequestPackageInstalls()
            true
        } else
            Settings.Secure.getInt(this.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1

        if(canInstallFromOtherSources) {
            launchInstallAPKActivity(this, download)
        } else {
            AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        R.string.turn_on_unknown_sources_for_app else R.string.turn_on_unknown_sources)
                    .setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { dialog, which -> run {
                        val intentSettings = Intent()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            intentSettings.action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                            intentSettings.data = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                        } else {
                            intentSettings.action = Settings.ACTION_SECURITY_SETTINGS
                        }
                        intentSettings.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivityForResult(intentSettings, REQUEST_CODE_UNKNOWN_APP_SOURCES)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
                        }
                    }})
                    .show()

        }
    }

    private fun openFolder(v: DownloadListItemView) {
        val uri = com.phlox.tvwebbrowser.sofa.LumenDownloads.customFolder()
            ?: Uri.parse(com.phlox.tvwebbrowser.sofa.LumenDownloads.defaultFolderFile().absolutePath)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "resource/folder")
        if (intent.resolveActivityInfo(packageManager, 0) != null) {
            startActivity(intent)
        } else {
            Utils.showToast(this, R.string.no_file_explorer_msg)
        }
    }

    private fun deleteItem(v: DownloadListItemView) = lifecycleScope.launch(Dispatchers.Main) {
        v.download?.let {
            //stop it first if it is still running; the file goes on a background thread (USB drives)
            activeDownloadsModel.cancelDownload(it)
            adapter.remove(it)
            kotlinx.coroutines.withContext(Dispatchers.IO) { activeDownloadsModel.deleteItem(it) }
        }
    }

    override fun onDownloadUpdated(downloadInfo: Download) {
        if (phone && downloadInfo.size > 0 && downloadInfo.bytesReceived >= downloadInfo.size && !isDownloadActive(downloadInfo)) {
            //finished: leaves the list after a moment
            val id = downloadInfo.id
            vb.listView.postDelayed({ adapter.removeById(id); updatePlaceholder() }, 1200)
        } else showRunning(downloadInfo)
        for (i in listeners.indices) {
            listeners[i].onDownloadUpdated(downloadInfo)
        }
    }

    override fun onDownloadError(downloadInfo: Download, responseCode: Int, responseMessage: String) {
        if (phone) {
            //failed for good (no retry coming): the reason was shown as a message; off the list
            val id = downloadInfo.id
            vb.listView.postDelayed({
                if (!com.phlox.tvwebbrowser.service.downloads.DownloadService.retryAt.containsKey(id) &&
                    activeDownloadsModel.activeDownloads.none { it.downloadInfo.id == id }) {
                    adapter.removeById(id)
                    updatePlaceholder()
                }
            }, 2500)
        }
        for (i in listeners.indices) {
            listeners[i].onDownloadError(downloadInfo, responseCode, responseMessage)
        }
    }

    override fun onAllDownloadsComplete() {}

    fun registerListener(listener: ActiveDownloadsModel.Listener) {
        listeners.add(listener)
    }

    /** True while the download is really running (not one left unfinished by an earlier session). */
    /** The running task's own download object (connections, pause support). */
    fun liveInfo(download: Download): Download? = activeDownloadsModel.taskOf(download)?.downloadInfo

    fun isDownloadActive(download: Download): Boolean =
        activeDownloadsModel.activeDownloads.any { it.downloadInfo.id == download.id }

    fun unregisterListener(listener: ActiveDownloadsModel.Listener) {
        listeners.remove(listener)
    }

    companion object {
        const val REQUEST_CODE_UNKNOWN_APP_SOURCES = 10007
        const val EXTRA_DOWNLOAD_URL = "lumen_download_url"

        /** What a running download does besides downloading (e.g. copying to the USB drive). */
        fun phaseOf(d: Download?): String? = d?.phase
        private const val REQUEST_CODE_INSTALL_PACKAGE = 10008
        val TAG: String = DownloadsActivity::class.java.simpleName

        fun launchInstallAPKActivity(activity: Activity, download: Download) {
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("apk")
            val apkURI = if (com.phlox.tvwebbrowser.sofa.LumenDownloads.isContentPath(download.filepath)) {
                Uri.parse(download.filepath)
            } else {
                val file = File(download.filepath)
                FileProvider.getUriForFile(
                    activity,
                    activity.applicationContext.packageName + ".provider", file
                )
            }

            val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
            install.setDataAndType(apkURI, mimeType)
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivityForResult(install, REQUEST_CODE_INSTALL_PACKAGE)//we are not using result for now
            } catch (e: Exception) {
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Wisp: a phone screen - title bar with Back, and the three actions as rounded buttons with
     * icons (one filled, two tonal) instead of TV boxes with plain text.
     */
    private fun stylePhone() {
        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        fun color(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)
        val root = vb.root as android.widget.RelativeLayout
        root.setBackgroundColor(color(R.color.wisp_bg))
        window.statusBarColor = color(R.color.wisp_bar)

        //title bar: ←  Downloads
        val bar = android.widget.LinearLayout(this)
        bar.id = View.generateViewId()
        bar.orientation = android.widget.LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(color(R.color.wisp_bar))
        bar.setPadding(px(4f), 0, px(16f), 0)
        val back = android.widget.ImageButton(this)
        back.setImageResource(R.drawable.ic_wisp_back)
        back.setColorFilter(color(R.color.wisp_icon))
        back.setBackgroundResource(R.drawable.button_bg_selector)
        back.contentDescription = getString(R.string.wisp_back)
        back.setOnClickListener { finish() }
        bar.addView(back, android.widget.LinearLayout.LayoutParams(px(48f), px(48f)))
        val title = android.widget.TextView(this)
        title.setText(R.string.wisp_downloads)
        title.textSize = 20f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setTextColor(color(R.color.wisp_text))
        title.setPadding(px(8f), 0, 0, 0)
        bar.addView(title)
        root.addView(bar, 0, android.widget.RelativeLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, px(60f)).also {
            it.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
        })

        fun button(b: android.widget.Button, label: Int, icon: Int, filled: Boolean) {
            b.setText(label)
            b.isAllCaps = false
            b.textSize = 14f
            b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
            val fg = color(if (filled) R.color.wisp_on_accent else R.color.wisp_accent)
            b.setTextColor(fg)
            b.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33888888),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(color(if (filled) R.color.wisp_accent else R.color.wisp_accent_soft))
                    cornerRadius = 20 * dp
                }, null)
            b.stateListAnimator = null
            b.elevation = 0f
            b.minHeight = px(40f)
            b.minimumHeight = px(40f)
            b.setPadding(px(14f), 0, px(16f), 0)
            val d = androidx.core.content.ContextCompat.getDrawable(this, icon)?.mutate()
            d?.setTint(fg)
            d?.setBounds(0, 0, px(18f), px(18f))
            b.setCompoundDrawables(d, null, null, null)
            b.compoundDrawablePadding = px(6f)
        }
        button(vb.btnLumenAddDownload, R.string.wisp_dl_add, R.drawable.ic_wisp_add, true)
        button(vb.btnLumenPauseAll, R.string.wisp_dl_pause_all, R.drawable.ic_wisp_pause, false)
        button(vb.btnLumenResumeAll, R.string.wisp_dl_resume_all, R.drawable.ic_wisp_play, false)

        //the three buttons in one row under the title bar
        (vb.btnLumenAddDownload.layoutParams as android.widget.RelativeLayout.LayoutParams).let {
            it.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
            it.addRule(android.widget.RelativeLayout.BELOW, bar.id)
            it.setMargins(px(14f), px(12f), px(8f), px(10f))
            vb.btnLumenAddDownload.layoutParams = it
        }
        for (b in listOf(vb.btnLumenPauseAll, vb.btnLumenResumeAll)) {
            (b.layoutParams as android.widget.RelativeLayout.LayoutParams).let {
                it.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                it.addRule(android.widget.RelativeLayout.ALIGN_TOP, vb.btnLumenAddDownload.id)
                it.setMargins(0, 0, px(8f), 0)
                b.layoutParams = it
            }
        }
        vb.listView.divider = android.graphics.drawable.ColorDrawable(color(R.color.wisp_divider))
        vb.listView.dividerHeight = 1
        vb.tvPlaceholder.setText(R.string.wisp_dl_empty)
        vb.tvPlaceholder.textSize = 16f
        vb.tvPlaceholder.setTextColor(color(R.color.wisp_text_secondary))
    }
}
