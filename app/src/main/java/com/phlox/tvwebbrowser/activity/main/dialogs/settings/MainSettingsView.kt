package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import android.app.Activity
import com.phlox.tvwebbrowser.sofa.SofaPasswords
import com.phlox.tvwebbrowser.sofa.SofaVpn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.AdblockModel
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsMainBinding
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private var vb = ViewSettingsMainBinding.inflate(LayoutInflater.from(getContext()), this, true)
    var settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)
    var adblockModel = ActiveModelsRepository.get(AdblockModel::class, activity!!)
    var config = AppContext.provideConfig()

    init {
        initWebBrowserEngineSettingsUI()

        initHomePageAndSearchEngineConfigUI()

        initUAStringConfigUI(context)

        initAdBlockConfigUI()

        initSofaExtrasUI()

        initThemeSettingsUI()

        initWebViewAlgorithmicDarkeningWithDarkUiModeUI()

        initAllowAutoplayMediaUI()

        initWebEngineDebugUI()

        initKeepScreenOnUI()

        initJoystickAxesNavigationUI()

        initVirtualCursorPhysicsSettingsUI()

        vb.btnClearWebCache.setOnClickListener {
            (activity as MainActivity).lifecycleScope.launch {
                WebEngineFactory.clearCache(context)
                Toast.makeText(context, android.R.string.ok, Toast.LENGTH_SHORT).show()
            }
        }

        //Android System WebView's own developer screen ("WebView DevTools"): it has no icon on TVs
        vb.btnWebViewDevTools.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(R.string.lumen_webview_devtools)
                .setMessage("Opens Android System WebView's developer screen. Its Flags tab can switch experimental " +
                        "engine features on or off for all apps that show web pages (${com.phlox.tvwebbrowser.Brand.NAME} too).\n\n" +
                        "For videos that only break into blocks on this TV: in Flags, search for \"video decode\" or " +
                        "\"accelerated\". If there is a flag that turns off accelerated (hardware) video decoding, set it " +
                        "to Enabled / Disabled as its description says, then close and reopen ${com.phlox.tvwebbrowser.Brand.NAME}. Set it back to " +
                        "Default to undo; \"Reset all to default\" undoes everything.")
                .setPositiveButton("Open") { _, _ ->
                    try {
                        context.startActivity(android.content.Intent("com.android.webview.SHOW_DEV_UI")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Toast.makeText(context, "This device's WebView has no developer screen", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        vb.btnBrowserCheck.setOnClickListener { (activity as? MainActivity)?.openBrowserCheck() }

        vb.btnRepairWebEngine.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(R.string.lumen_repair_engine)
                .setMessage("For videos that show blocks, coloured squares or a frozen picture on this device only.\n\n" +
                        "${com.phlox.tvwebbrowser.Brand.NAME} restarts and deletes the web engine's graphics and video caches and its cached files. " +
                        "Logins, cookies, passwords, bookmarks and settings are kept.\n\n" +
                        "If it doesn't help, restart the TV completely (Settings → Device → Restart, or unplug it for 30 seconds).")
                .setPositiveButton("Repair and restart") { _, _ ->
                    TVBro.requestWebEngineRepair(context)
                    TVBro.instance.needToExitProcessAfterMainActivityFinish = true
                    TVBro.instance.needRestartMainActivityAfterExitingProcess = true
                    activity!!.finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun initWebBrowserEngineSettingsUI() {
        if (WebEngineFactory.getProviders().size == 1) {
            vb.llWebEngine.visibility = View.GONE
            return
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, Config.SupportedWebEngines)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        vb.spWebEngine.adapter = adapter

        vb.spWebEngine.setSelection(Config.SupportedWebEngines.indexOf(config.webEngine), false)

        vb.spWebEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (config.webEngine == Config.SupportedWebEngines[position]) return
                if (Config.SupportedWebEngines[position] == Config.ENGINE_GECKO_VIEW && !Config.canRecommendGeckoView()) {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.warning)
                        .setMessage(R.string.settings_engine_change_gecko_msg)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            config.webEngine = Config.SupportedWebEngines[position]
                            showRestartDialog()
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            vb.spWebEngine.setSelection(Config.SupportedWebEngines.indexOf(config.webEngine), false)
                        }
                        .show()
                    return
                } else if (Config.SupportedWebEngines[position] == Config.ENGINE_WEB_VIEW) {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.warning)
                        .setMessage(R.string.settings_engine_change_webview_msg)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            config.webEngine = Config.SupportedWebEngines[position]
                            showRestartDialog()
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            vb.spWebEngine.setSelection(Config.SupportedWebEngines.indexOf(config.webEngine), false)
                        }
                        .show()
                    return
                }
                config.webEngine = Config.SupportedWebEngines[position]
                showRestartDialog()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(context)
            .setTitle(R.string.need_restart)
            .setMessage(R.string.need_restart_message)
            .setPositiveButton(R.string.exit) { _, _ ->
                TVBro.instance.needToExitProcessAfterMainActivityFinish = true
                TVBro.instance.needRestartMainActivityAfterExitingProcess = true
                activity!!.finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun initSofaExtrasUI() {
        bindSofaSwitch(vb.scSofaCosmeticFilter, config.sofaCosmeticFilter) { config.sofaCosmeticFilter = it }
        bindSofaSwitch(vb.scSofaPopupBlocker, config.sofaPopupBlocker) { config.sofaPopupBlocker = it }
        bindSofaSwitch(vb.scSofaAutoMaxQuality, config.sofaAutoMaxQuality) { config.sofaAutoMaxQuality = it }
        bindSofaSwitch(vb.scLumenStopMiniPlayer, config.lumenStopMiniPlayer) { config.lumenStopMiniPlayer = it }
        bindSofaSwitch(vb.scLumenMouseClicks, config.lumenMouseClicks) { config.lumenMouseClicks = it }
        bindSofaSwitch(vb.scSofaForceDark, config.sofaForceDark) { config.sofaForceDark = it }

        vb.scSofaCloudflareCompat.isChecked = config.sofaCloudflareCompat
        vb.scSofaCloudflareCompat.setOnCheckedChangeListener { _, isChecked ->
            config.sofaCloudflareCompat = isChecked
            Toast.makeText(context, context.getString(R.string.need_restart), Toast.LENGTH_SHORT).show()
        }

        bindSofaSwitch(vb.scSofaMatchFrameRate, config.sofaMatchFrameRate) { config.sofaMatchFrameRate = it }
        bindSofaSwitch(vb.scSofaPlayerTunneling, config.sofaPlayerTunneling) { config.sofaPlayerTunneling = it }
        bindPlainSwitch(vb.scLumenSubtitleBox, config.lumenSubtitleBox) { config.lumenSubtitleBox = it }
        bindSofaSwitch(vb.scSofaSavePasswords, config.sofaSavePasswords) { config.sofaSavePasswords = it }
        vb.btnSofaManagePasswords.setOnClickListener { SofaPasswords.showManager(activity as Activity) }
        vb.btnSofaAdblockSites.setOnClickListener { showAdBlockSitesDialog() }
        initSofaVpnUI()
        initLumenPrivacyAndDownloadsUI()

        vb.etSofaUserScript.setText(config.sofaUserScript)
        vb.btnSofaSaveUserScript.setOnClickListener {
            config.sofaUserScript = vb.etSofaUserScript.text.toString()
            Toast.makeText(context, R.string.sofa_reload_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDebandSitesDialog() {
        val sites = config.lumenDebandSites.sorted()
        val builder = AlertDialog.Builder(context).setTitle(R.string.lumen_deband_sites)
        if (sites.isEmpty()) {
            builder.setMessage(R.string.lumen_deband_sites_empty)
        } else {
            val checked = BooleanArray(sites.size) { true }
            builder.setMultiChoiceItems(sites.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            builder.setPositiveButton(R.string.ok) { _, _ ->
                config.lumenDebandSites = sites.filterIndexed { i, _ -> checked[i] }.toSet()
            }
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    private fun showAdBlockSitesDialog() {
        val sites = config.adBlockDisabledHosts.sorted()
        val builder = AlertDialog.Builder(context).setTitle(R.string.sofa_adblock_sites_title)
        if (sites.isEmpty()) {
            builder.setMessage(R.string.sofa_adblock_sites_empty)
        } else {
            val checked = BooleanArray(sites.size) { true }
            builder.setMultiChoiceItems(sites.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            builder.setPositiveButton(R.string.ok) { _, _ ->
                //unchecked sites get the ad blocker back
                config.adBlockDisabledHosts = sites.filterIndexed { i, _ -> checked[i] }.toSet()
            }
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    private fun initSofaVpnUI() {
        bindSofaSwitch(vb.scSofaVpnBrowserOnly, config.sofaVpnBrowserOnly) { config.sofaVpnBrowserOnly = it }
        vb.scSofaVpnAutoConnect.isChecked = config.sofaVpnAutoConnect
        vb.scSofaVpnAutoConnect.setOnCheckedChangeListener { _, isChecked -> config.sofaVpnAutoConnect = isChecked }

        SofaVpn.refreshStatus()
        SofaVpn.status.subscribe(activity as FragmentActivity) { status ->
            val server = SofaVpn.endpointLabel()
            vb.tvSofaVpnStatus.text = when (status) {
                SofaVpn.Status.NOT_CONFIGURED -> context.getString(R.string.sofa_vpn_status_none)
                SofaVpn.Status.DISCONNECTED -> context.getString(R.string.sofa_vpn_status_off, server ?: "")
                SofaVpn.Status.CONNECTING -> context.getString(R.string.sofa_vpn_status_connecting)
                SofaVpn.Status.CONNECTED -> context.getString(R.string.sofa_vpn_status_on, server ?: "",
                    context.getString(if (config.sofaVpnBrowserOnly) R.string.sofa_vpn_scope_browser else R.string.sofa_vpn_scope_device))
                SofaVpn.Status.ERROR -> context.getString(R.string.sofa_vpn_status_error, SofaVpn.lastError ?: "")
            }
            val configured = status != SofaVpn.Status.NOT_CONFIGURED
            vb.btnSofaVpnConnect.isEnabled = configured && status != SofaVpn.Status.CONNECTING
            vb.btnSofaVpnConnect.setText(if (status == SofaVpn.Status.CONNECTED) R.string.sofa_vpn_disconnect else R.string.sofa_vpn_connect)
            vb.btnSofaVpnRemove.isEnabled = configured
        }

        vb.btnSofaVpnConnect.setOnClickListener {
            val main = activity as? MainActivity ?: return@setOnClickListener
            if (SofaVpn.status.value == SofaVpn.Status.CONNECTED) {
                main.lifecycleScope.launch { SofaVpn.disconnect(context.applicationContext) }
            } else {
                main.requestVpnConnect()
            }
        }
        vb.btnSofaVpnImportFile.setOnClickListener { (activity as? MainActivity)?.pickVpnConfigFile() }
        vb.btnSofaVpnPaste.setOnClickListener { showVpnPasteDialog() }
        vb.btnSofaVpnRemove.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(R.string.sofa_vpn_remove)
                .setMessage(R.string.sofa_vpn_remove_q)
                .setPositiveButton(R.string.ok) { _, _ -> SofaVpn.removeConfig(context) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun initLumenPrivacyAndDownloadsUI() {
        bindPlainSwitch(vb.scLumenAutoLines, config.lumenAutoDetectLines) { config.lumenAutoDetectLines = it }
        vb.btnLumenDebandSites.setOnClickListener { showDebandSitesDialog() }
        vb.btnLumenCopyPasteAllApps.setOnClickListener { showCopyPasteAllAppsDialog() }
        vb.btnLumenBigVideo.setOnClickListener { showBigVideoDialog() }
        vb.btnLumenHandoffLog.setOnClickListener { showHandoffLog() }
        bindPlainSwitch(vb.scLumenAutoHideToolbar, config.lumenAutoHideToolbar) {
            config.lumenAutoHideToolbar = it
            if (!it) (activity as? MainActivity)?.showToolbar()
        }
        bindPlainSwitch(vb.scLumenClearHistoryOnExit, config.lumenClearHistoryOnExit) { config.lumenClearHistoryOnExit = it }
        bindPlainSwitch(vb.scLumenClearCookiesOnExit, config.lumenClearCookiesOnExit) { config.lumenClearCookiesOnExit = it }

        fun refreshFolder() {
            vb.tvLumenDownloadFolder.text = context.getString(R.string.lumen_download_folder_s,
                com.phlox.tvwebbrowser.sofa.LumenDownloads.folderLabel(context))
        }
        refreshFolder()
        vb.btnLumenChooseDownloadFolder.setOnClickListener {
            (activity as? MainActivity)?.pickDownloadFolder { refreshFolder() }
        }
        vb.btnLumenDefaultDownloadFolder.setOnClickListener {
            config.lumenDownloadFolderUri = ""
            refreshFolder()
        }

        vb.sbLumenDownloadParts.max = 15
        vb.sbLumenDownloadParts.progress = config.lumenDownloadParts - 1
        vb.tvLumenDownloadParts.text = context.getString(R.string.lumen_parts_n, config.lumenDownloadParts)
        vb.sbLumenDownloadParts.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.lumenDownloadParts = progress + 1
                vb.tvLumenDownloadParts.text = context.getString(R.string.lumen_parts_n, progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        vb.sbLumenMaxDownloads.max = 5
        vb.sbLumenMaxDownloads.progress = config.lumenMaxDownloads - 1
        vb.tvLumenMaxDownloads.text = config.lumenMaxDownloads.toString()
        vb.sbLumenMaxDownloads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.lumenMaxDownloads = progress + 1
                vb.tvLumenMaxDownloads.text = (progress + 1).toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        //KB/s: off, 0.5, 1, 2, 3, 5, 8, 12, 20, 40 MB/s
        val limits = intArrayOf(0, 512, 1024, 2048, 3072, 5120, 8192, 12288, 20480, 40960)
        fun limitText(k: Int) = if (k == 0) "no limit" else if (k < 1024) "$k KB/s" else
            (if (k % 1024 == 0) (k / 1024).toString() else String.format(java.util.Locale.US, "%.1f", k / 1024.0)) + " MB/s"
        val cur = config.lumenSpeedLimitKBps
        val idx = limits.indices.minByOrNull { kotlin.math.abs(limits[it] - cur) } ?: 0
        vb.sbLumenSpeedLimit.max = limits.size - 1
        vb.sbLumenSpeedLimit.progress = idx
        vb.tvLumenSpeedLimit.text = limitText(limits[idx])
        vb.sbLumenSpeedLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.lumenSpeedLimitKBps = limits[progress]
                vb.tvLumenSpeedLimit.text = limitText(limits[progress])
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /** What happened when the last videos were passed to a player app (to find out why a server says 403). */
    private fun showHandoffLog() {
        val proxy = com.phlox.tvwebbrowser.sofa.LumenStreamProxy
        val actions = arrayOf(
            "📋  Copy the whole log",
            "📤  Send the log to an app (Gmail, Drive, Telegram, …)",
            "💾  Save the log to Download/${com.phlox.tvwebbrowser.Brand.NAME}",
            if (proxy.forceBasic) "🔁  Network for players: basic (switch to Chrome-like)"
            else "🔁  Network for players: Chrome-like (switch to basic)",
            "🗑  Clear the log"
        )
        //newest first, one entry per line: the remote moves through them
        val lines = proxy.logText().split("\n").reversed()
        AlertDialog.Builder(context)
            .setTitle("Video hand-off log (newest first)")
            .setItems((actions.toList() + "──────────" + lines).toTypedArray()) { _, which ->
                val text = proxy.logText()
                when (which) {
                    0 -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("${com.phlox.tvwebbrowser.Brand.NAME} video log", text))
                        android.widget.Toast.makeText(context, "Log copied (${text.length} characters)", android.widget.Toast.LENGTH_LONG).show()
                    }
                    1 -> sendLog(text)
                    2 -> try {
                        val where = proxy.saveLog(context)
                        android.widget.Toast.makeText(context, "Saved: $where", android.widget.Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Couldn't save: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                    3 -> {
                        proxy.forceBasic = !proxy.forceBasic
                        android.widget.Toast.makeText(context, "Next hand-off uses the " +
                                (if (proxy.forceBasic) "basic" else "Chrome-like") + " connection", android.widget.Toast.LENGTH_LONG).show()
                    }
                    4 -> proxy.clearLog()
                }
            }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** The log as text to another app; own list of apps (many TVs have no system "share" menu). */
    private fun sendLog(text: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "${com.phlox.tvwebbrowser.Brand.NAME} video hand-off log")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(send, android.content.pm.PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
        if (apps.isEmpty()) {
            android.widget.Toast.makeText(context, "No app on this TV can receive text. Use \"Save the log\" instead.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle("Send the log with")
            .setItems(apps.map { it.loadLabel(pm).toString() }.toTypedArray()) { _, which ->
                val a = apps[which].activityInfo
                try {
                    context.startActivity(android.content.Intent(send).setClassName(a.packageName, a.name)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, e.toString(), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Where videos from web pages play. */
    private fun showBigVideoDialog() {
        val handoff = com.phlox.tvwebbrowser.sofa.SofaVideoHandoff
        val just = handoff.justPlayerPackage(context) != null
        val player = if (just) "Just Player" else "${com.phlox.tvwebbrowser.Brand.NAME}'s player"
        val values = arrayOf("", "external_all", "ask_all", "native_all", "page")
        val labels = arrayOf(
            "In the page; 4K and unsupported videos in $player (automatic)",
            if (just) "Every video in Just Player" else "Every video in a player app (asks which)",
            "Every video: ask which player each time",
            "Every video in ${com.phlox.tvwebbrowser.Brand.NAME}'s player",
            "Always in the page"
        )
        val current = config.lumenBigVideoPlayer
        val checked = values.indexOf(current).let { if (it < 0) 0 else it }
        AlertDialog.Builder(context)
            .setTitle("Videos from web pages")
            .setSingleChoiceItems(labels, checked) { d, which ->
                config.lumenBigVideoPlayer = values[which]
                d.dismiss()
            }
            //TVs: streamed videos in Lumen's player, the page still downloading them
            .setNeutralButton(if (config.lumenTvPagePlayer) "TV: ${com.phlox.tvwebbrowser.Brand.NAME}'s player for streams ✓" else "TV: ${com.phlox.tvwebbrowser.Brand.NAME}'s player for streams ✗") { _, _ ->
                config.lumenTvPagePlayer = !config.lumenTvPagePlayer
                android.widget.Toast.makeText(context, if (config.lumenTvPagePlayer)
                    "On TVs, streamed videos (HLS/DASH) open in ${com.phlox.tvwebbrowser.Brand.NAME}'s player; the page downloads them"
                    else "Streamed videos stay in the page on TVs", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Copy & paste outside the browser comes with LeanKeyboard Plus (keyboard + its accessibility service). */
    private fun showCopyPasteAllAppsDialog() {
        AlertDialog.Builder(context)
            .setTitle(R.string.lumen_copy_paste_all_apps)
            .setMessage("In ${com.phlox.tvwebbrowser.Brand.NAME}: hold OK on a web page to select text (move to select more, OK copies, " +
                    "Back cancels). Hold OK on a text field or the address bar to paste.\n\n" +
                    "In all other apps this comes with LeanKeyboard Plus (v3 or newer):\n" +
                    "• while typing: the Copy / Cut / Select / clipboard-history chips above the keys\n" +
                    "• hold OK on a text field → pastes what you copied last\n" +
                    "• hold OK anywhere else → shows the item's text to copy\n\n" +
                    "Turn on \"LeanKeyboard Plus copy & paste\" in TV Settings → Accessibility.\n" +
                    "Everything you copy in ${com.phlox.tvwebbrowser.Brand.NAME} also shows up in the keyboard's clipboard history.")
            .setPositiveButton("Open accessibility settings") { _, _ ->
                try {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "This TV has no accessibility settings screen",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bindPlainSwitch(switch: SwitchCompat, initial: Boolean, save: (Boolean) -> Unit) {
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, isChecked -> save(isChecked) }
    }

    private fun showVpnPasteDialog() {
        val edit = EditText(context)
        edit.minLines = 6
        edit.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.typeface = android.graphics.Typeface.MONOSPACE
        edit.hint = "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …"
        AlertDialog.Builder(context)
            .setTitle(R.string.sofa_vpn_paste)
            .setView(edit)
            .setPositiveButton(R.string.sofa_save) { _, _ ->
                val error = SofaVpn.importConfig(edit.text.toString())
                Toast.makeText(context,
                    if (error == null) context.getString(R.string.sofa_vpn_imported) else context.getString(R.string.sofa_vpn_bad_config, error),
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bindSofaSwitch(switch: SwitchCompat, initial: Boolean, save: (Boolean) -> Unit) {
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, isChecked ->
            save(isChecked)
            Toast.makeText(context, R.string.sofa_reload_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun initThemeSettingsUI() {
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, context.resources.getStringArray(R.array.themes))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        vb.spTheme.adapter = adapter

        vb.spTheme.setSelection(config.theme.value.ordinal, false)

        vb.spTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (config.theme.value.ordinal == position) return
                config.theme.value = Config.Theme.values()[position]
                Toast.makeText(context, context.getString(R.string.need_restart), Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun initWebViewAlgorithmicDarkeningWithDarkUiModeUI() {
        vb.scWebViewAlgorithmicDarkeningWithDarkUiMode.isChecked =
            config.webviewUseAlgorithmicDarkeningWithDarkUiMode
        vb.scWebViewAlgorithmicDarkeningWithDarkUiMode.setOnCheckedChangeListener { _, isChecked ->
            config.webviewUseAlgorithmicDarkeningWithDarkUiMode = isChecked
        }
    }

    private fun initAllowAutoplayMediaUI() {
        vb.scAllowAutoplayMedia.isChecked = config.allowAutoplayMedia
        vb.scAllowAutoplayMedia.setOnCheckedChangeListener { _, isChecked ->
            config.allowAutoplayMedia = isChecked
        }
    }

    private fun initWebEngineDebugUI() {
        vb.scWebEngineDebug.isChecked = config.webEngineDebug
        vb.scWebEngineDebug.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                config.webEngineDebug = false
                return@setOnCheckedChangeListener
            }

            AlertDialog.Builder(context)
                .setTitle(R.string.warning)
                .setMessage(R.string.web_engine_debug_warning_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    config.webEngineDebug = true
                    Toast.makeText(context, context.getString(R.string.need_restart), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    vb.scWebEngineDebug.isChecked = false
                }
                .setOnCancelListener {
                    vb.scWebEngineDebug.isChecked = false
                }
                .show()
        }
    }

    private fun initKeepScreenOnUI() {
        vb.scKeepScreenOn.isChecked = settingsModel.keepScreenOn.value

        vb.scKeepScreenOn.setOnCheckedChangeListener { buttonView, isChecked ->
            settingsModel.keepScreenOn.value = isChecked
        }
    }

    private fun initJoystickAxesNavigationUI() {
        vb.scNavigateWithJoystickAxes.isChecked = !config.disableMotionAxesDpadNavigation
        vb.scNavigateWithJoystickAxes.setOnCheckedChangeListener { _, isChecked ->
            config.disableMotionAxesDpadNavigation = !isChecked
        }
    }

    private fun initVirtualCursorPhysicsSettingsUI() {
        val minP = Config.CURSOR_PHYSICS_PERCENT_MIN
        val maxP = Config.CURSOR_PHYSICS_PERCENT_MAX
        val range = maxP - minP
        vb.sbCursorMaxSpeed.max = range
        vb.sbCursorAcceleration.max = range
        fun refreshValueLabels() {
            vb.tvCursorMaxSpeedValue.text = context.getString(R.string.cursor_physics_percent, config.cursorMaxSpeedPercent)
            vb.tvCursorAccelerationValue.text = context.getString(R.string.cursor_physics_percent, config.cursorAccelerationPercent)
        }
        vb.sbCursorMaxSpeed.progress = config.cursorMaxSpeedPercent - minP
        vb.sbCursorAcceleration.progress = config.cursorAccelerationPercent - minP
        refreshValueLabels()
        vb.sbCursorMaxSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.cursorMaxSpeedPercent = minP + progress
                refreshValueLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        vb.sbCursorAcceleration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.cursorAccelerationPercent = minP + progress
                refreshValueLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initAdBlockConfigUI() {
        vb.scAdblock.isChecked = config.adBlockEnabled
        vb.etAdBlockerListUrl.setText(config.adBlockListURL.value)
        vb.llAdblock.setOnClickListener {
            vb.scAdblock.isChecked = !vb.scAdblock.isChecked
            config.adBlockEnabled = vb.scAdblock.isChecked
            vb.llAdBlockerDetails.visibility = if (vb.scAdblock.isChecked) VISIBLE else GONE
        }
        vb.llAdBlockerDetails.visibility = if (config.adBlockEnabled) VISIBLE else GONE

        adblockModel.clientLoading.subscribe(activity as FragmentActivity) {
            updateAdBlockInfo()
        }

        vb.btnAdBlockerUpdate.setOnClickListener {
            if (adblockModel.clientLoading.value) return@setOnClickListener
            saveAdBlockListUrl()
            adblockModel.loadAdBlockList(true)
            it.isEnabled = false
        }

        updateAdBlockInfo()
    }

    private fun saveAdBlockListUrl() {
        val value = vb.etAdBlockerListUrl.text.toString().trim()
        config.adBlockListURL.value = value.ifEmpty { Config.DEFAULT_ADBLOCK_LIST_URL }
    }

    private fun updateAdBlockInfo() {
        val dateFormat = SimpleDateFormat("hh:mm dd MMMM yyyy", Locale.getDefault())
        val lastUpdate = if (config.adBlockListLastUpdate == 0L)
            context.getString(R.string.never) else
            dateFormat.format(Date(config.adBlockListLastUpdate))
        val engine = when (adblockModel.engineName) {
            "adblock-rust" -> "adblock-rust (uBlock Origin rules)"
            "basic" -> "basic engine"
            else -> "loading lists"
        }
        val infoText = "${context.getString(R.string.last_update)}: $lastUpdate · $engine"
        vb.tvAdBlockerListInfo.text = infoText
        val loadingAdBlockList = adblockModel.clientLoading.value
        vb.btnAdBlockerUpdate.visibility = if (loadingAdBlockList) View.GONE else View.VISIBLE
        vb.pbAdBlockerListLoading.visibility = if (loadingAdBlockList) View.VISIBLE else View.GONE
    }

    private fun initUAStringConfigUI(context: Context) {
        if (config.userAgentString.value?.contains("TV Bro/1.0 ") == true) {//legacy ua string - now default one should be used
            config.userAgentString.value = null
        }
        val selected = if (config.userAgentString.value == null) {
            0
        } else {
            settingsModel.uaStrings.indexOf(config.userAgentString.value ?: "")
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, settingsModel.userAgentStringTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vb.spTitles.adapter = adapter

        if (selected != -1) {
            vb.spTitles.setSelection(selected, false)
            vb.etUAString.setText(settingsModel.uaStrings[selected])
        } else {
            vb.spTitles.setSelection(settingsModel.userAgentStringTitles.size - 1, false)
            vb.llUAString.visibility = View.VISIBLE
            vb.etUAString.setText(config.userAgentString.value ?: "")
            vb.etUAString.requestFocus()
        }
        vb.spTitles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == settingsModel.userAgentStringTitles.size - 1 && vb.llUAString.visibility == View.GONE) {
                    vb.llUAString.visibility = View.VISIBLE
                    vb.llUAString.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    vb.etUAString.requestFocus()
                }
                vb.etUAString.setText(settingsModel.uaStrings[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
    }

    private fun initHomePageAndSearchEngineConfigUI() {
        var selected = 0
        if ("" != config.searchEngineURL.value) {
            selected = Config.SearchEnginesURLs.indexOf(config.searchEngineURL.value)
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, Config.SearchEnginesTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        vb.spEngine.adapter = adapter

        if (selected != -1) {
            vb.spEngine.setSelection(selected)
            vb.etUrl.setText(Config.SearchEnginesURLs[selected])
        } else {
            vb.spEngine.setSelection(Config.SearchEnginesTitles.size - 1)
            vb.llURL.visibility = View.VISIBLE
            vb.etUrl.setText(config.searchEngineURL.value)
            vb.etUrl.requestFocus()
        }
        vb.spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == (Config.SearchEnginesTitles.size - 1)) {
                    if (vb.llURL.visibility == View.GONE) {
                        vb.llURL.visibility = View.VISIBLE
                        vb.llURL.startAnimation(
                            AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
                        )
                    }
                    vb.etUrl.setText(config.searchEngineURL.value)
                    vb.etUrl.requestFocus()
                    return
                } else {
                    vb.llURL.visibility = View.GONE
                    vb.etUrl.setText(Config.SearchEnginesURLs[position])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val homePageSpinnerAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, context.resources.getStringArray(R.array.home_page_modes))
        homePageSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vb.spHomePage.adapter = homePageSpinnerAdapter
        vb.spHomePage.setSelection(settingsModel.homePageMode.ordinal)

        vb.spHomePage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val homePageMode = Config.HomePageMode.entries[position]
                vb.llCustomHomePage.visibility = if (homePageMode == Config.HomePageMode.CUSTOM) View.VISIBLE else View.GONE
                vb.llHomePageLinksMode.visibility = if (homePageMode == Config.HomePageMode.HOME_PAGE) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val homePageLinksSpinnerAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, context.resources.getStringArray(R.array.home_page_links_modes))
        homePageLinksSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vb.spHomePageLinks.adapter = homePageLinksSpinnerAdapter
        vb.spHomePageLinks.setSelection(settingsModel.homePageLinksMode.ordinal)

        vb.etCustomHomePageUrl.setText(settingsModel.homePage)
    }

    fun save() {
        val customSearchEngineUrl = vb.etUrl.text.toString()
        settingsModel.setSearchEngineURL(customSearchEngineUrl)

        val homePageMode = Config.HomePageMode.entries[vb.spHomePage.selectedItemPosition]
        val customHomePageURL = vb.etCustomHomePageUrl.text.toString()
        val homePageLinksMode = Config.HomePageLinksMode.entries[vb.spHomePageLinks.selectedItemPosition]
        settingsModel.setHomePageProperties(homePageMode, customHomePageURL, homePageLinksMode)

        val userAgent = vb.etUAString.text.toString().trim(' ')
        config.userAgentString.value = userAgent.ifEmpty { null }
        saveAdBlockListUrl()
    }
}
