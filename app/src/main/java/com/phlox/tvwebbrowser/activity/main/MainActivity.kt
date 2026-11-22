package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.util.Patterns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebStorage
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoriteEditorDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.activity.main.view.ActionBar
import com.phlox.tvwebbrowser.activity.main.view.CursorMenuView
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.BackNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.DownloadUtils
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.childs
import com.phlox.tvwebbrowser.utils.sameDay
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.system.exitProcess


open class MainActivity : AppCompatActivity(), ActionBar.Callback {
    companion object {
        private const val PREF_CLEAR_PENDING = "lumen_clear_pending"
        /** False until the first screen of this app process has loaded. */
        private var processStarted = false
        private const val LIVE_TABS = 2
        private val TAG = MainActivity::class.java.simpleName
        const val VOICE_SEARCH_REQUEST_CODE = 10001
        const val MY_PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS = 10003
        private const val PREF_ASKED_NOTIFICATIONS = "asked_notifications_for_downloads"
        const val MY_PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS = 10004
        const val PICK_FILE_REQUEST_CODE = 10005
        private const val REQUEST_CODE_HISTORY_ACTIVITY = 10006
        const val REQUEST_CODE_UNKNOWN_APP_SOURCES = 10007
        const val REQUEST_CODE_VPN_PERMISSION = 10020
        const val REQUEST_CODE_VPN_IMPORT_FILE = 10021
        const val REQUEST_CODE_PICK_DOWNLOAD_FOLDER = 10022
        const val REQUEST_CODE_DOWNLOADS = 10023
        const val REQUEST_CODE_VPN_IMPORT_ANY = 10024
        const val REQUEST_CODE_BACKUP_SAVE = 10025
        const val REQUEST_CODE_BACKUP_OPEN = 10026
        const val KEY_PROCESS_ID_TO_KILL = "proc_id_to_kill"
        private const val MY_PERMISSIONS_REQUEST_VOICE_SEARCH_PERMISSIONS = 10008
        private const val COMMON_REQUESTS_START_CODE = 10100
    }

    private lateinit var vb: ActivityMainBinding
    private lateinit var viewModel: MainActivityViewModel
    private lateinit var tabsModel: TabsModel
    private lateinit var settingsModel: SettingsModel
    private lateinit var adblockModel: AdblockModel
    private lateinit var uiHandler: Handler
    private var isFullscreen: Boolean = false
    private lateinit var prefs: SharedPreferences
    protected val config = AppContext.provideConfig()
    private val voiceSearchHelper = VoiceSearchHelper(this, VOICE_SEARCH_REQUEST_CODE,
        MY_PERMISSIONS_REQUEST_VOICE_SEARCH_PERMISSIONS)
    private var lastCommonRequestsCode = COMMON_REQUESTS_START_CODE
    private var downloadService: DownloadService? = null
    private var downloadIntent: Download? = null
    var openUrlInExternalAppDialog: AlertDialog? = null
    private var linkActionsMenu: PopupMenu? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reloadSettingsFromDisk()

        //Wisp: a fresh start (from the launcher or a link from another app, not a switch made in
        //the menu) always opens in private mode
        //Wisp: one process, always private (its data is separate and emptied by Quit, see TVBro)
        if (com.phlox.tvwebbrowser.Brand.phone && !config.incognitoMode) config.incognitoMode = true
        //Quit cleared everything already; Android kept the process, so a new session starts here.
        //The "delete the folder at the next start" mark is dropped - it would otherwise delete
        //this new session's logins whenever the process starts again later
        if (com.phlox.tvwebbrowser.Brand.phone) {
            val wp = getSharedPreferences(TVBro.WISP_PRIVATE_PREFS, Context.MODE_PRIVATE)
            if (wp.getBoolean(TVBro.WISP_WIPE_KEY, false)) wp.edit().putBoolean(TVBro.WISP_WIPE_KEY, false).apply()
        }
        val incognitoMode = config.incognitoMode
        Log.d(TAG, "onCreate incognitoMode: $incognitoMode")
        if (!com.phlox.tvwebbrowser.Brand.phone && (incognitoMode xor (this is IncognitoModeMainActivity))) {
            //keep the address a link from another app wanted to open
            switchProcess(incognitoMode, intent?.extras, intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data)
            finish()
            return
        }
        val pidToKill = intent?.getIntExtra(KEY_PROCESS_ID_TO_KILL, -1) ?: -1
        if (pidToKill != -1) {
            Process.killProcess(pidToKill)
        }

        viewModel = ActiveModelsRepository.get(MainActivityViewModel::class, this)
        if (incognitoMode && !com.phlox.tvwebbrowser.Brand.phone) {
            viewModel.prepareSwitchToIncognito()
        }
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, this)
        adblockModel = ActiveModelsRepository.get(AdblockModel::class, this)
        tabsModel = ActiveModelsRepository.get(TabsModel::class, this)
        uiHandler = Handler()
        prefs = getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        //Lumen 119: deband is gone, so are its remembered settings (sites, line detection, deinterlace)
        if (!prefs.getBoolean("lumen_deband_leftovers_cleared", false)) {
            config.lumenDebandSites = emptySet()
            config.lumenAutoDetectLines = false
            config.sofaDeinterlace = false
            prefs.edit().putBoolean("lumen_deband_leftovers_cleared", true).apply()
        }
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)
        com.phlox.tvwebbrowser.sofa.LumenCrashLog.showIfAny(this)
        if (com.phlox.tvwebbrowser.Brand.phone) {
            com.phlox.tvwebbrowser.sofa.WispLog.tabCount = { tabsModel.tabsStates.size }
            com.phlox.tvwebbrowser.sofa.WispLog.showClosedNoticeIfAny(this)
        }
        if (TVBro.instance.repairDone) {
            TVBro.instance.repairDone = false
            Toast.makeText(this, "Web engine repaired. If videos still break up, restart the TV completely.", Toast.LENGTH_LONG).show()
        }
        //get Chrome's network stack from Google Play services ready for the video player (Lumen only)
        com.phlox.tvwebbrowser.sofa.CronetSupport.installProvider(this)
        vb.vToolbarCursor.host = object : com.phlox.tvwebbrowser.activity.main.view.ToolbarCursorView.Host {
            override fun toolbarBottom(): Int = (vb.rlActionBar.top + vb.rlActionBar.height + vb.rlActionBar.translationY).toInt()
            override fun onLeaveToPage(x: Float, dirX: Int, dirY: Int, speedX: Float, speedY: Float) =
                returnPointerToPage(x, dirX, dirY, speedX, speedY)
            override fun onCursorClosed() = returnPointerToPage(null)
            override fun touchTarget(): android.view.ViewGroup = vb.rlRoot
            override fun onLongPress(x: Float, y: Float): Boolean = pasteIntoFieldAt(x, y)
        }
        //pushed against the bottom of a page that can't scroll (a player page): the bar goes away,
        //so the page gets the whole screen (and tall player menus fit)
        vb.flWebViewContainer.cursorDrawerDelegate.bottomEdgeCallback = {
            val view = tabsModel.currentTab.value?.webEngine?.getView()
            if (toolbarShown && canAutoHideToolbar() && view != null && !view.canScrollVertically(1)) {
                autoHideToolbar()
            }
        }
        //the page pointer pushed against the top of a page that can't scroll further up:
        //it carries straight on over the top bar (which slides in), no extra key press
        vb.flWebViewContainer.cursorDrawerDelegate.topEdgeCallback = { x, speedX, speedY, dirX ->
            val engine = tabsModel.currentTab.value?.webEngine
            if (engine == null || isFullscreen || vb.vToolbarCursor.active || vb.rlActionBar.hasFocus() ||
                !pageStoppedAtTop(engine)) {
                false
            } else {
                enterToolbarPointer(x, dirX, speedX, speedY)
                true
            }
        }
        vb.rlActionBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && toolbarAnimator == null && !isFullscreen) applyToolbarLayout(toolbarShown)
        }

        vb.ivMiniatures.visibility = View.INVISIBLE
        //Lumen: the top bar (address bar, buttons and tabs) is always visible
        vb.progressBar.visibility = View.GONE

        vb.vTabs.listener = tabsListener

        vb.ibAdBlock.setOnClickListener { showAdBlockOptions() }
        vb.ibDesktopSite.setOnClickListener { toggleDesktopSite() }
        vb.ibGrabLinks.setOnClickListener { showGrabbedLinks() }
        vb.ibDeband.setOnClickListener { showDebandMenu() }
        vb.ibPopupBlock.setOnClickListener { lifecycleScope.launch(Dispatchers.Main) { showPopupBlockOptions() } }
        vb.ibBack.setOnClickListener { navigateBack() }
        vb.ibForward.setOnClickListener {
            val tab = tabsModel.currentTab.value ?: return@setOnClickListener
            if (tab.webEngine.canGoForward()) {
                tab.webEngine.goForward()
            }
        }
        vb.ibRefresh.setOnClickListener { refresh() }
        vb.ibCloseTab.setOnClickListener { tabsModel.currentTab.value?.apply { closeTab(this) } }

        vb.vActionBar.callback = this
        if (com.phlox.tvwebbrowser.Brand.phone) setupPhoneUi()


        config.userAgentString.subscribe(this.lifecycle, false) {
            for (tab in tabsModel.tabsStates) {
                tab.webEngine.userAgentString = it
            }
        }

        config.theme.subscribe(this.lifecycle, false) {
            when (it) {
                Config.Theme.BLACK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                Config.Theme.WHITE -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            WebEngineFactory.onThemeSettingUpdated(it)
        }

        settingsModel.keepScreenOn.subscribe(this.lifecycle) {
            if (it) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        viewModel.homePageLinks.subscribe(this) {
            Log.i(TAG, "homePageLinks updated")
            val currentUrl = tabsModel.currentTab.value?.url ?: return@subscribe
            if (Config.HOME_PAGE_URL == currentUrl) {
                tabsModel.currentTab.value?.webEngine?.reload()
            }
        }

        tabsModel.currentTab.subscribe(this) {
            vb.vActionBar.setAddressBoxText(it?.url ?: "")
            it?.let {
                onWebViewUpdated(it)
            }
        }

        tabsModel.tabsStates.subscribe(this, false) {
            if (it.isEmpty()) {
                if (!config.isWebEngineGecko()) {
                    vb.flWebViewContainer.removeAllViews()
                }
            }
        }

        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        loadState()


        //Lumen: WireGuard auto-connect
        com.phlox.tvwebbrowser.sofa.SofaVpn.refreshStatus()
        if (config.sofaVpnAutoConnect && com.phlox.tvwebbrowser.sofa.SofaVpn.hasConfig() &&
            com.phlox.tvwebbrowser.sofa.SofaVpn.permissionIntent(this) == null &&
            com.phlox.tvwebbrowser.sofa.SofaVpn.status.value != com.phlox.tvwebbrowser.sofa.SofaVpn.Status.CONNECTED) {
            lifecycleScope.launch { com.phlox.tvwebbrowser.sofa.SofaVpn.connect(applicationContext) }
        }
    }

    private var touchMenuButton: View? = null

    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun addTouchMenuButton() {
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
        val margin = (16 * density).toInt()
        val button = android.widget.ImageButton(this)
        button.setImageResource(R.drawable.ic_menu_24)
        button.setColorFilter(Color.WHITE)
        button.contentDescription = getString(R.string.sofa_menu)
        button.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xDD2A2A30.toInt())
        }
        button.elevation = 8 * density
        button.alpha = 0.9f
        button.setOnClickListener { toggleMenu() }
        val lp = android.widget.RelativeLayout.LayoutParams(size, size)
        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
        lp.setMargins(margin, margin, margin, margin)
        vb.rlRoot.addView(button, lp)
        touchMenuButton = button
    }

    // ---------------- Wisp (phone build): phone bar, ⋮ menu, tab list, site info, privacy ----------------

    /** Turns Lumen's TV layout into Wisp's: one slim bar at the top, no cursor, no tab strip. */
    private fun setupPhoneUi() {
        val dp = resources.displayMetrics.density
        vb.vToolbarCursor.visibility = View.GONE
        vb.flWebViewContainer.cursorEnabled = false
        vb.vTabs.visibility = View.GONE
        vb.llBottomPanel.visibility = View.GONE
        vb.progressBarGeneric.visibility = View.GONE
        val bar = vb.rlActionBar
        bar.setBackgroundColor(ContextCompat.getColor(this,
            if (config.incognitoMode) R.color.wisp_bar_private else R.color.wisp_bar))
        bar.elevation = 3 * dp
        //status bar in the bar's colour, so the top of the screen is one piece
        window.statusBarColor = ContextCompat.getColor(this,
            if (config.incognitoMode) R.color.wisp_bar_private else R.color.wisp_bar)
        //page loading: a thin line right under the bar
        (vb.progressBar.layoutParams as android.widget.RelativeLayout.LayoutParams).let {
            it.height = (3f * dp).toInt()
            vb.progressBar.layoutParams = it
        }
        //a clear colour that shows on light and dark pages alike
        vb.progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.wisp_progress))
        vb.progressBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        //the keyboard pushes the bar up while typing an address
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        //the app places itself around the status/navigation bars (and the camera notch), so that a
        //full-screen video can really use the whole screen: before, the system kept the notch's
        //strip free and it showed as a white bar above the video (also after rotating)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(vb.rlRoot) { v, insets ->
            if (isFullscreen) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            //the keyboard was closed (Back, or its own button) while typing an address: typing is
            //over - the address and the bar's buttons come back (before, the bar stayed in typing
            //mode without buttons)
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboardWasVisible && !imeVisible && hasWindowFocus() &&
                android.os.SystemClock.uptimeMillis() - lastConfigChange > 1500) {
                //only when the user closed it: a sheet opening (search engine list), turning the
                //phone or leaving the app also hide the keyboard - the typed text must stay then
                vb.vActionBar.postDelayed({
                    val stillHidden = ViewCompat.getRootWindowInsets(vb.rlRoot)?.isVisible(WindowInsetsCompat.Type.ime()) != true
                    if (stillHidden && hasWindowFocus() && vb.vActionBar.isAddressEditing()) vb.vActionBar.endAddressEditing()
                }, 300)
                scheduleUiCheck("keyboard closed", 900)
            }
            keyboardWasVisible = imeVisible
            WindowInsetsCompat.CONSUMED
        }
        vb.rlRoot.setBackgroundColor(ContextCompat.getColor(this, R.color.wisp_bar))
        updatePhoneTabCount()
        tabsModel.tabsStates.subscribe(this, false) { updatePhoneTabCount() }
        vb.vActionBar.refreshSiteButton()
    }

    private var engineIconCache: Pair<String, Bitmap?>? = null
    /** Wisp: when the page script last reported a long press (it beats the WebView's own report). */
    private var lastScriptLongPress = 0L
    private var linkSheet: android.app.Dialog? = null

    override fun phoneEngineIcon(): Bitmap? {
        val name = config.guessSearchEngineName()
        engineIconCache?.let { if (it.first == name) return it.second }
        val bmp = try {
            assets.open("pages/home/favicons/$name.png").use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null }
        engineIconCache = Pair(name, bmp)
        return bmp
    }

    /** Wisp: choose the search engine (the button left of the address on the start page / while typing). */
    override fun showPhoneEngines() {
        val current = config.guessSearchEngineName()
        val items = Config.SearchEnginesNames.mapIndexed { i, name ->
            val icon = try {
                assets.open("pages/home/favicons/$name.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            WispSheets.MenuItem(R.drawable.ic_wisp_find, Config.SearchEnginesTitles[i],
                subtitle = if (name == "custom" && current == "custom") config.searchEngineURL.value else null,
                badge = if (name == current) "✓" else null, iconBitmap = icon) {
                if (name == "custom") askCustomSearchEngine() else setSearchEngine(Config.SearchEnginesURLs[i])
            }
        }
        WispSheets.showList(this, getString(R.string.wisp_search_engine), null, listOf(items))
    }

    private fun setSearchEngine(url: String) {
        config.searchEngineURL.value = url
        engineIconCache = null
        vb.vActionBar.refreshSiteButton()
    }

    private fun askCustomSearchEngine() {
        val input = android.widget.EditText(this)
        input.isSingleLine = true
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        input.hint = "https://example.com/search?q=[query]"
        if (config.guessSearchEngineName() == "custom") input.setText(config.searchEngineURL.value)
        val box = android.widget.FrameLayout(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.wisp_search_engine)
            .setMessage(getString(R.string.wisp_custom_engine_hint, "[query]"))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http") && url.contains("[query]")) setSearchEngine(url)
                else Toast.makeText(this, getString(R.string.wisp_custom_engine_hint, "[query]"), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun phoneReload(stop: Boolean) {
        val engine = tabsModel.currentTab.value?.webEngine ?: return
        if (stop) {
            (engine.getView() as? android.webkit.WebView)?.stopLoading()
            vb.vActionBar.setLoading(false)
        } else refresh()
    }

    /**
     * The browser runs in two processes (normal and private mode) that share one settings file.
     * Each keeps a copy in memory, and saving any setting writes its whole copy: a process with an
     * old copy (e.g. the normal one, kept alive by Android) wrote back old values - settings made
     * in private mode came back ("Desktop site" on again, switches reset). Re-reading the file
     * before working with it keeps both copies current.
     */
    private fun reloadSettingsFromDisk() {
        try {
            @Suppress("DEPRECATION")
            getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_MULTI_PROCESS)
        } catch (e: Exception) {
        }
    }

    /** Wisp: reader view - the article only, in a readable size (Mozilla Readability). */
    private fun showReader() {
        val tab = tabsModel.currentTab.value ?: return
        val wv = tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx ?: return
        val pageUrl = tab.url
        WispPages.extract(this, wv) { article ->
            if (article == null) {
                Toast.makeText(this, R.string.wisp_reader_none, Toast.LENGTH_SHORT).show()
                return@extract
            }
            wv.showReaderPage(WispPages.readerHtml(article, pageUrl, config.wispReaderFont), pageUrl)
        }
    }

    /** Wisp: keeps the page to read without internet (pictures and text; scripts don't run there). */
    private fun savePageOffline() {
        val tab = tabsModel.currentTab.value ?: return
        val wv = tab.webEngine.getView() as? android.webkit.WebView ?: return
        WispPages.save(this, wv, tab.title, tab.url) { ok ->
            Toast.makeText(this, if (ok) R.string.wisp_saved_offline else R.string.wisp_save_offline_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSavedPages() {
        val pages = WispPages.list(this)
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.wisp_no_saved_pages, Toast.LENGTH_LONG).show()
            return
        }
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        val items = pages.map { p ->
            val host = try { Uri.parse(p.url).host?.removePrefix("www.") } catch (e: Exception) { null } ?: ""
            WispSheets.MenuItem(R.drawable.ic_wisp_offline, p.title, subtitle = host + " · " + fmt.format(java.util.Date(p.time))) {
                val open = WispSheets.MenuItem(R.drawable.ic_wisp_open_new, getString(R.string.wisp_open)) { openSavedPage(p) }
                val online = WispSheets.MenuItem(R.drawable.ic_wisp_globe, getString(R.string.wisp_open_online)) {
                    openInNewTab(p.url, tabsModel.tabsStates.size, needToHideMenuOverlay = false, navigateImmediately = true)
                }
                val del = WispSheets.MenuItem(R.drawable.ic_delete_grey_900_36dp, getString(R.string.wisp_delete)) {
                    WispPages.delete(this, p)
                    Toast.makeText(this, R.string.wisp_deleted, Toast.LENGTH_SHORT).show()
                }
                WispSheets.showList(this, p.title, p.url, listOf(listOf(open, online), listOf(del)))
            }
        }
        WispSheets.showList(this, getString(R.string.wisp_saved_pages), null, listOf(items))
    }

    private fun openSavedPage(p: WispPages.Saved) {
        val tab = tabsModel.currentTab.value
        val wv = tab?.webEngine?.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        if (tab == null || wv == null || !isOnHomePage(tab)) {
            //a new tab, so the page shown stays (its WebView opens the file itself, see WebViewEx.loadUrl)
            openInNewTab(Uri.fromFile(java.io.File(p.file)).toString(), tabsModel.tabsStates.size,
                needToHideMenuOverlay = false, navigateImmediately = true)
        } else wv.openSavedPage(p.file)
    }

    private var keyboardWasVisible = false
    private var lastConfigChange = 0L

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        lastConfigChange = android.os.SystemClock.uptimeMillis()
        if (com.phlox.tvwebbrowser.Brand.phone) scheduleUiCheck("turned", 1200)
    }

    /** Wisp: search engine picked in Settings: the logo left of the address follows. */
    fun refreshEngineButton() {
        engineIconCache = null
        vb.vActionBar.refreshSiteButton()
    }

    /** Wisp: the tab counter counts tabs with a site open; a tab on the start page doesn't count. */
    /** Diagnostics, a moment after a page loaded: the address bar and the ad blocker agree with the page? */
    private fun checkPageConsistency(tab: WebTabState) {
        vb.rlRoot.postDelayed({
            if (tabsModel.currentTab.value != tab || isOnHomePage(tab)) return@postDelayed
            val real = tab.webEngine.url ?: return@postDelayed
            if (!real.startsWith("http")) return@postDelayed
            val log = com.phlox.tvwebbrowser.sofa.WispLog
            if (!vb.vActionBar.isAddressEditing()) {
                val held = vb.vActionBar.heldAddress()
                fun norm(u: String) = u.substringBefore('#').removeSuffix("/")
                if (norm(held) != norm(real)) log.mismatch("address bar vs page",
                    "bar has ${log.site(held)}${if (log.site(held) == log.site(real)) " (other path)" else ""}, page is ${log.site(real)}")
            }
            if (!isAdBlockOnForTab(tab) && tab.blockedAds > 0) log.mismatch("ad blocker switch vs page",
                "${log.site(real)}: ad blocker off but ${tab.blockedAds} requests were blocked")
            uiSelfCheck("page loaded")
        }, 1500)
    }

    // ---------------- Wisp: UI self-checks and problem reports ----------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        //back from a sheet, dialog, settings or another app
        if (hasFocus && com.phlox.tvwebbrowser.Brand.phone) scheduleUiCheck("back on screen", 700)
    }

    private val uiCheckRunnable = Runnable { uiSelfCheck(pendingUiCheckReason) }
    private var pendingUiCheckReason = ""

    private fun scheduleUiCheck(reason: String, delay: Long) {
        pendingUiCheckReason = reason
        vb.rlRoot.removeCallbacks(uiCheckRunnable)
        vb.rlRoot.postDelayed(uiCheckRunnable, delay)
    }

    private val uiSnapshotAt = HashMap<String, Long>()

    private fun uiProblem(kind: String, detail: String, reason: String) {
        val log = com.phlox.tvwebbrowser.sofa.WispLog
        log.mismatch("UI: $kind", "$detail (after: $reason)")
        //the layout as text (no picture) once per kind every 10 minutes, for the report
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (uiSnapshotAt[kind] ?: -900_000L) > 600_000L) {
            uiSnapshotAt[kind] = now
            com.phlox.tvwebbrowser.sofa.WispSnapshot.save(this, "ui", "UI CHECK $kind: $detail (after: $reason)", null)
        }
    }

    /** Wisp's own screen, checked for the UI bugs it had (or could have). Problems go to the log. */
    private fun uiSelfCheck(reason: String) {
        if (!com.phlox.tvwebbrowser.Brand.phone || isFinishing || !hasWindowFocus()) return
        try {
            val ins = ViewCompat.getRootWindowInsets(vb.rlRoot)
            val statusTop = if (ins?.isVisible(WindowInsetsCompat.Type.statusBars()) == true)
                ins.getInsets(WindowInsetsCompat.Type.statusBars()).top else 0
            val screenH = vb.rlRoot.rootView.height
            val bar = vb.rlActionBar
            val loc = IntArray(2)
            bar.getLocationOnScreen(loc)
            if (isFullscreen) {
                if (ins?.isVisible(WindowInsetsCompat.Type.statusBars()) == true)
                    uiProblem("status bar in fullscreen", "the status bar is still shown (${statusTop} px) during fullscreen", reason)
                val r = IntArray(2)
                vb.rlRoot.getLocationOnScreen(r)
                if (r[1] > 2) uiProblem("gap above fullscreen", "fullscreen starts ${r[1]} px below the top", reason)
                return
            }
            val moving = toolbarAnimator != null || android.os.SystemClock.uptimeMillis() < toolbarMovingUntil
            if (toolbarShown && !moving) {
                when {
                    bar.visibility != View.VISIBLE -> uiProblem("toolbar missing", "toolbar should show but is ${if (bar.visibility == View.GONE) "gone" else "invisible"}", reason)
                    bar.height <= 0 -> uiProblem("toolbar missing", "toolbar has no height", reason)
                    kotlin.math.abs(bar.translationY) > 2f -> uiProblem("toolbar moved", "toolbar should show but is moved by ${bar.translationY.toInt()} px", reason)
                    loc[1] + bar.height <= 0 || loc[1] >= screenH -> uiProblem("toolbar off screen", "toolbar at y=${loc[1]}, screen ${screenH} px high", reason)
                    loc[1] < statusTop - 2 -> uiProblem("toolbar under the status bar", "toolbar top ${loc[1]} px, status bar ${statusTop} px", reason)
                }
            }
            if (!toolbarShown && !moving && !canAutoHideToolbar())
                uiProblem("toolbar hidden", "toolbar hidden although it can't auto-hide here", reason)
            //the bar's parts: visible ones must have a size
            if (toolbarShown && vb.vActionBar.visibility == View.VISIBLE) {
                val parts = listOf("address" to findViewById<View>(R.id.etUrl), "menu" to findViewById<View>(R.id.ibMenu),
                    "tabs" to findViewById<View>(R.id.tvTabCount), "reload" to findViewById<View>(R.id.ibReload),
                    "site button" to findViewById<View>(R.id.ibSiteData))
                for ((name, v) in parts) {
                    if (v != null && v.isShown && (v.width <= 0 || v.height <= 0))
                        uiProblem("zero-size button", "$name is shown with size ${v.width}x${v.height}", reason)
                }
                val url = findViewById<View>(R.id.etUrl)
                if (url != null && url.isShown && url.width in 1 until (24 * resources.displayMetrics.density).toInt())
                    uiProblem("address bar squeezed", "address field only ${url.width} px wide", reason)
            }
            //typing mode without a keyboard (the bar loses its buttons): looked at again 2 s later
            if (vb.vActionBar.isAddressEditing() && ins?.isVisible(WindowInsetsCompat.Type.ime()) != true) {
                vb.rlRoot.postDelayed({
                    val again = ViewCompat.getRootWindowInsets(vb.rlRoot)
                    if (hasWindowFocus() && vb.vActionBar.isAddressEditing() && again?.isVisible(WindowInsetsCompat.Type.ime()) != true)
                        uiProblem("typing mode without keyboard", "address bar in typing mode, keyboard closed", reason)
                }, 2000)
            }
            //the page area
            val page = tabsModel.currentTab.value?.webEngine?.getView()
            if (page != null && page.isAttachedToWindow && (page.width <= 0 || page.height <= 0))
                uiProblem("page area empty", "web page has size ${page.width}x${page.height}", reason)
        } catch (e: Throwable) {
            com.phlox.tvwebbrowser.sofa.WispLog.error("ui", "self-check failed", e)
        }
    }

    /** Report a problem: a picture of the screen as it is now, the layout, then the person's words. */
    fun reportProblem(over: android.view.Window? = null, after: (() -> Unit)? = null) {
        com.phlox.tvwebbrowser.sofa.WispSnapshot.capture(this) { bmp -> showReportDialog(bmp, over, after) }
    }

    private fun showReportDialog(picture: Bitmap?, over: android.view.Window?, after: (() -> Unit)?) {
        val cats = listOf(R.string.wisp_diag_cat_crash, R.string.wisp_diag_cat_wrong, R.string.wisp_diag_cat_slow,
            R.string.wisp_diag_cat_looks).map { getString(it) }
        var picked = 1
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(pad, pad / 5, pad, 0)
        val group = android.widget.RadioGroup(this)
        cats.forEachIndexed { i, label ->
            val rb = android.widget.RadioButton(this)
            rb.text = label
            rb.id = 1000 + i
            group.addView(rb)
        }
        group.check(1000 + picked)
        group.setOnCheckedChangeListener { _, id -> picked = id - 1000 }
        box.addView(group)
        val edit = android.widget.EditText(this)
        edit.hint = getString(R.string.wisp_diag_report_hint)
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        edit.maxLines = 4
        box.addView(edit)
        if (picture != null) {
            val note = TextView(this)
            note.setText(R.string.wisp_diag_report_picture)
            note.textSize = 12f
            note.setPadding(0, pad / 3, 0, 0)
            box.addView(note)
        }
        var sent = false
        AlertDialog.Builder(this)
            .setTitle(R.string.wisp_diag_report)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                sent = true
                val log = com.phlox.tvwebbrowser.sofa.WispLog
                val cat = cats.getOrElse(picked) { cats[1] }
                val text = edit.text.toString().trim()
                log.userReport(this, cat, text)
                com.phlox.tvwebbrowser.sofa.WispSnapshot.save(this, "report",
                    "REPORT $cat: $text\n${log.versionLine(this)}\n${log.memoryLine(this)}", picture, over) { name ->
                    log.event("report", "saved $name (" + (if (picture != null) "picture + " else "") + "layout)")
                    log.copyReport(this)
                    Toast.makeText(this, R.string.wisp_diag_reported, Toast.LENGTH_LONG).show()
                    after?.invoke()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!sent) picture?.recycle() }
            .show()
    }

    private fun updatePhoneTabCount() {
        if (!com.phlox.tvwebbrowser.Brand.phone) return
        vb.vActionBar.setTabCount(tabsModel.tabsStates.count { !isOnHomePage(it) })
    }

    override fun showPhoneMenu() {
        val tab = tabsModel.currentTab.value
        val engine = tab?.webEngine
        val url = tab?.url ?: ""
        val onWeb = tab != null && url.startsWith("http") && !isOnHomePage(tab)
        val host = if (onWeb) try { Uri.parse(url).host } catch (e: Exception) { null } else null
        val webView = engine?.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        val mediaCount = webView?.mediaLog?.size ?: 0
        fun count(n: Int) = if (n <= 0) null else if (n > 99) "99+" else n.toString()
        val top = listOf(
            WispSheets.Action(R.drawable.ic_wisp_back, getString(R.string.wisp_back), enabled = tab != null) { navigateBack() },
            WispSheets.Action(R.drawable.ic_wisp_forward, getString(R.string.wisp_forward),
                enabled = engine?.canGoForward() == true) {
                engine?.let { if (it.canGoForward()) it.goForward() }
            },
            WispSheets.Action(R.drawable.ic_wisp_star_add, getString(R.string.wisp_bookmark), enabled = onWeb) {
                tab?.let { addBookmark(it) }
            },
            WispSheets.Action(R.drawable.ic_wisp_share, getString(R.string.wisp_share), enabled = onWeb) { shareAddress(url) }
        )
        val adOn = tab != null && isAdBlockOnForTab(tab)
        val main = listOf(
            WispSheets.MenuItem(R.drawable.ic_wisp_reader, getString(R.string.wisp_reader), enabled = onWeb) { showReader() },
            WispSheets.MenuItem(R.drawable.ic_wisp_find, getString(R.string.wisp_find_in_page), enabled = onWeb) { showFindInPage() },
            WispSheets.MenuItem(R.drawable.ic_desktop_site, getString(R.string.wisp_desktop), enabled = onWeb,
                toggle = onWeb && config.isDesktopSite(host)) { toggleDesktopSite() },
            WispSheets.MenuItem(R.drawable.ic_wisp_block, getString(R.string.wisp_ad_block), enabled = tab != null,
                subtitle = if (adOn && (tab?.blockedAds ?: 0) > 0) resources.getQuantityString(R.plurals.wisp_blocked_n,
                    tab!!.blockedAds, tab.blockedAds) else null,
                toggle = adOn) { showAdBlockOptions() }
        )
        val more = listOf(
            WispSheets.MenuItem(R.drawable.ic_wisp_offline, getString(R.string.wisp_save_offline), enabled = onWeb) { savePageOffline() },
            WispSheets.MenuItem(R.drawable.ic_wisp_offline, getString(R.string.wisp_saved_pages),
                badge = WispPages.list(this).size.takeIf { it > 0 }?.toString()) { showSavedPages() },
            WispSheets.MenuItem(R.drawable.ic_wisp_popup, getString(R.string.wisp_popups), enabled = tab != null,
                badge = count(tab?.blockedPopups ?: 0)) {
                lifecycleScope.launch(Dispatchers.Main) { showPopupBlockOptions() }
            },
            WispSheets.MenuItem(R.drawable.ic_grab_links, getString(R.string.wisp_media), enabled = onWeb,
                badge = count(mediaCount)) { showGrabbedLinks() },
            WispSheets.MenuItem(R.drawable.ic_wisp_gif, getString(R.string.wisp_gif_tool)) { WispGif.openPicker(this) },
            WispSheets.MenuItem(R.drawable.ic_wisp_add_home, getString(R.string.wisp_add_to_home), enabled = onWeb) {
                tab?.let { addToHomeScreen(it) }
            },
            //a picture of the screen as it is (once the menu has slid away) goes with the report
            WispSheets.MenuItem(R.drawable.ic_wisp_diag, getString(R.string.wisp_diag_report)) {
                vb.rlRoot.postDelayed({ reportProblem() }, 450)
            }
        )
        val tiles = listOf(
            WispSheets.Action(R.drawable.ic_history_grey_900_36dp, getString(R.string.wisp_history)) { showHistory() },
            WispSheets.Action(R.drawable.ic_star_border_grey_900_36dp, getString(R.string.wisp_bookmarks)) { showFavorites() },
            WispSheets.Action(R.drawable.ic_wisp_download, getString(R.string.wisp_downloads)) { showDownloads() },
            WispSheets.Action(R.drawable.ic_wisp_key, getString(R.string.wisp_passwords)) { openPasswordManager() }
        )
        WispSheets.showMenu(this, host?.removePrefix("www."), top, main, more, tiles,
            settings = WispSheets.MenuItem(R.drawable.ic_settings_grey_900_24dp, getString(R.string.wisp_settings)) { showSettings() },
            quit = WispSheets.MenuItem(R.drawable.ic_wisp_power, getString(R.string.wisp_quit), danger = true) { closeWindow() })
    }

    /** Wisp: the + next to the address bar - a new tab with the keyboard ready, like Fennec. */
    override fun showPhoneNewTab() {
        closeFindInPage()
        tabsModel.currentTab.value?.let { captureThumbnail(it) }
        openInNewTab(settingsModel.homePage, tabsModel.tabsStates.size, navigateImmediately = true)
        vb.rlRoot.postDelayed({ vb.vActionBar.focusAddressBar() }, 250)
    }

    /** A small picture of the tab's page for the tab grid (kept in memory only). */
    private fun captureThumbnail(tab: WebTabState) {
        if (isOnHomePage(tab)) { tab.thumbnail = null; return }
        val v = tab.webEngine.getView() ?: return
        if (v.width <= 0 || v.height <= 0 || !v.isAttachedToWindow) return
        try {
            val scale = 0.33f
            val w = (v.width * scale).toInt().coerceAtLeast(1)
            val h = (w * 1.15f).toInt().coerceAtMost((v.height * scale).toInt()).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(Color.WHITE)
            c.scale(scale, scale)
            c.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
            v.draw(c)
            tab.thumbnail = bmp
        } catch (e: Throwable) {
            Log.e(TAG, "tab preview", e)
        }
    }

    override fun showPhoneTabs() {
        closeFindInPage()
        val current = tabsModel.currentTab.value
        current?.let { captureThumbnail(it) }
        val tabs = ArrayList(tabsModel.tabsStates)
        val cards = tabs.map { t ->
            val home = isOnHomePage(t)
            val address = if (home) "" else try { Uri.parse(t.url).host?.removePrefix("www.") ?: t.url } catch (e: Exception) { t.url }
            val title = when {
                home -> getString(R.string.wisp_start_page)
                t.title.isNotBlank() -> t.title
                else -> address
            }
            WispSheets.TabCard(title, address, t == current, if (home) null else t.thumbnail, home)
        }
        //diagnostics: the list and the tab button must say the same
        val listed = cards.count { !it.home }
        val shown = vb.vActionBar.shownTabCount()
        if (shown != listed.toString() && !(listed > 99 && shown == ":D"))
            com.phlox.tvwebbrowser.sofa.WispLog.mismatch("tab button vs tab list", "button shows $shown, list has $listed")
        val (_, setIcon) = WispSheets.showTabs(this, config.incognitoMode, cards,
            onPick = { i ->
                tabs.getOrNull(i)?.let { t ->
                    if (tabsModel.tabsStates.contains(t)) {
                        if (!t.selected) changeTab(t)
                        vb.vTabs.current = tabsModel.tabsStates.indexOf(t)
                    }
                }
            },
            onClose = { i -> tabs.getOrNull(i)?.let {
                val title = it.title.ifBlank { it.url }
                val counts = !isOnHomePage(it)
                closeTab(it)
                if (counts) showUndoBar(getString(R.string.wisp_tab_closed_s, title))
            } },
            onRecent = { showRecentlyClosed() },
            onNew = { showPhoneNewTab() },
            onCloseAll = {
                val all = ArrayList(tabsModel.tabsStates)
                val keep = tabsModel.currentTab.value
                for (t in all) if (t != keep) closeTab(t)
                keep?.let { closeTab(it) } //the last one leaves a fresh start page tab
            })
        tabs.forEachIndexed { i, t ->
            if (isOnHomePage(t) || !t.url.startsWith("http")) return@forEachIndexed
            lifecycleScope.launch(Dispatchers.Main) {
                setIcon(i, com.phlox.tvwebbrowser.singleton.FaviconsPool.get(t.url))
            }
        }
    }

    private fun addBookmark(tab: WebTabState) {
        val item = FavoriteItem()
        item.title = tab.title.ifBlank { tab.url }
        item.url = tab.url
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                AppDatabase.db.favoritesDao().insert(item)
                viewModel.refreshHomePageLinks()
                Toast.makeText(this@MainActivity, R.string.wisp_bookmark_added, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "add bookmark", e)
            }
        }
    }

    private fun shareAddress(url: String) {
        val share = Intent(Intent.ACTION_SEND)
        share.type = "text/plain"
        share.putExtra(Intent.EXTRA_TEXT, url)
        try {
            startActivity(Intent.createChooser(share, getString(R.string.wisp_share)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    /** Wisp: Chrome-style site info - security, the site's settings, and deleting its data. */
    private fun showPhoneSiteInfo(tab: WebTabState) {
        val uri = try { Uri.parse(tab.url) } catch (e: Exception) { null }
        val host = uri?.host
        if (uri == null || host.isNullOrEmpty() || uri.scheme?.startsWith("http") != true || isOnHomePage(tab)) {
            Toast.makeText(this, R.string.wisp_site_info_none, Toast.LENGTH_SHORT).show()
            return
        }
        val site = Config.siteKey(host)
        val origin = uri.scheme + "://" + host + (if (uri.port > 0) ":" + uri.port else "")
        val secure = "https".equals(uri.scheme, ignoreCase = true)
        val wv = tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        val cookies = try {
            CookieManager.getInstance().getCookie(tab.url)?.split(';')?.count { it.isNotBlank() } ?: 0
        } catch (e: Exception) { 0 }
        fun show(storage: Long) {
            val size = if (storage >= 0) android.text.format.Formatter.formatShortFileSize(this, storage) else "?"
            val hiddenCount = config.wispHiddenFor(host).size
            val rows = listOfNotNull(
                WispSheets.Row(R.drawable.ic_wisp_block, getString(R.string.wisp_site_adblock),
                    switchOn = config.adBlockEnabled && !config.isAdBlockDisabledForHost(host), enabled = config.adBlockEnabled) { on ->
                    val sites = config.adBlockDisabledHosts.toMutableSet()
                    //the whole site (as Config.isAdBlockDisabledForHost reads it on the phone)
                    val base = Config.baseSite(host)
                    sites.removeAll { Config.baseSite(it) == base }
                    if (!on) sites.add(base)
                    config.adBlockDisabledHosts = sites
                    tab.adblock = null
                    tab.webEngine.onUpdateAdblockSetting(isAdBlockOnForTab(tab))
                    onWebViewUpdated(tab)
                    refresh()
                },
                WispSheets.Row(R.drawable.ic_wisp_key, getString(R.string.wisp_stay_logged_in),
                    subtitle = getString(R.string.wisp_stay_logged_in_sum), switchOn = config.isKeepSite(host)) { on ->
                    val base = Config.baseSite(host)
                    config.wispKeepSites = config.wispKeepSites.filterNot { Config.baseSite(it) == base }.toSet() +
                            (if (on) setOf(base) else emptySet())
                },
                WispSheets.Row(R.drawable.ic_wisp_cookie, getString(R.string.wisp_site_3p_cookies),
                    subtitle = getString(R.string.wisp_site_3p_cookies_desc),
                    switchOn = !config.wispBlockThirdPartyCookies || config.isThirdPartyCookieSite(host)) { on ->
                    val sites = config.wispThirdPartyCookieSites.toMutableSet()
                    val base = Config.baseSite(host)
                    sites.removeAll { Config.baseSite(it) == base }
                    if (on) sites.add(base)
                    config.wispThirdPartyCookieSites = sites
                    wv?.applyThirdPartyCookiePolicy(host)
                    refresh()
                },
                WispSheets.Row(R.drawable.ic_desktop_site, getString(R.string.wisp_desktop),
                    switchOn = config.isDesktopSite(host)) { toggleDesktopSite() },
                if (hiddenCount == 0) null else WispSheets.Row(R.drawable.ic_wisp_private,
                    getString(R.string.wisp_site_unhide), subtitle = resources.getQuantityString(R.plurals.wisp_hidden_n,
                        hiddenCount, hiddenCount)) {
                    config.clearWispHidden(host)
                    refresh()
                },
                WispSheets.Row(R.drawable.ic_wisp_cookie, getString(R.string.wisp_site_delete_cookies),
                    subtitle = resources.getQuantityString(R.plurals.wisp_cookies_n, cookies, cookies)) {
                    clearSiteCookies(tab.url)
                    tab.webEngine.reload()
                    Toast.makeText(this, getString(R.string.wisp_site_deleted, site), Toast.LENGTH_SHORT).show()
                },
                WispSheets.Row(R.drawable.ic_site_data, getString(R.string.wisp_site_delete_storage),
                    subtitle = getString(R.string.wisp_site_storage_s, size)) {
                    clearSiteStorage(wv, origin)
                    Toast.makeText(this, getString(R.string.wisp_site_deleted, site), Toast.LENGTH_SHORT).show()
                },
                WispSheets.Row(R.drawable.ic_delete_grey_900_36dp, getString(R.string.wisp_site_delete_all), danger = true) {
                    clearSiteCookies(tab.url)
                    clearSiteStorage(wv, origin)
                    Toast.makeText(this, getString(R.string.wisp_site_deleted, site), Toast.LENGTH_SHORT).show()
                }
            )
            WispSheets.showSiteInfo(this, host.removePrefix("www."), secure, rows)
        }
        if (wv == null) { show(-1); return }
        //size of the page's localStorage (IndexedDB and the cache can't be measured from here)
        wv.evaluateJavascript(
            "(function(){var s=0;try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);s+=k.length+(localStorage.getItem(k)||'').length}}catch(e){}return String(s*2)})()"
        ) { res -> show(res?.trim('"')?.toLongOrNull() ?: -1) }
    }

    /** Wisp: a pinned shortcut that opens the current page (Chrome and Firefox's "Add to Home screen"). */
    private fun addToHomeScreen(tab: WebTabState) {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, R.string.wisp_add_to_home_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val url = tab.url
        val label = tab.title.ifBlank { Uri.parse(url).host ?: url }.take(40)
        lifecycleScope.launch(Dispatchers.Main) {
            val favicon = try { com.phlox.tvwebbrowser.singleton.FaviconsPool.get(url) } catch (e: Exception) { null }
            val icon = if (favicon != null) {
                //the site's icon centred on a round plate, like launchers show web shortcuts
                val size = 192
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.WHITE
                c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                val inner = (size * 0.56f).toInt()
                val off = (size - inner) / 2f
                c.drawBitmap(favicon, null, android.graphics.RectF(off, off, off + inner, off + inner),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                android.graphics.drawable.Icon.createWithBitmap(bmp)
            } else {
                android.graphics.drawable.Icon.createWithResource(this@MainActivity, R.drawable.ic_launcher)
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(this@MainActivity, MainActivity::class.java)
            val info = android.content.pm.ShortcutInfo.Builder(this@MainActivity, "site-" + url.hashCode())
                .setShortLabel(label)
                .setIcon(icon)
                .setIntent(intent)
                .build()
            try {
                sm.requestPinShortcut(info, null)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.wisp_add_to_home_unsupported, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- Wisp: find in page ----------------
    private var findBar: android.widget.LinearLayout? = null
    private var findEdit: android.widget.EditText? = null
    private var findCount: android.widget.TextView? = null
    private var findView: android.webkit.WebView? = null

    private fun showFindInPage() {
        val wv = tabsModel.currentTab.value?.webEngine?.getView() as? android.webkit.WebView ?: return
        hideUndoBar() //it would sit over the search box and keyboard
        val bar = findBar ?: buildFindBar().also { findBar = it }
        findView = wv
        wv.setFindListener { active, matches, _ ->
            findCount?.text = if (matches == 0) "0/0" else "${active + 1}/$matches"
        }
        showToolbar()
        vb.vActionBar.visibility = View.INVISIBLE
        bar.visibility = View.VISIBLE
        findEdit?.let { e ->
            e.setText("")
            e.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Leaves find in page (Back, ✕, switching tabs). True when it was open. */
    private fun closeFindInPage(): Boolean {
        val bar = findBar ?: return false
        if (bar.visibility != View.VISIBLE) return false
        findView?.let {
            it.clearMatches()
            it.setFindListener(null)
        }
        findView = null
        findEdit?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        bar.visibility = View.GONE
        vb.vActionBar.visibility = View.VISIBLE
        return true
    }

    private fun buildFindBar(): android.widget.LinearLayout {
        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val iconColor = ContextCompat.getColor(this, R.color.wisp_icon)
        val bar = android.widget.LinearLayout(this)
        bar.orientation = android.widget.LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(px(10f), px(6f), px(4f), px(6f))
        bar.setBackgroundColor(ContextCompat.getColor(this,
            if (config.incognitoMode) R.color.wisp_bar_private else R.color.wisp_bar))
        val edit = android.widget.EditText(this)
        edit.setHint(R.string.wisp_find_in_page)
        edit.isSingleLine = true
        edit.textSize = 15f
        edit.setBackgroundResource(R.drawable.wisp_url_pill)
        edit.setPadding(px(16f), px(9f), px(16f), px(9f))
        edit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: ""
                if (text.isEmpty()) {
                    findView?.clearMatches()
                    findCount?.text = ""
                } else {
                    findView?.findAllAsync(text)
                }
            }
        })
        edit.setOnEditorActionListener { _, _, _ -> findView?.findNext(true); true }
        bar.addView(edit, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val count = android.widget.TextView(this)
        count.textSize = 13f
        count.setTextColor(ContextCompat.getColor(this, R.color.wisp_text_secondary))
        count.setPadding(px(10f), 0, px(4f), 0)
        bar.addView(count)
        fun button(icon: Int, desc: Int, click: () -> Unit) {
            val b = android.widget.ImageButton(this)
            b.setImageResource(icon)
            b.setColorFilter(iconColor)
            b.contentDescription = getString(desc)
            b.setBackgroundResource(R.drawable.button_bg_selector)
            b.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            b.setOnClickListener { click() }
            bar.addView(b, android.widget.LinearLayout.LayoutParams(px(42f), px(42f)))
        }
        button(R.drawable.ic_wisp_up, R.string.wisp_find_previous) { findView?.findNext(false) }
        button(R.drawable.ic_wisp_down, R.string.wisp_find_next) { findView?.findNext(true) }
        button(R.drawable.ic_close_grey_900_24dp, R.string.wisp_find_close) { closeFindInPage() }
        findEdit = edit
        findCount = count
        //over the address bar, same place and height
        val lp = android.widget.RelativeLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0)
        lp.addRule(android.widget.RelativeLayout.ALIGN_TOP, R.id.vActionBar)
        lp.addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, R.id.vActionBar)
        bar.visibility = View.GONE
        vb.rlActionBar.addView(bar, lp)
        return bar
    }

    // ---------------- Wisp: pull to refresh ----------------
    private var pullIndicator: android.widget.ImageView? = null

    /** The page is pulled down past its top by [distance] px; [released]: the finger was lifted. */
    private fun onPullToRefresh(tab: WebTabState, distance: Float, released: Boolean) {
        if (tab != tabsModel.currentTab.value || isFullscreen) return
        val dp = resources.displayMetrics.density
        val threshold = 110 * dp
        val ind = pullIndicator ?: android.widget.ImageView(this).also { iv ->
            iv.setImageResource(R.drawable.ic_refresh_grey_900_24dp)
            iv.setColorFilter(ContextCompat.getColor(this, R.color.wisp_accent))
            iv.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@MainActivity, R.color.wisp_sheet))
            }
            val pad = (9 * dp).toInt()
            iv.setPadding(pad, pad, pad, pad)
            iv.elevation = 6 * dp
            val size = (40 * dp).toInt()
            val lp = android.widget.RelativeLayout.LayoutParams(size, size)
            lp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
            lp.addRule(android.widget.RelativeLayout.BELOW, R.id.rlActionBar)
            lp.topMargin = -size
            vb.rlRoot.addView(iv, lp)
            pullIndicator = iv
        }
        val reach = distance.coerceIn(0f, threshold * 1.2f)
        if (released) {
            if (distance >= threshold) refresh()
            ind.animate().translationY(0f).alpha(0f).setDuration(180).start()
            return
        }
        ind.animate().cancel()
        ind.alpha = (reach / threshold).coerceIn(0f, 1f)
        ind.translationY = reach * 0.6f + 8 * dp
        ind.rotation = reach * 1.5f
        ind.setColorFilter(ContextCompat.getColor(this,
            if (distance >= threshold) R.color.wisp_accent else R.color.wisp_text_secondary))
    }

    /** Wisp: third-party cookie rules changed in Settings: apply them to every open tab. */
    fun reapplyCookiePolicy() {
        for (t in tabsModel.tabsStates) {
            val wv = t.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx ?: continue
            wv.applyThirdPartyCookiePolicy(try { Uri.parse(t.url).host } catch (e: Exception) { null })
        }
    }

    /** Wisp: text size of all open pages (Settings → General). */
    fun applyTextZoom() {
        for (t in tabsModel.tabsStates) {
            (t.webEngine.getView() as? android.webkit.WebView)?.settings?.textZoom = config.wispTextZoom
        }
    }

    /** Remote "channel up/down": jump to the next or previous tab. */
    fun switchTabRelative(delta: Int) {
        val tabs = tabsModel.tabsStates
        if (tabs.size < 2) {
            Toast.makeText(this, R.string.sofa_only_one_tab, Toast.LENGTH_SHORT).show()
            return
        }
        val current = tabsModel.currentTab.value
        val idx = if (current == null) 0 else tabs.indexOf(current).coerceAtLeast(0)
        val newIndex = ((idx + delta) % tabs.size + tabs.size) % tabs.size
        val newTab = tabs[newIndex]
        changeTab(newTab)
        vb.vTabs.current = newIndex
        val title = newTab.title.ifBlank { newTab.url }
        NotificationView.showBottomRight(vb.rlRoot, R.drawable.ic_menu_24,
            getString(R.string.sofa_tab_n_of_m, newIndex + 1, tabs.size, title))
    }

    /**
     * Grab button: lists the video, audio and subtitle addresses the current page has loaded
     * (seen in its network requests) or written into its <video>/<audio>/<track> elements.
     */
    private fun showGrabbedLinks() {
        val tab = tabsModel.currentTab.value ?: return
        val webView = tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        if (webView == null) {
            Toast.makeText(this, R.string.lumen_grab_engine, Toast.LENGTH_LONG).show()
            return
        }
        webView.evaluateJavascript("JSON.stringify(window.__lumenDomMedia ? window.__lumenDomMedia() : [])") { result ->
            try {
                val json = org.json.JSONTokener(result).nextValue() as? String ?: "[]"
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val kind = when (o.optString("k")) {
                        "a" -> com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaKind.AUDIO
                        "s" -> com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaKind.SUBTITLE
                        else -> com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaKind.VIDEO
                    }
                    webView.mediaLog.add(o.optString("u"), tab.url, kind)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            presentGrabbedLinks(webView.mediaLog.all())
        }
    }

    /**
     * Wisp: the videos, sounds and subtitles found on the page, each with Download (single files),
     * Play in a video app and Copy link. Streams (HLS/DASH) come in many small pieces and can't be
     * saved as one file; they can be played or copied.
     */
    private fun presentPhoneMedia(links: List<com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.DetectedMedia>) {
        val tab = tabsModel.currentTab.value ?: return
        val usable = links.filter { it.url.startsWith("http") }.distinctBy { it.url }.reversed()
        if (usable.isEmpty()) {
            Toast.makeText(this, R.string.wisp_media_none, Toast.LENGTH_LONG).show()
            return
        }
        val items = usable.map { m ->
            val uri = Uri.parse(m.url)
            val stream = com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.isManifest(m.url)
            val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT).take(5)
            val format = when {
                stream && m.url.contains(".mpd", true) -> "DASH"
                stream -> "HLS"
                else -> ext.uppercase(Locale.ROOT).ifEmpty { "FILE" }
            }
            val icon = when (m.kind) {
                com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaKind.AUDIO -> R.drawable.ic_wisp_play
                else -> R.drawable.ic_wisp_play
            }
            WispSheets.MenuItem(icon, m.kind.label + " · " + format,
                subtitle = (uri.lastPathSegment ?: "") + " · " + (uri.host?.removePrefix("www.") ?: "")) {
                val actions = ArrayList<WispSheets.MenuItem>()
                if (!stream) actions.add(WispSheets.MenuItem(R.drawable.ic_wisp_download, getString(R.string.wisp_download_media)) {
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    val name = DownloadUtils.guessFileName(m.url, null, mime)
                    startDownloadNow(m.url, m.referer ?: tab.url, name, pageUserAgent(tab), mime,
                        Download.OperationAfterDownload.NOP, null, null, 0L)
                })
                //9: Wisp's own player sends the page's headers and cookies, which many sites need
                if (m.kind != com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaKind.SUBTITLE)
                    actions.add(WispSheets.MenuItem(R.drawable.ic_wisp_play, getString(R.string.wisp_play_in_wisp)) {
                        val cookies = try { CookieManager.getInstance().getCookie(m.url) } catch (e: Exception) { null }
                        com.phlox.tvwebbrowser.sofa.SofaPlayerActivity.start(this,
                            com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.Stream(m.url, tab.title, m.referer ?: tab.url,
                                pageUserAgent(tab), cookies, 0L, m.headers,
                                usable.filter { it.kind == com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaKind.SUBTITLE }.map { it.url }))
                    })
                actions.add(WispSheets.MenuItem(R.drawable.ic_wisp_open_new, getString(R.string.wisp_play_in_app)) {
                    val type = when (m.kind) {
                        com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaKind.AUDIO -> "audio/*"
                        else -> if (stream) (if (format == "DASH") "application/dash+xml" else "application/x-mpegURL") else "video/*"
                    }
                    //headers in the form MX Player / Just Player read (name, value, name, value...)
                    val hdr = LinkedHashMap<String, String>(m.headers)
                    if (hdr.keys.none { it.equals("Referer", true) }) hdr["Referer"] = m.referer ?: tab.url
                    pageUserAgent(tab)?.let { ua -> if (hdr.keys.none { it.equals("User-Agent", true) }) hdr["User-Agent"] = ua }
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                        .putExtra("headers", hdr.flatMap { listOf(it.key, it.value) }.toTypedArray())
                        .putExtra("title", tab.title)
                    try {
                        startActivity(Intent.createChooser(view, getString(R.string.wisp_play_in_app)))
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
                    }
                })
                actions.add(WispSheets.MenuItem(R.drawable.ic_wisp_copy, getString(R.string.wisp_copy_link)) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Media link", m.url))
                })
                WispSheets.showList(this, m.kind.label + " · " + format,
                    if (stream) getString(R.string.wisp_media_stream_note) else m.url, listOf(actions))
            }
        }
        WispSheets.showList(this, getString(R.string.wisp_media) + " (" + usable.size + ")", null, listOf(items))
    }

    private fun presentGrabbedLinks(links: List<com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.DetectedMedia>) {
        if (com.phlox.tvwebbrowser.Brand.phone) { presentPhoneMedia(links); return }
        if (links.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.lumen_grab_title)
                .setMessage(R.string.lumen_grab_empty)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        fun label(m: com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.DetectedMedia): String {
            val uri = Uri.parse(m.url)
            val name = uri.lastPathSegment ?: m.url
            val format = when {
                com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.isManifest(m.url) && m.url.contains(".mpd", true) -> "DASH"
                com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.isManifest(m.url) -> "HLS"
                else -> name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(5).ifEmpty { "STREAM" }
            }
            return "${m.kind.label} · $format · $name\n${uri.host}"
        }
        val labels = links.map { label(it) }.toTypedArray()
        val checked = BooleanArray(links.size)
        fun selectedOrAll(): List<String> {
            val sel = links.filterIndexed { i, _ -> checked[i] }.map { it.url }
            return sel.ifEmpty { links.map { it.url } }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lumen_grab_title) + " (" + links.size + ")")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.lumen_grab_copy) { _, _ ->
                val urls = selectedOrAll()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Media links", urls.joinToString("\n")))
                Toast.makeText(this, getString(R.string.lumen_grab_copied, urls.size), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.lumen_grab_show_text) { _, _ -> showLinksAsText(selectedOrAll()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The full addresses in a text box, so any part can be selected and copied. */
    private fun showLinksAsText(urls: List<String>) {
        val text = android.widget.EditText(this)
        text.setText(urls.joinToString("\n\n"))
        text.setTextIsSelectable(true)
        text.isFocusable = true
        text.keyListener = null //read-only, but still selectable
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        text.typeface = android.graphics.Typeface.MONOSPACE
        val pad = (16 * resources.displayMetrics.density).toInt()
        val scroll = android.widget.ScrollView(this)
        scroll.setPadding(pad, pad / 2, pad, 0)
        scroll.addView(text)
        AlertDialog.Builder(this)
            .setTitle(R.string.lumen_grab_text_title)
            .setView(scroll)
            .setPositiveButton(R.string.lumen_grab_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val sel = if (text.hasSelection()) text.text.substring(text.selectionStart, text.selectionEnd) else null
                clipboard.setPrimaryClip(ClipData.newPlainText("Media links", sel ?: urls.joinToString("\n")))
                Toast.makeText(this, getString(R.string.lumen_grab_copied, if (sel != null) 1 else urls.size), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Toolbar Deband button: deband the video inside the page, or hand the video over to Lumen's own
     * player with anime deband on (hardware decoding, filters on the GPU - the most reliable way).
     */
    private fun showDebandMenu() {
        val tab = tabsModel.currentTab.value ?: return
        val webView = tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        if (webView == null) {
            Toast.makeText(this, R.string.lumen_grab_engine, Toast.LENGTH_LONG).show()
            return
        }
        val host = try { Uri.parse(tab.url).host } catch (e: Exception) { null }
        val siteOn = config.isDebandSite(host)
        val items = arrayListOf(
            getString(if (webView.pageDeband) R.string.lumen_deband_menu_page_off else R.string.lumen_deband_menu_page_on),
            getString(R.string.lumen_deband_menu_player)
        )
        if (!host.isNullOrEmpty()) {
            items.add(getString(if (siteOn) R.string.lumen_deband_menu_site_off else R.string.lumen_deband_menu_site_on,
                host.removePrefix("www.")))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lumen_deband_menu_title)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> togglePageDeband()
                    1 -> playWithDeband(tab, webView)
                    else -> {
                        val key = Config.siteKey(host!!)
                        config.lumenDebandSites = if (siteOn) config.lumenDebandSites.filterNot {
                            host == it || host.endsWith(".$it")
                        }.toSet() else config.lumenDebandSites + key
                        Toast.makeText(this, if (siteOn) R.string.lumen_deband_site_removed else R.string.lumen_deband_site_added,
                            Toast.LENGTH_LONG).show()
                        if (!siteOn && !webView.pageDeband) togglePageDeband()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Deband didn't start: show what each part of the page reported, with a Copy button. */
    private fun showDebandDiagnosis(webView: com.phlox.tvwebbrowser.webengine.webview.WebViewEx) {
        val lines = ArrayList<String>()
        val scans = synchronized(webView.debandScans) { ArrayList(webView.debandScans) }
        for (raw in scans) {
            try {
                val o = org.json.JSONObject(raw)
                val where = (if (o.optBoolean("top")) "page " else "frame ") + o.optString("h")
                lines.add("• $where: ${o.optInt("v")} video(s), ${o.optInt("p")} playing, " +
                        "${o.optInt("f")} filtered" +
                        (if (o.optInt("d") > 0) ", ${o.optInt("d")} DRM-protected" else "") +
                        (if (!o.optBoolean("gl", true)) ", no WebGL" else ""))
            } catch (e: Exception) {
                lines.add("• $raw")
            }
        }
        val anyVideo = scans.any { it.contains("\"v\":") && !it.contains("\"v\":0") }
        val summary = when {
            scans.isEmpty() -> getString(R.string.lumen_deband_diag_noscript)
            !anyVideo -> getString(R.string.lumen_deband_diag_novideo)
            else -> getString(R.string.lumen_deband_diag_notdrawn)
        }
        val text = summary + "\n\n" + lines.joinToString("\n") + "\n\n" + (tabsModel.currentTab.value?.url ?: "")
        AlertDialog.Builder(this)
            .setTitle(R.string.lumen_deband_diag_title)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.lumen_grab_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("${com.phlox.tvwebbrowser.Brand.NAME} deband", text))
                Toast.makeText(this, R.string.lumen_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun playWithDeband(tab: WebTabState, webView: com.phlox.tvwebbrowser.webengine.webview.WebViewEx) {
        val asked = System.currentTimeMillis()
        //the frame that plays the video answers with its address and position (see 'handoff-deband')
        webView.evaluateJavascript("window.__sofaMedia && window.__sofaMedia('handoff-deband')", null)
        toolbarHandler.postDelayed({
            if (com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.lastLaunchAt >= asked) return@postDelayed
            //no frame answered: use the streams the page has loaded
            com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.open(this, com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MODE_NATIVE_DEBAND,
                null, tab.url, tab.title, 0.0, webView.settings.userAgentString, webView.mediaLog)
        }, 1500)
    }

    /** Toolbar Deband button: anime deband for the video(s) on the current page, until it reloads. */
    private fun togglePageDeband() {
        val tab = tabsModel.currentTab.value ?: return
        val webView = tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        if (webView == null) {
            Toast.makeText(this, R.string.lumen_grab_engine, Toast.LENGTH_LONG).show()
            return
        }
        if (isTvDevice() && !webView.pageDeband) {
            //TVs: no deband inside pages (too heavy for TV graphics chips, broke the picture);
            //the video plays in Lumen's player with deband instead
            Toast.makeText(this, "On TVs deband runs in ${com.phlox.tvwebbrowser.Brand.NAME}'s player: opening the video there", Toast.LENGTH_LONG).show()
            playWithDeband(tab, webView)
            return
        }
        webView.pageDeband = !webView.pageDeband
        webView.evaluateJavascript("window.__lumenSetDeband && window.__lumenSetDeband(" + webView.pageDeband + ")", null)
        webView.debandConfirmed = false
        webView.debandScans.clear()
        Toast.makeText(this, if (webView.pageDeband) R.string.lumen_deband_waiting else R.string.lumen_deband_page_off,
            Toast.LENGTH_SHORT).show()
        if (webView.pageDeband) {
            //no confirmation and no error after a while: say so instead of leaving the user guessing
            val asked = System.currentTimeMillis()
            toolbarHandler.postDelayed({
                if (webView.pageDeband && !webView.debandConfirmed && webView.lastDebandReport < asked &&
                    tabsModel.currentTab.value?.webEngine?.getView() === webView) {
                    showDebandDiagnosis(webView)
                }
            }, 10_000)
        }
        onWebViewUpdated(tab)
    }

    /** Hides the tablet's status and navigation bars while a video is full screen. */
    private fun setLumenImmersive(on: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.also {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.also {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    private fun toggleDesktopSite() {
        val tab = tabsModel.currentTab.value ?: return
        val webView = tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        if (webView == null) {
            Toast.makeText(this, R.string.sofa_desktop_not_supported, Toast.LENGTH_LONG).show()
            return
        }
        val host = try { URL(tab.url).host } catch (e: Exception) { null }
        //Wisp: the switch is for the whole site (see Config.isDesktopSite)
        val site = if (com.phlox.tvwebbrowser.Brand.phone) Config.baseSite(host) else Config.siteKey(host)
        if (site.isEmpty()) return
        val sites = config.desktopSites.toMutableSet()
        val nowDesktop = if (config.isDesktopSite(site)) {
            //remove the entry and any parent-domain entry that covers this site
            if (com.phlox.tvwebbrowser.Brand.phone) sites.removeAll { Config.baseSite(it) == site }
            else sites.removeAll { site == it || site.endsWith(".$it") }
            false
        } else {
            sites.add(site)
            true
        }
        config.desktopSites = sites
        Toast.makeText(this, getString(if (nowDesktop) R.string.sofa_desktop_on else R.string.sofa_desktop_off, site),
            Toast.LENGTH_SHORT).show()
        onWebViewUpdated(tab)
        hideMenuOverlay()
        //load again with the new user agent
        webView.loadUrl(tab.url)
    }

    private fun isAdBlockOnForTab(tab: WebTabState): Boolean {
        tab.adblock?.let { return it }
        if (!config.adBlockEnabled) return false
        val host = try { URL(tab.url).host } catch (e: Exception) { null }
        return !config.isAdBlockDisabledForHost(host)
    }

    private fun showAdBlockOptions() {
        val tab = tabsModel.currentTab.value ?: return
        val host = try { URL(tab.url).host } catch (e: Exception) { null }
        val site = Config.siteKey(host)
        if (site.isEmpty() || !config.adBlockEnabled) {
            toggleAdBlockForTab()
            return
        }
        val disabledForSite = if (com.phlox.tvwebbrowser.Brand.phone) config.isAdBlockDisabledForHost(site)
            else config.adBlockDisabledHosts.contains(site)
        val items = arrayOf(
            getString(R.string.sofa_adblock_on_site, site),
            getString(R.string.sofa_adblock_off_site, site),
            getString(R.string.sofa_adblock_off_tab)
        )
        val checked = when {
            tab.adblock == false -> 2
            disabledForSite -> 1
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sofa_adblock_title)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val sites = config.adBlockDisabledHosts.toMutableSet()
                when (which) {
                    //Wisp: for the whole site (Config.isAdBlockDisabledForHost on the phone)
                    0 -> {
                        if (com.phlox.tvwebbrowser.Brand.phone) sites.removeAll { Config.baseSite(it) == Config.baseSite(site) }
                        else sites.remove(site)
                        tab.adblock = null
                    }
                    1 -> { sites.add(if (com.phlox.tvwebbrowser.Brand.phone) Config.baseSite(site) else site); tab.adblock = null }
                    2 -> { tab.adblock = false }
                }
                config.adBlockDisabledHosts = sites
                val newState = isAdBlockOnForTab(tab)
                tab.webEngine.onUpdateAdblockSetting(newState)
                onWebViewUpdated(tab)
                dialog.dismiss()
                refresh()
            }
            .also { if (!com.phlox.tvwebbrowser.Brand.phone) it.setNeutralButton("Site fixes…") { _, _ -> showSiteFixes(tab, site) } }
            .show()
    }

    /** Shield menu → "Site fixes…": for sites stuck on a verification or ad gate. */
    private fun showSiteFixes(tab: WebTabState, site: String) {
        val wv = tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        val ff = config.isFirefoxSite(site)
        val ext = config.isExternalSite(site)
        val firefox = com.phlox.tvwebbrowser.sofa.LumenExternal.firefoxPackage(this) != null
        val browser = if (firefox) "Firefox" else "another browser"
        val player = if (com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.justPlayerPackage(this) != null) "Just Player" else "a player app"
        val items = arrayOf(
            "Open this page in $browser now",
            if (ext) "✓ Always open $site in $browser (tap to stop)" else "Always open $site in $browser",
            "Verification failing? Reload clean (no ad blocking or page changes, this time)",
            if (ff) "✓ Identify as Firefox on $site (tap to stop)" else "Identify as Firefox on $site",
            "Reset $site (undo all fixes, forget its verification, new cookies)",
            if (config.isExternalVideoSite(site)) "✓ Play $site videos in $player (tap to stop)" else "Always play $site videos in $player",
            if (config.isVideoCompatSite(site)) "✓ Video picture fix on $site (tap to stop)"
                else "Video breaks into blocks here only? Video picture fix for $site",
            "Deinterlace / inverse telecine on $site: " + when (config.ivtcModeFor(site)) {
                "auto" -> "automatic"; "ivtc" -> "inverse telecine"; "mixed" -> "IVTC + deinterlace"; "deint" -> "deinterlace"; else -> "off"
            } + "…",
            "${com.phlox.tvwebbrowser.Brand.NAME}'s player for $site videos (the page downloads them): " + when (config.pagePlayerModeFor(site)) {
                "on" -> "always"; "off" -> "never"
                else -> if (com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.wantsPagePlayer(this, site)) "automatic (on, TV)" else "automatic (off)"
            } + "…"
        )
        AlertDialog.Builder(this)
            .setTitle("Site fixes · $site")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> com.phlox.tvwebbrowser.sofa.LumenExternal.open(this, tab.url)
                    1 -> {
                        config.lumenExternalSites = if (ext) config.lumenExternalSites - site else config.lumenExternalSites + site
                        if (!ext) {
                            com.phlox.tvwebbrowser.sofa.LumenExternal.open(this, tab.url)
                            Toast.makeText(this, "$site will open in $browser from now on. " +
                                    "To stop: the same menu in ${com.phlox.tvwebbrowser.Brand.NAME}.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "$site opens in ${com.phlox.tvwebbrowser.Brand.NAME} again", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> if (wv?.startManualCleanMode() == true) Toast.makeText(this,
                        "Reloaded without ad blocking or page changes for the verification. " +
                                "Password saving is off on this page.", Toast.LENGTH_LONG).show()
                    4 -> {
                        config.lumenFirefoxSites = config.lumenFirefoxSites - site
                        config.lumenExternalSites = config.lumenExternalSites - site
                        config.desktopSites = config.desktopSites - site
                        config.lumenExternalVideoSites = config.lumenExternalVideoSites - site
                        config.lumenVideoCompatSites = config.lumenVideoCompatSites - site
                        config.setIvtcMode(site, "")
                        config.setPagePlayerMode(site, "")
                        config.lumenDebandSites = config.lumenDebandSites.filterNot { it == site || it.endsWith(".$site") || site.endsWith(".$it") }.toSet()
                        com.phlox.tvwebbrowser.sofa.LumenVerify.forget(site)
                        clearSiteCookies(tab.url)
                        wv?.applyUserAgentFor(tab.url)
                        tab.webEngine.reload()
                        Toast.makeText(this, "$site reset: it opens like a site you never visited", Toast.LENGTH_LONG).show()
                    }
                    7 -> showIvtcChoice(tab, site)
                    8 -> showPagePlayerChoice(site)
                    6 -> {
                        val on = config.isVideoCompatSite(site)
                        config.lumenVideoCompatSites = if (on) config.lumenVideoCompatSites - site else config.lumenVideoCompatSites + site
                        tab.webEngine.reload()
                        Toast.makeText(this, if (on) "$site: videos use the TV's video layer again"
                            else "$site: videos are now drawn by the browser instead of the TV's video layer. " +
                                "Start the video again and check the picture.", Toast.LENGTH_LONG).show()
                    }
                    5 -> {
                        val on = config.isExternalVideoSite(site)
                        config.lumenExternalVideoSites = if (on) config.lumenExternalVideoSites - site else config.lumenExternalVideoSites + site
                        Toast.makeText(this, if (on) "$site videos play in the page again"
                            else "Videos on $site now open in $player when they start playing. " +
                                "Videos the TV can't show cleanly in the page usually play fine there.", Toast.LENGTH_LONG).show()
                    }
                    3 -> {
                        config.lumenFirefoxSites = if (ff) config.lumenFirefoxSites - site else config.lumenFirefoxSites + site
                        wv?.applyUserAgentFor(tab.url)
                        tab.webEngine.reload()
                        Toast.makeText(this, if (ff) "$site: normal identity again"
                            else "$site now sees Firefox. If its videos or sign-in break, switch it off here.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Site fixes → Lumen's player with the page downloading (LumenPageFetch). */
    private fun showPagePlayerChoice(site: String) {
        val modes = arrayOf("", "on", "off")
        val labels = arrayOf(
            "Automatic: on TVs, streamed videos play in ${com.phlox.tvwebbrowser.Brand.NAME}'s player",
            "Always: videos start in ${com.phlox.tvwebbrowser.Brand.NAME}'s player (clean picture from the TV's video chip, deinterlacer on the GPU)",
            "Never: videos stay in the page"
        )
        val current = modes.indexOf(config.pagePlayerModeFor(site)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("${com.phlox.tvwebbrowser.Brand.NAME}'s player on $site")
            .setSingleChoiceItems(labels, current) { d, which ->
                config.setPagePlayerMode(site, modes[which])
                d.dismiss()
                Toast.makeText(this, "Start the video again", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Site fixes → deinterlacing in the page (port of Just Player URL+'s automatic IVTC). */
    private fun showIvtcChoice(tab: WebTabState, site: String) {
        val modes = arrayOf("", "auto", "ivtc", "mixed", "deint")
        val labels = arrayOf(
            "Off",
            "Automatic: measure the video (~12 s), then IVTC, deinterlace or nothing",
            "Inverse telecine (field matching) - 3:2 pulldown anime / film",
            "Inverse telecine + deinterlace the interlaced parts",
            "Deinterlace (really interlaced video)"
        )
        val current = modes.indexOf(config.ivtcModeFor(site)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Deinterlacing on $site")
            .setSingleChoiceItems(labels, current) { d, which ->
                config.setIvtcMode(site, modes[which])
                d.dismiss()
                tab.webEngine.reload()
                Toast.makeText(this, if (which == 0) "Deinterlacing off on $site"
                    else "Start the video: a small note on the picture shows what the deinterlacer does",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Expires every cookie the site's address can see (Cloudflare's pass cookies included). */
    private fun clearSiteCookies(url: String) {
        try {
            val cm = android.webkit.CookieManager.getInstance()
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: return
            val parts = host.split('.')
            val domains = (0 until (parts.size - 1).coerceAtLeast(1)).map { parts.drop(it).joinToString(".") }
            val cookies = cm.getCookie(url) ?: return
            for (c in cookies.split(';')) {
                val name = c.substringBefore('=').trim()
                if (name.isEmpty()) continue
                for (d in domains) {
                    for (path in listOf("/", uri.path ?: "/")) {
                        cm.setCookie("https://$host", "$name=; Max-Age=0; Path=$path; Domain=$d")
                        cm.setCookie("https://$host", "$name=; Max-Age=0; Path=$path")
                    }
                }
            }
            cm.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Settings → VPN → connect: asks for the Android VPN permission first if needed. */
    fun requestVpnConnect() {
        val intent = com.phlox.tvwebbrowser.sofa.SofaVpn.permissionIntent(this)
        if (intent != null) {
            try {
                startActivityForResult(intent, REQUEST_CODE_VPN_PERMISSION)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.sofa_vpn_not_supported, Toast.LENGTH_LONG).show()
            }
            return
        }
        lifecycleScope.launch { com.phlox.tvwebbrowser.sofa.SofaVpn.connect(applicationContext) }
    }

    private var onDownloadFolderPicked: (() -> Unit)? = null

    /** Settings → Downloads → Choose folder: system folder picker (internal storage, SD card, USB). */
    fun pickDownloadFolder(onPicked: () -> Unit) {
        onDownloadFolderPicked = onPicked
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (com.phlox.tvwebbrowser.Brand.phone) {
            //Wisp: phones always have the system file manager's folder picker (and Wisp has no
            //"all files" permission for a folder browser of its own)
            try {
                startActivityForResult(intent, REQUEST_CODE_PICK_DOWNLOAD_FOLDER)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.external_app_open_error, Toast.LENGTH_LONG).show()
            }
            return
        }
        //Android TV answers this request with a placeholder ("there's no app that can do this"),
        //so only real folder pickers (system Files, AnExplorer, ...) are offered, next to our own browser
        val pickers = packageManager.queryIntentActivities(intent, 0)
            .filter { !it.activityInfo.packageName.contains("frameworkpackagestubs") && it.activityInfo.exported }
        val builtIn = { com.phlox.tvwebbrowser.sofa.LumenFolderPicker(this) { onPicked() }.show() }
        if (pickers.isEmpty()) {
            builtIn()
            return
        }
        val labels = ArrayList<String>()
        labels.add("${com.phlox.tvwebbrowser.Brand.NAME} folder browser (works with the remote)")
        for (p in pickers) labels.add(p.loadLabel(packageManager).toString())
        AlertDialog.Builder(this)
            .setTitle(R.string.lumen_choose_folder_with)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    builtIn()
                    return@setItems
                }
                val info = pickers[which - 1].activityInfo
                val explicit = Intent(intent).setClassName(info.packageName, info.name)
                try {
                    startActivityForResult(explicit, REQUEST_CODE_PICK_DOWNLOAD_FOLDER)
                } catch (e: Exception) {
                    builtIn()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onDownloadFolderResult(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        //make sure files can really be created there (read-only drives, unsupported file systems)
        val problem = com.phlox.tvwebbrowser.sofa.LumenDownloads.testWritable(uri)
        if (problem != null) {
            try {
                contentResolver.releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (ignored: Exception) {
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.lumen_folder_not_writable)
                .setMessage(getString(R.string.lumen_folder_not_writable_msg, problem))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        config.lumenDownloadFolderUri = uri.toString()
        Toast.makeText(this, getString(R.string.lumen_folder_set,
            com.phlox.tvwebbrowser.sofa.LumenDownloads.folderLabel(this)), Toast.LENGTH_LONG).show()
        onDownloadFolderPicked?.invoke()
        onDownloadFolderPicked = null
    }

    fun pickVpnConfigFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.sofa_vpn_import_file)),
                REQUEST_CODE_VPN_IMPORT_FILE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.sofa_vpn_no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    /** "Import from file or archive": a .conf, a .zip of .conf files, or a picture of a WireGuard QR code. */
    fun pickVpnFileOrArchive() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.sofa_vpn_import_any)),
                REQUEST_CODE_VPN_IMPORT_ANY)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.sofa_vpn_no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun onVpnFileOrArchivePicked(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { com.phlox.tvwebbrowser.sofa.VpnImport.read(this@MainActivity, uri) }
            when (result) {
                is com.phlox.tvwebbrowser.sofa.VpnImport.Result.Configs -> {
                    if (result.list.size == 1) saveVpnConfig(result.list[0].text)
                    else AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.sofa_vpn_choose_config)
                        .setItems(result.list.map { it.name }.toTypedArray()) { _, i -> saveVpnConfig(result.list[i].text) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                com.phlox.tvwebbrowser.sofa.VpnImport.Result.NoQrCode ->
                    Toast.makeText(this@MainActivity, R.string.sofa_vpn_no_qr, Toast.LENGTH_LONG).show()
                com.phlox.tvwebbrowser.sofa.VpnImport.Result.Nothing ->
                    Toast.makeText(this@MainActivity, R.string.sofa_vpn_nothing_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveVpnConfig(text: String) {
        val error = com.phlox.tvwebbrowser.sofa.SofaVpn.importConfig(text)
        Toast.makeText(this,
            if (error == null) getString(R.string.sofa_vpn_imported) else getString(R.string.sofa_vpn_bad_config, error),
            Toast.LENGTH_LONG).show()
    }

    private fun onVpnConfigFilePicked(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null || text.length > 100_000) {
                Toast.makeText(this@MainActivity, R.string.sofa_vpn_import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val error = com.phlox.tvwebbrowser.sofa.SofaVpn.importConfig(text)
            Toast.makeText(this@MainActivity,
                if (error == null) getString(R.string.sofa_vpn_imported) else getString(R.string.sofa_vpn_bad_config, error),
                Toast.LENGTH_LONG).show()
        }
    }

    private var progressBarHideRunnable: Runnable = Runnable {
        val anim = AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.fade_out)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                vb.progressBar.visibility = View.GONE
            }
        })
        vb.progressBar.startAnimation(anim)
    }

    private val mConnectivityChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            val isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting
            val tab = tabsModel.currentTab.value ?: return
            tab.webEngine.setNetworkAvailable(isConnected)
        }
    }

    private val displayThumbnailRunnable = object : Runnable {
        var tabState: WebTabState? = null
        override fun run() {
            tabState?.let {
                lifecycleScope.launch(Dispatchers.Main) {
                    displayThumbnail(it)
                }
            }
        }
    }

    private val tabsListener = object : Listener {
        override fun onTitleChanged(index: Int) {
            Log.d(TAG, "onTitleChanged: $index")
            val tab = tabByTitleIndex(index)
            vb.vActionBar.setAddressBoxText(tab?.url ?: "")
            uiHandler.removeCallbacks(displayThumbnailRunnable)
            displayThumbnailRunnable.tabState = tab
            uiHandler.postDelayed(displayThumbnailRunnable, 200)
        }

        override fun onTitleSelected(index: Int) {
            //a tap selects the tab directly (touch screens don't move the focus first)
            tabByTitleIndex(index)?.let { if (!it.selected) changeTab(it) }
            vb.vTabs.current = index
            hideMenuOverlay()
        }

        override fun onAddNewTabSelected() {
            openInNewTab(settingsModel.homePage, tabsModel.tabsStates.size)
        }

        override fun closeTab(tabState: WebTabState?) = this@MainActivity.closeTab(tabState)

        override fun openInNewTab(url: String, tabIndex: Int) {
            this@MainActivity.openInNewTab(url, tabIndex,
                needToHideMenuOverlay = false,
                navigateImmediately = true
            )
        }
    }

    override fun closeWindow() {
        Log.d(TAG, "closeWindow")
        if (com.phlox.tvwebbrowser.Brand.phone) {
            quitWisp()
            return
        }
        lifecycleScope.launch {
            clearDataOnExit()
            if (config.incognitoMode) {
                toggleIncognitoMode(false).join()
            }
            finish()
        }
    }

    /**
     * Wisp "Quit": the private session ends - tabs closed, cookies, site storage and cache deleted
     * now, and the web data folder itself at the next start (see TVBro.setUpWispPrivateData).
     * Downloads still running continue (they run in the download service).
     */
    private fun quitWisp() {
        lifecycleScope.launch {
            val keep = config.wispKeepSites.map { Config.baseSite(it) }.filter { it.isNotEmpty() }.toSet()
            try {
                if (keep.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        WebStorage.getInstance().deleteAllData()
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                    }
                } else {
                    clearAllExceptKeptSites(keep)
                }
                android.webkit.GeolocationPermissions.getInstance().clearAll()
                //the session's history and recently closed tabs end with it
                withContext(Dispatchers.IO) { AppDatabase.db.historyDao().deleteWhereTimeLessThan(Long.MAX_VALUE) }
                closedTabs.clear()
                WebEngineFactory.clearCache(this@MainActivity)
                tabsModel.onCloseAllTabs().join()
                tabsModel.currentTab.value = null
            } catch (e: Exception) {
                Log.e(TAG, "quit", e)
            }
            //the web data folder is deleted at the next start only when no site keeps its login
            if (keep.isEmpty()) {
                getSharedPreferences(TVBro.WISP_PRIVATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(TVBro.WISP_WIPE_KEY, true).commit()
            }
            finishAndRemoveTask()
        }
    }

    /**
     * Quit with "Stay logged in" sites: site storage of all other sites is deleted; cookies can't
     * be deleted per site in WebView, so the kept sites' cookies are read, all cookies deleted and
     * the kept ones written back (for the whole site, valid 90 days).
     */
    private suspend fun clearAllExceptKeptSites(keep: Set<String>) {
        //site storage (local storage, databases, service workers): per origin
        val origins: Set<String> = kotlin.coroutines.suspendCoroutine { cont ->
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                WebStorage.getInstance().getOrigins { map ->
                    val set = HashSet<String>()
                    (map as? Map<*, *>)?.keys?.forEach { k -> if (k != null) set.add(k.toString()) }
                    if (resumed.compareAndSet(false, true)) cont.resumeWith(Result.success(set as Set<String>))
                }
            } catch (e: Exception) {
                if (resumed.compareAndSet(false, true)) cont.resumeWith(Result.success(emptySet<String>()))
            }
        }
        for (origin in origins) {
            val host = try { Uri.parse(origin).host } catch (e: Exception) { null }
            if (Config.baseSite(host) !in keep) WebStorage.getInstance().deleteOrigin(origin)
        }
        //cookies. A cookie can belong to one subdomain (studio.example.com) or one folder
        //(example.com/app), so the addresses visited this session are read too, not only the site
        val cm = CookieManager.getInstance()
        val hosts = LinkedHashSet<String>()
        val folders = LinkedHashSet<Pair<String, String>>() //(host, "/first-folder")
        for (site in keep) for (h in listOf(site, "www.$site", "m.$site", "accounts.$site", "login.$site", "auth.$site")) hosts.add(h)
        val visited = ArrayList<String>()
        try { AppDatabase.db.historyDao().last(500).forEach { visited.add(it.url) } } catch (e: Exception) {}
        tabsModel.tabsStates.forEach { visited.add(it.url) }
        for (u in visited) {
            val uri = try { Uri.parse(u) } catch (e: Exception) { null } ?: continue
            val h = uri.host?.lowercase(Locale.ROOT) ?: continue
            if (uri.scheme != "https" && uri.scheme != "http") continue
            if (Config.baseSite(h) !in keep) continue
            hosts.add(h)
            uri.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { folders.add(Pair(h, "/$it")) }
        }
        //key → (url to set on, "name=value", path)
        val saved = LinkedHashMap<String, Triple<String, String, String>>()
        val rootNames = HashMap<String, MutableSet<String>>()
        for (h in hosts) {
            val site = Config.baseSite(h)
            val url = "https://$h"
            val all = try { cm.getCookie(url) } catch (e: Exception) { null } ?: continue
            for (part in all.split(";")) {
                val nv = part.trim()
                if (nv.isEmpty() || !nv.contains('=')) continue
                val name = nv.substringBefore('=')
                rootNames.getOrPut(h) { HashSet() }.add(name)
                //"__Host-" cookies belong to one exact address
                val key = if (name.startsWith("__Host-")) "$h|$name|/" else "$site|$name|/"
                if (key !in saved) saved[key] = Triple(url, nv, "/")
            }
        }
        for ((h, folder) in folders) {
            val site = Config.baseSite(h)
            val url = "https://$h$folder/"
            val all = try { cm.getCookie(url) } catch (e: Exception) { null } ?: continue
            for (part in all.split(";")) {
                val nv = part.trim()
                if (nv.isEmpty() || !nv.contains('=')) continue
                val name = nv.substringBefore('=')
                if (name.startsWith("__Host-") || rootNames[h]?.contains(name) == true) continue //already kept
                val key = "$site|$name|$folder"
                if (key !in saved) saved[key] = Triple(url, nv, folder)
            }
        }
        withContext(Dispatchers.IO) {
            cm.removeAllCookies(null)
            cm.flush()
        }
        for ((key, v) in saved) {
            val (url, nv, path) = v
            val site = key.substringBefore('|')
            val attrs = if (nv.substringBefore('=').startsWith("__Host-")) "; Path=/; Secure; Max-Age=7776000"
                else "; Domain=$site; Path=$path; Secure; Max-Age=7776000"
            try { cm.setCookie(url, nv + attrs) } catch (e: Exception) {}
        }
        withContext(Dispatchers.IO) { cm.flush() }
    }

    override fun showDownloads() {
        startActivityForResult(Intent(this@MainActivity, DownloadsActivity::class.java), REQUEST_CODE_DOWNLOADS)
    }

    /**
     * Downloads screen → "Download from link". Checks what the link is first: web pages open in a
     * tab, files are downloaded (with the name the server gives).
     */
    private fun downloadFromLink(link: String) {
        lifecycleScope.launch {
            val ua = (tabsModel.currentTab.value?.webEngine?.getView() as? android.webkit.WebView)?.settings?.userAgentString
                ?: android.webkit.WebSettings.getDefaultUserAgent(this@MainActivity)
            val info = withContext(Dispatchers.IO) {
                var c: java.net.HttpURLConnection? = null
                try {
                    c = java.net.URL(link).openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "HEAD"
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 15000
                    c.readTimeout = 15000
                    c.setRequestProperty("User-Agent", ua)
                    CookieManager.getInstance().getCookie(link)?.let { c.setRequestProperty("cookie", it) }
                    c.connect()
                    if (c.responseCode in 200..299) {
                        Triple(c.getHeaderField("Content-Type"), c.getHeaderField("Content-Disposition"), c.url.toString())
                    } else null
                } catch (e: Exception) {
                    null //HEAD not supported or failed: just try the download
                } finally {
                    c?.disconnect()
                }
            }
            val mime = info?.first?.substringBefore(';')?.trim()
            //a stream playlist isn't a file to save: play it in the built-in player
            if (com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.isManifest(info?.third ?: link) ||
                mime?.lowercase()?.contains("mpegurl") == true || mime == "application/dash+xml") {
                com.phlox.tvwebbrowser.sofa.SofaPlayerActivity.start(this@MainActivity,
                    com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.Stream(link, "", null, ua,
                        CookieManager.getInstance().getCookie(link), 0L))
                return@launch
            }
            if (mime != null && (mime.startsWith("text/html") || mime.startsWith("application/xhtml")) && info?.second == null) {
                Toast.makeText(this@MainActivity, R.string.lumen_link_is_page, Toast.LENGTH_LONG).show()
                openInNewTab(link, tabsModel.tabsStates.size, needToHideMenuOverlay = true, navigateImmediately = true)
                return@launch
            }
            val fileName = DownloadUtils.guessFileName(link, info?.second, mime)
            onDownloadRequested(link, "", fileName, ua, mime, Download.OperationAfterDownload.NOP, null, null)
            //back to the list to watch the progress
            showDownloads()
        }
    }

    override fun showHistory() {
        startActivityForResult(
                Intent(this@MainActivity, HistoryActivity::class.java),
                REQUEST_CODE_HISTORY_ACTIVITY)
        hideMenuOverlay()
    }

    override fun showFavorites() {
        val currentTab = tabsModel.currentTab.value
        val currentPageTitle = currentTab?.title ?: ""
        val currentPageUrl = currentTab?.url ?: ""

        FavoritesDialog(this@MainActivity, lifecycleScope, object : FavoritesDialog.Callback {
            override fun onFavoriteChoosen(item: FavoriteItem?) {
                navigate(item!!.url!!)
            }
        }, currentPageTitle, currentPageUrl).show()
        hideMenuOverlay()
    }

    private val bottomButtonsOnTouchListener = View.OnTouchListener{ v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                return@OnTouchListener true
            }
            MotionEvent.ACTION_UP -> {
                hideMenuOverlay(false)
                v.performClick()
                return@OnTouchListener true
            }
            else -> return@OnTouchListener false
        }
    }

    private val bottomButtonsFocusListener = View.OnFocusChangeListener { view, hasFocus ->
        if (hasFocus) {
            hideMenuOverlay(false)
        }
    }

    private val bottomButtonsKeyListener = View.OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    hideBottomPanel()
                    tabsModel.currentTab.value?.webEngine?.getView()?.requestFocus()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    private fun tabByTitleIndex(index: Int) =
            if (index >= 0 && index < tabsModel.tabsStates.size) tabsModel.tabsStates[index] else null

    private var settingsDialog: SettingsDialog? = null

    override fun showSettings() {
        if (com.phlox.tvwebbrowser.Brand.phone) {
            //Wisp: its own phone settings screen (no TV settings, no remote shortcuts)
            WispSettings(this, settingsModel, adblockModel).show()
            return
        }
        settingsDialog = SettingsDialog(this, settingsModel).also { it.show() }
    }

    /**
     * Settings → Browser check: a page that shows what websites (and Cloudflare's verification)
     * can see about this browser and device. WebView serves it from the app like the start page;
     * GeckoView gets it as a data: page.
     */
    fun openBrowserCheck() {
        try { settingsDialog?.dismiss() } catch (e: Exception) {}
        val url = if (config.webEngine == Config.ENGINE_GECKO_VIEW) {
            val html = assets.open("pages/home/check.html").bufferedReader().use { it.readText() }
            "data:text/html;charset=utf-8;base64," + android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_WRAP)
        } else {
            Config.HOME_PAGE_URL + "check.html"
        }
        openInNewTab(url, tabsModel.tabsStates.size, needToHideMenuOverlay = true, navigateImmediately = true)
    }

    override fun onExtendedAddressBarMode() {
        //the second row (tabs and buttons) stays visible while typing
    }

    override fun onUrlInputDone() {
        hideMenuOverlay()
    }

    fun navigateBack(goHomeIfNoHistory: Boolean = false) {
        val currentTab = tabsModel.currentTab.value
        if (currentTab != null && currentTab.webEngine.canGoBack()) {
            currentTab.webEngine.goBack()
        } else if (currentTab == null || isOnHomePage(currentTab)) {
            //Wisp: like other phone browsers, Back on the start page just leaves the app (tabs stay)
            if (com.phlox.tvwebbrowser.Brand.phone) moveTaskToBack(true) else confirmExit()
        } else {
            //no history left in this tab: go to the home page; Back there asks to exit
            navigate(settingsModel.homePage)
        }
    }

    private fun isOnHomePage(tab: WebTabState): Boolean {
        val url = tab.url
        return url == settingsModel.homePage || url == Config.HOME_URL_ALIAS ||
                url.startsWith(Config.HOME_PAGE_URL) || url.isBlank() || url == "about:blank"
    }

    private var exitDialogShown = false

    private fun confirmExit() {
        if (exitDialogShown) return
        exitDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lumen_exit_title, getString(R.string.app_name_short)))
            .setPositiveButton(R.string.lumen_exit) { _, _ -> closeWindow() }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { exitDialogShown = false }
            .show()
    }

    /** Set when the session's tabs were removed: nothing may save them again while closing. */
    private var sessionCleared = false

    /**
     * Settings: clear history / cookies when the browser is closed. "History" also closes all tabs,
     * so the next start begins with a fresh home page.
     */
    private suspend fun clearDataOnExit() {
        if (config.incognitoMode) return
        if (config.lumenClearHistoryOnExit) {
            sessionCleared = true
            val tabs = ArrayList(tabsModel.tabsStates)
            withContext(Dispatchers.IO) {
                try {
                    AppDatabase.db.historyDao().deleteWhereTimeLessThan(Long.MAX_VALUE)
                    AppDatabase.db.tabsDao().deleteAll(false)
                    tabs.forEach { try { it.removeFiles() } catch (ignored: Exception) {} }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (config.lumenClearCookiesOnExit) {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refresh() {
        tabsModel.currentTab.value?.webEngine?.reload()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        //here properties can be uninitialized in case of wrong activity for incognito mode
        //detection and force activity restart in onCreate()
        if (::tabsModel.isInitialized) {
            tabsModel.onDetachActivity()
        }
        super.onDestroy()
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) {
            handleIntent(intent)
        }
    }

    private fun loadState() = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "loadState")
        WebEngineFactory.initialize(this@MainActivity, vb.flWebViewContainer)

        //Wisp: no big spinner while the saved tabs load (it takes a moment and looked like the start page loading)
        if (!com.phlox.tvwebbrowser.Brand.phone) {
            vb.progressBarGeneric.visibility = View.VISIBLE
            vb.progressBarGeneric.requestFocus()
        }
        viewModel.loadState().join()
        //the browser was left (Home) and Android has since ended it: that counts as closing it
        if (!processStarted && prefs.getBoolean(PREF_CLEAR_PENDING, false)) {
            clearDataOnExit()
            sessionCleared = false
        }
        processStarted = true
        prefs.edit().putBoolean(PREF_CLEAR_PENDING, false).apply()
        tabsModel.loadState().join()

        if (!isActive) {
            return@launch
        }

        vb.progressBarGeneric.visibility = View.GONE

        if (intent.data == null) {
            if (tabsModel.tabsStates.isEmpty()) {
                openInNewTab(settingsModel.homePage, 0,
                    needToHideMenuOverlay = true,
                    navigateImmediately = true
                )
            } else {
                var foundSelectedTab = false
                for (i in tabsModel.tabsStates.indices) {
                    val tab = tabsModel.tabsStates[i]
                    if (tab.selected) {
                        changeTab(tab)
                        foundSelectedTab = true
                        break
                    }
                }
                if (!foundSelectedTab) {//this may happen in some error states
                    changeTab(tabsModel.tabsStates[0])
                }
            }
        } else {
            handleIntent(intent)
        }

        val currentTab = tabsModel.currentTab.value
        if (currentTab == null || currentTab.url == settingsModel.homePage) {
            showMenuOverlay()
        }
        com.phlox.tvwebbrowser.sofa.LumenUpdater.checkOnStart(this@MainActivity)
    }

    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "handleIntent: " + intent.data)
        if (intent.getBooleanExtra("com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB", false) &&
            tabsModel.tabsStates.isNotEmpty()) {
            if (tabsModel.currentTab.value == null) {
                changeTab(tabsModel.tabsStates[0])
            }
            navigate(intent.data.toString())
            return
        }

        openInNewTab(
            intent.data.toString(), tabsModel.tabsStates.size, needToHideMenuOverlay = true,
            navigateImmediately = true
        )
    }

    private fun openInNewTab(url: String?, index: Int = 0, needToHideMenuOverlay: Boolean = true, navigateImmediately: Boolean): WebEngine? {
        Log.d(TAG, "openInNewTab: url: $url, index: $index, needToHideMenuOverlay: $needToHideMenuOverlay, navigateImmediately: $navigateImmediately")
        if (url == null) {
            return null
        }
        val tab = WebTabState(url = url, incognito = config.incognitoMode)
        createWebView(tab) ?: return null
        tabsModel.tabsStates.add(index, tab)
        changeTab(tab)
        if (navigateImmediately) {
            navigate(url)
        }
        if (needToHideMenuOverlay && toolbarShown) {
            hideMenuOverlay(true)
        }
        return tab.webEngine
    }

    /** Wisp: recently closed tabs (address, title), newest first; this session only. */
    private val closedTabs = ArrayList<Pair<String, String>>()
    private var undoBar: View? = null

    fun clearClosedTabs() {
        closedTabs.clear()
        hideUndoBar()
    }

    private fun rememberClosed(tab: WebTabState) {
        if (!com.phlox.tvwebbrowser.Brand.phone || isOnHomePage(tab) || !tab.url.startsWith("http")) return
        closedTabs.removeAll { it.first == tab.url }
        closedTabs.add(0, Pair(tab.url, tab.title.ifBlank { tab.url }))
        while (closedTabs.size > 20) closedTabs.removeAt(closedTabs.size - 1)
    }

    private fun reopenClosed(index: Int = 0) {
        val (url, _) = closedTabs.getOrNull(index) ?: return
        closedTabs.removeAt(index)
        hideUndoBar()
        openInNewTab(url, tabsModel.tabsStates.size, needToHideMenuOverlay = false, navigateImmediately = true)
    }

    /** "Tab closed · Undo" at the bottom for 4 seconds. */
    private fun showUndoBar(text: String) {
        hideUndoBar()
        //the search was in the page that just closed
        closeFindInPage()
        val d = resources.displayMetrics.density
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, R.color.wisp_accent))
            cornerRadius = 14 * d
        }
        bar.elevation = 6 * d
        bar.setPadding((16 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        val onAccent = ContextCompat.getColor(this, R.color.wisp_on_accent)
        val t = TextView(this)
        t.text = text
        t.setTextColor(onAccent)
        t.textSize = 14f
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val undo = TextView(this)
        undo.setText(R.string.wisp_undo)
        undo.setTextColor(onAccent)
        undo.textSize = 14f
        undo.setTypeface(undo.typeface, android.graphics.Typeface.BOLD)
        undo.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        undo.setOnClickListener { reopenClosed(0) }
        bar.addView(undo)
        val lp = android.widget.RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
        lp.setMargins((12 * d).toInt(), 0, (12 * d).toInt(), (16 * d).toInt())
        vb.rlRoot.addView(bar, lp)
        undoBar = bar
        bar.postDelayed({ if (undoBar == bar) hideUndoBar() }, 4000)
    }

    private fun hideUndoBar() {
        undoBar?.let { vb.rlRoot.removeView(it) }
        undoBar = null
    }

    /** Wisp: the list of recently closed tabs. */
    private fun showRecentlyClosed() {
        if (closedTabs.isEmpty()) {
            Toast.makeText(this, R.string.wisp_no_closed_tabs, Toast.LENGTH_SHORT).show()
            return
        }
        val items = closedTabs.mapIndexed { i, (url, title) ->
            WispSheets.MenuItem(R.drawable.ic_wisp_globe, title,
                subtitle = try { Uri.parse(url).host?.removePrefix("www.") } catch (e: Exception) { url }) { reopenClosed(i) }
        }
        WispSheets.showList(this, getString(R.string.wisp_recently_closed), null, listOf(items))
    }

    private fun closeTab(tab: WebTabState?) {
        if (tab == null) return
        val tabs = tabsModel.tabsStates
        val position = tabs.indexOf(tab)
        if (position < 0) return
        rememberClosed(tab)
        val wasCurrent = tabsModel.currentTab.value == tab
        if (tabs.size == 1) {
            //closing the only tab leaves a fresh home page tab
            tabsModel.currentTab.value = null
            openInNewTab(settingsModel.homePage, 0, needToHideMenuOverlay = false, navigateImmediately = true)
        } else if (wasCurrent) {
            //show the neighbour; closing a background tab doesn't change the visible one
            tabsModel.currentTab.value = null
            changeTab(if (position > 0) tabs[position - 1] else tabs[position + 1])
        }
        tabsModel.onCloseTab(tab)
        //indexes shifted: keep the tab strip pointing at the visible tab
        tabsModel.currentTab.value?.let { vb.vTabs.current = tabsModel.tabsStates.indexOf(it) }
        hideMenuOverlay(true)
    }

    /** Tabs in the order they were last shown; only the newest ones keep their web page in memory. */
    private val recentTabs = ArrayList<WebTabState>()

    private fun changeTab(newTab: WebTabState) {
        //Wisp: the reload/stop button follows the tab shown (the next progress report corrects it)
        if (com.phlox.tvwebbrowser.Brand.phone) vb.vActionBar.setLoading(false)
        closeFindInPage()
        //Wisp: the tab being left keeps a small picture for the tab grid
        if (com.phlox.tvwebbrowser.Brand.phone) tabsModel.currentTab.value?.takeIf { it != newTab }?.let { captureThumbnail(it) }
        tabsModel.changeTab(newTab, { tab: WebTabState -> createWebView(tab) }, vb.flWebViewContainer, WebEngineCallback(newTab))
        //TVs have little memory: keep the shown tab and the one before it loaded, release the others
        //(their state is saved; they reload when opened again)
        recentTabs.remove(newTab)
        recentTabs.add(0, newTab)
        recentTabs.retainAll(tabsModel.tabsStates.toSet())
        while (recentTabs.size > LIVE_TABS) {
            val old = recentTabs.removeAt(recentTabs.size - 1)
            if (old != tabsModel.currentTab.value && old.webEngine.getView()?.isAttachedToWindow == false) {
                try { old.saveWebViewStateToFile() } catch (e: Exception) { Log.e(TAG, "save tab state", e) }
                old.trimMemory()
            }
        }
        //switching tabs shows the tab strip; it hides again when the page is scrolled down
        showToolbar()
        updateGrabCount()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState): View? {
        val webView: View
        try {
            webView = tab.webEngine.getOrCreateView(this) //WebViewEx(this, WebViewCallback(tab), AndroidJSInterface(this, viewModel, tabsModel, tab))
        } catch (e: Throwable) {
            e.printStackTrace()

            if (!config.isWebEngineGecko()) {
                val dialogBuilder = AlertDialog.Builder(this)
                    .setTitle(R.string.error)
                    .setCancelable(false)
                    .setMessage(R.string.err_webview_can_not_link)
                    .setNegativeButton(R.string.exit) { _, _ -> finish() }

                val appPackageName = "com.google.android.webview"
                val intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName"))
                val activities = packageManager.queryIntentActivities(intent, 0)
                if (activities.size > 0) {
                    dialogBuilder.setPositiveButton(R.string.find_in_apps_store) { _, _ ->
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        finish()
                    }
                }
                dialogBuilder.show()
            }
            return null
        }

        var ua = config.userAgentString.value
        if (ua?.contains("TV Bro/1.0 ") == true) {//legacy ua string - now default one should be used
            config.userAgentString.value = null
            ua = null
        }
        //Wisp: desktop only per site (menu → Desktop site); an older "every site desktop" choice is cleared
        if (com.phlox.tvwebbrowser.Brand.phone && ua != null &&
            (ua.contains("Windows NT") || ua.contains("Macintosh") || (ua.contains("X11") && !ua.contains("Android")))) {
            config.userAgentString.value = null
            ua = null
        }
        if (ua != null) {
            tab.webEngine.userAgentString = ua
        }

        (webView as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx)?.mediaLog?.onChanged = { scheduleGrabCountUpdate() }
        //Wisp: fingers only, no remote cursor
        if (com.phlox.tvwebbrowser.Brand.phone) {
            (webView as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx)?.setVirtualCursorMode(false)
        }
        return webView
    }

    // ---------------- Lumen: site data button (before the address) ----------------
    private var siteIconUrl: String? = null

    private fun updateSiteIcon(tab: WebTabState) {
        val url = tab.url
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        if (host.isNullOrEmpty() || isLumenHome(url)) {
            siteIconUrl = null
            vb.vActionBar.setSiteIcon(null)
            return
        }
        if (siteIconUrl == url) return
        siteIconUrl = url
        lifecycleScope.launch(Dispatchers.Main) {
            val icon = try { com.phlox.tvwebbrowser.singleton.FaviconsPool.get(url) } catch (e: Exception) { null }
            if (siteIconUrl == url) vb.vActionBar.setSiteIcon(icon)
        }
    }

    /** Lumen's start page is served from TV Bro's site (tvbro.phlox.dev): it is no website of its own. */
    private fun isLumenHome(url: String): Boolean = url.startsWith(Config.HOME_PAGE_URL, true) ||
            url == Config.HOME_URL_ALIAS || (try { Uri.parse(url).host } catch (e: Exception) { null }) == "tvbro.phlox.dev"

    override fun showSiteData() {
        val tab = tabsModel.currentTab.value ?: return
        if (com.phlox.tvwebbrowser.Brand.phone) {
            showPhoneSiteInfo(tab)
            return
        }
        if (isLumenHome(tab.url)) {
            Toast.makeText(this, "This is ${com.phlox.tvwebbrowser.Brand.NAME}'s start page. Open a website to manage its cookies and data.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = try { Uri.parse(tab.url) } catch (e: Exception) { null } ?: return
        val host = uri.host
        if (host.isNullOrEmpty() || uri.scheme?.startsWith("http") != true) {
            Toast.makeText(this, "This page has no site data", Toast.LENGTH_SHORT).show()
            return
        }
        val origin = uri.scheme + "://" + host + (if (uri.port > 0) ":" + uri.port else "")
        val cookies = try {
            android.webkit.CookieManager.getInstance().getCookie(tab.url)?.split(';')?.count { it.isNotBlank() } ?: 0
        } catch (e: Exception) { 0 }
        val wv = tab.webEngine.getView() as? android.webkit.WebView
        fun show(usage: Long) {
            val sizeText = if (usage >= 0) android.text.format.Formatter.formatShortFileSize(this, usage) else "unknown"
            val items = arrayOf(
                "🍪  Delete cookies ($cookies) - logs you out of this site",
                "🗄  Delete site storage (local storage $sizeText) - saved settings, offline data",
                "🧹  Delete cached files (all sites: WebView keeps one shared cache)",
                "✖  Delete all data of $host"
            )
            AlertDialog.Builder(this)
                .setTitle(host.removePrefix("www."))
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> { clearSiteCookies(tab.url); tab.webEngine.reload()
                            Toast.makeText(this, "Cookies of $host deleted", Toast.LENGTH_SHORT).show() }
                        1 -> { clearSiteStorage(wv, origin); Toast.makeText(this, "Storage of $host deleted", Toast.LENGTH_SHORT).show() }
                        2 -> { wv?.clearCache(true); Toast.makeText(this, "Cached files deleted", Toast.LENGTH_SHORT).show() }
                        3 -> { clearSiteCookies(tab.url); clearSiteStorage(wv, origin)
                            Toast.makeText(this, "All data of $host deleted", Toast.LENGTH_SHORT).show() }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        if (wv == null) { show(-1); return }
        //size of the page's localStorage (IndexedDB and the cache can't be measured synchronously)
        wv.evaluateJavascript(
            "(function(){var s=0;try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);s+=k.length+(localStorage.getItem(k)||'').length}}catch(e){}return String(s*2)})()"
        ) { res -> show(res?.trim('"')?.toLongOrNull() ?: -1) }
    }

    /** Deletes the page's own storage (localStorage, sessionStorage, IndexedDB, Cache API, service workers), then reloads. */
    private fun clearSiteStorage(wv: android.webkit.WebView?, origin: String) {
        try { android.webkit.WebStorage.getInstance().deleteOrigin(origin) } catch (e: Exception) {}
        wv?.evaluateJavascript("""(function(){try{localStorage.clear()}catch(e){}try{sessionStorage.clear()}catch(e){}
            try{indexedDB.databases().then(function(l){l.forEach(function(d){indexedDB.deleteDatabase(d.name)})})}catch(e){}
            try{caches.keys().then(function(k){k.forEach(function(n){caches.delete(n)})})}catch(e){}
            try{navigator.serviceWorker.getRegistrations().then(function(r){r.forEach(function(x){x.unregister()})})}catch(e){}
            setTimeout(function(){location.reload()},400)})()""", null)
    }

    private fun onWebViewUpdated(tab: WebTabState) {
        updateSiteIcon(tab)
        vb.ibBack.isEnabled = tab.webEngine.canGoBack() == true
        vb.ibForward.isEnabled = tab.webEngine.canGoForward() == true

        val debandOn = (tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx)?.pageDeband == true
        if (debandOn) vb.ibDeband.setColorFilter(Color.parseColor("#20C997")) else vb.ibDeband.clearColorFilter()
        val desktopOn = config.isDesktopSite(try { URL(tab.url).host } catch (e: Exception) { null })
        if (desktopOn) vb.ibDesktopSite.setColorFilter(Color.parseColor("#20C997")) else vb.ibDesktopSite.clearColorFilter()

        val adblockEnabled = isAdBlockOnForTab(tab)
        vb.ibAdBlock.setImageResource(if (adblockEnabled) R.drawable.ic_adblock_on else R.drawable.ic_adblock_off)
        vb.tvBlockedAdCounter.visibility = if (adblockEnabled && tab.blockedAds != 0) View.VISIBLE else View.GONE
        vb.tvBlockedAdCounter.text = tab.blockedAds.toString()

        vb.tvBlockedPopupCounter.visibility = if (tab.blockedPopups != 0) View.VISIBLE else View.GONE
        vb.tvBlockedPopupCounter.text = tab.blockedPopups.toString()
    }

    private fun onDownloadRequested(url: String, referer: String, originalDownloadFileName: String, userAgent: String?, mimeType: String? = null,
                                    operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
                                    base64BlobData: String? = null, stream: InputStream?, size: Long = 0L) {
        //Wisp: a WebM video or WebP image can be saved as a GIF instead (like ezgif)
        if (com.phlox.tvwebbrowser.Brand.phone && base64BlobData == null && stream == null && url.startsWith("http") &&
            WispGif.isConvertible(url, originalDownloadFileName, mimeType)) {
            WispGif.offer(this, url, originalDownloadFileName, mimeType, userAgent, referer) {
                startDownloadNow(url, referer, originalDownloadFileName, userAgent, mimeType, operationAfterDownload, null, null, size)
            }
            return
        }
        startDownloadNow(url, referer, originalDownloadFileName, userAgent, mimeType, operationAfterDownload, base64BlobData, stream, size)
    }

    private fun startDownloadNow(url: String, referer: String, originalDownloadFileName: String, userAgent: String?, mimeType: String?,
                                 operationAfterDownload: Download.OperationAfterDownload,
                                 base64BlobData: String?, stream: InputStream?, size: Long) {
        downloadIntent = Download(url, originalDownloadFileName, null, operationAfterDownload,
            mimeType, referer, userAgent, base64BlobData, stream, size)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                MY_PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false)) {
            //asked once only: the notification just shows the progress, the download works without it
            prefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                MY_PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS
            )
        } else {
            startDownload()
        }
    }

    /** Downloads asked for while the download service wasn't connected yet (e.g. right after returning to this screen). */
    private val pendingDownloads = ArrayList<Download>()

    private fun startDownload() {
        val download = this.downloadIntent ?: return
        this.downloadIntent = null
        //private mode runs in its own process; the download service runs in the main one and can't
        //be used directly from here (downloads used to wait forever): hand the download over
        if (!com.phlox.tvwebbrowser.sofa.SofaVpn.isMainProcess(this)) {
            startDownloadInMainProcess(download)
            return
        }
        val service = downloadService
        if (service == null) {
            //the connection to the service is (re)made in onStart: start it as soon as it is there
            pendingDownloads.add(download)
            if (bindService(Intent(this, DownloadService::class.java), downloadServiceConnection, Context.BIND_AUTO_CREATE)) downloadServiceBound = true
            return
        }
        service.startDownload(download)
        onDownloadStarted(download.filename)
    }

    /** The identity the tab's page was loaded with: downloads use it too (Cloudflare ties its pass cookie to it). */
    private fun pageUserAgent(tab: WebTabState): String? =
        (tab.webEngine.getView() as? android.webkit.WebView)?.settings?.userAgentString ?: tab.webEngine.userAgentString

    private var downloadServiceBound = false

    private fun startDownloadInMainProcess(download: Download) {
        lifecycleScope.launch {
            val dataFile = withContext(Dispatchers.IO) {
                try {
                    //the page's own data (blob: / data: files) goes over as a temporary file
                    val bytes: ByteArray? = download.base64BlobData?.let {
                        android.util.Base64.decode(it.substringAfter("base64,", it), android.util.Base64.DEFAULT)
                    }
                    val stream = download.stream
                    if (bytes == null && stream == null) null else {
                        val dir = File(cacheDir, "handover").apply { mkdirs() }
                        val f = File.createTempFile("dl", ".part", dir)
                        f.outputStream().use { out -> if (bytes != null) out.write(bytes) else stream!!.use { it.copyTo(out) } }
                        f
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "download handover", e)
                    null
                }
            }
            if ((download.base64BlobData != null || download.stream != null) && dataFile == null) {
                Toast.makeText(this@MainActivity, R.string.wisp_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val cookies = try { CookieManager.getInstance().getCookie(download.url) } catch (e: Exception) { null }
            val intent = Intent(this@MainActivity, DownloadService::class.java)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_URL, download.url)
                .putExtra(DownloadService.EXTRA_NAME, download.filename)
                .putExtra(DownloadService.EXTRA_MIME, download.mimeType)
                .putExtra(DownloadService.EXTRA_REFERER, download.referer)
                .putExtra(DownloadService.EXTRA_UA, download.userAgentString)
                .putExtra(DownloadService.EXTRA_SIZE, download.size)
                .putExtra(DownloadService.EXTRA_INSTALL,
                    download.operationAfterDownload == Download.OperationAfterDownload.INSTALL)
                .putExtra(DownloadService.EXTRA_COOKIES, cookies)
                .putExtra(DownloadService.EXTRA_DATA_FILE, dataFile?.absolutePath)
            try {
                startService(intent)
                onDownloadStarted(download.filename)
            } catch (e: Exception) {
                Log.e(TAG, "download handover", e)
                dataFile?.delete()
                Toast.makeText(this@MainActivity, getString(R.string.wisp_download_error_s, e.message ?: ""),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startPendingDownloads() {
        val service = downloadService ?: return
        val list = ArrayList(pendingDownloads)
        pendingDownloads.clear()
        for (d in list) {
            service.startDownload(d)
            onDownloadStarted(d.filename)
        }
    }

    override fun onTrimMemory(level: Int) {
        for (tab in tabsModel.tabsStates) {
            if (!tab.selected) {
                tab.trimMemory()
            }
        }
        super.onTrimMemory(level)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        if (voiceSearchHelper.processPermissionsResult(requestCode, permissions, grantResults)) {
            return
        }
        if (tabsModel.currentTab.value?.webEngine?.onPermissionsResult(requestCode, permissions, grantResults) == true) return
        //notifications allowed or not (or the question dismissed): download anyway
        if (requestCode == MY_PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS) {
            startDownload()
            return
        }
        if (grantResults.isEmpty()) return
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startDownload()
                }
            }
            MY_PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startDownload()
                }
            }
            com.phlox.tvwebbrowser.sofa.LumenFolderPicker.REQUEST_CODE_STORAGE -> {
                val picked = onDownloadFolderPicked
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED && picked != null) {
                    com.phlox.tvwebbrowser.sofa.LumenFolderPicker(this) { picked() }.show()
                }
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (voiceSearchHelper.processActivityResult(requestCode, resultCode, data)) {
            return
        }
        when (requestCode) {
            WispGif.REQUEST_PICK -> if (resultCode == Activity.RESULT_OK) {
                data?.data?.let { WispGif.onPicked(this, it) }
            }
            PICK_FILE_REQUEST_CODE -> {
                tabsModel.currentTab.value?.webEngine?.onFilePicked(resultCode, data)
            }
            REQUEST_CODE_HISTORY_ACTIVITY -> if (resultCode == Activity.RESULT_OK) {
                val url = data?.getStringExtra(HistoryActivity.KEY_URL)
                if (url != null) {
                    navigate(url)
                }
                hideMenuOverlay()
            }
            REQUEST_CODE_UNKNOWN_APP_SOURCES -> {}
            REQUEST_CODE_VPN_PERMISSION -> if (resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch { com.phlox.tvwebbrowser.sofa.SofaVpn.connect(applicationContext) }
            } else {
                Toast.makeText(this, R.string.sofa_vpn_permission_denied, Toast.LENGTH_LONG).show()
            }
            REQUEST_CODE_DOWNLOADS -> if (resultCode == Activity.RESULT_OK) {
                data?.getStringExtra(DownloadsActivity.EXTRA_DOWNLOAD_URL)?.let { downloadFromLink(it) }
            }
            REQUEST_CODE_PICK_DOWNLOAD_FOLDER -> if (resultCode == Activity.RESULT_OK) {
                data?.data?.let { onDownloadFolderResult(it) }
            }
            REQUEST_CODE_VPN_IMPORT_FILE -> if (resultCode == Activity.RESULT_OK) {
                data?.data?.let { onVpnConfigFilePicked(it) }
            }
            REQUEST_CODE_VPN_IMPORT_ANY -> if (resultCode == Activity.RESULT_OK) {
                data?.data?.let { onVpnFileOrArchivePicked(it) }
            }
            REQUEST_CODE_BACKUP_SAVE -> if (resultCode == Activity.RESULT_OK) data?.data?.let { writeBackup(it) } else backupPassword = null
            REQUEST_CODE_BACKUP_OPEN -> if (resultCode == Activity.RESULT_OK) data?.data?.let { readBackup(it) }
            WispSecurity.REQUEST_CONFIRM_CREDENTIAL -> WispSecurity.onCredentialResult(resultCode == Activity.RESULT_OK)

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onStart() {
        super.onStart()
        //private mode's process can't use the service directly (see startDownloadInMainProcess)
        downloadServiceBound = com.phlox.tvwebbrowser.sofa.SofaVpn.isMainProcess(this) &&
            bindService(Intent(this, DownloadService::class.java), downloadServiceConnection, Context.BIND_AUTO_CREATE)
        //back in the same session: nothing to clear later
        if (processStarted) prefs.edit().putBoolean(PREF_CLEAR_PENDING, false).apply()
    }

    // ---------------- Wisp: app lock ----------------

    private var lockCover: View? = null

    private fun lockIfNeeded() {
        if (!com.phlox.tvwebbrowser.Brand.phone || !WispSecurity.needsUnlock()) { removeLockCover(); return }
        if (!WispSecurity.deviceSecure(this)) { removeLockCover(); return }
        showLockCover()
        if (!WispSecurity.prompting) unlock()
    }

    private fun unlock() {
        WispSecurity.authenticate(this, getString(R.string.wisp_unlock_title)) { ok -> if (ok) removeLockCover() }
    }

    /** Covers the browser until it is unlocked (also hides it in the recent apps list). */
    private fun showLockCover() {
        if (lockCover != null) return
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(ContextCompat.getColor(this, R.color.wisp_bg))
        box.isClickable = true
        box.isFocusable = true
        val icon = android.widget.ImageView(this)
        icon.setImageResource(R.drawable.ic_wisp_lock)
        icon.setColorFilter(ContextCompat.getColor(this, R.color.wisp_icon))
        box.addView(icon, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
        val t = TextView(this)
        t.setText(R.string.wisp_locked)
        t.textSize = 17f
        t.setTextColor(ContextCompat.getColor(this, R.color.wisp_text))
        t.setPadding(0, (14 * d).toInt(), 0, (20 * d).toInt())
        box.addView(t)
        val b = TextView(this)
        b.setText(R.string.wisp_unlock)
        b.textSize = 15f
        b.setTextColor(ContextCompat.getColor(this, R.color.wisp_on_accent))
        b.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, R.color.wisp_accent)); cornerRadius = 22 * d
        }
        b.setPadding((28 * d).toInt(), (12 * d).toInt(), (28 * d).toInt(), (12 * d).toInt())
        b.setOnClickListener { unlock() }
        box.addView(b)
        val lp = android.widget.RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        vb.rlRoot.addView(box, lp)
        box.elevation = 100 * d
        lockCover = box
    }

    private fun removeLockCover() {
        lockCover?.let { vb.rlRoot.removeView(it) }
        lockCover = null
    }

    /** Recent apps shows no picture of the pages while the lock is on (Android 13+). */
    fun applyLockToRecents() {
        if (com.phlox.tvwebbrowser.Brand.phone && Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!config.wispLockApp)
        }
    }

    /** Saved passwords: open straight away (no fingerprint, PIN or other lock - as the user wants). */
    fun openPasswordManager() {
        com.phlox.tvwebbrowser.sofa.SofaPasswords.showManager(this)
    }

    // ---------------- Wisp: back up and restore ----------------

    private var backupPassword: String? = null

    /** Asks for a password (twice), then where to save the backup file. */
    fun startBackup() {
        passwordDialog(R.string.wisp_backup_title, R.string.wisp_backup_password_hint, confirm = true) { pw ->
            backupPassword = pw
            val name = "Wisp-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) + ".wispbackup"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name)
            try { startActivityForResult(intent, REQUEST_CODE_BACKUP_SAVE) } catch (e: Exception) {
                Toast.makeText(this, R.string.sofa_vpn_no_file_picker, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startRestore() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(intent, REQUEST_CODE_BACKUP_OPEN) } catch (e: Exception) {
            Toast.makeText(this, R.string.sofa_vpn_no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun writeBackup(uri: Uri) {
        val pw = backupPassword ?: return
        backupPassword = null
        lifecycleScope.launch {
            val ok = try {
                val json = WispSecurity.buildBackup(this@MainActivity).toString()
                val file = withContext(Dispatchers.Default) { WispSecurity.encrypt(json, pw) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.toByteArray(Charsets.UTF_8)) } != null
                }
            } catch (e: Exception) {
                Log.e(TAG, "backup", e)
                false
            }
            Toast.makeText(this@MainActivity, if (ok) R.string.wisp_backup_done else R.string.wisp_backup_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun readBackup(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size < 60_000_000 }?.toString(Charsets.UTF_8) }
                } catch (e: Exception) { null }
            }
            if (file == null || !file.contains("wispBackup")) {
                Toast.makeText(this@MainActivity, R.string.wisp_restore_not_backup, Toast.LENGTH_LONG).show()
                return@launch
            }
            passwordDialog(R.string.wisp_restore_title, R.string.wisp_restore_password_hint, confirm = false) { pw ->
                lifecycleScope.launch {
                    val json = withContext(Dispatchers.Default) { WispSecurity.decrypt(file, pw) }
                    if (json == null) {
                        Toast.makeText(this@MainActivity, R.string.wisp_restore_wrong_password, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val r = try { WispSecurity.restore(this@MainActivity, org.json.JSONObject(json)) } catch (e: Exception) {
                        Log.e(TAG, "restore", e)
                        Toast.makeText(this@MainActivity, R.string.wisp_backup_failed, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.wisp_restore_done_title)
                        .setMessage(getString(R.string.wisp_restore_done_s, r.bookmarks, r.passwords, r.settings) +
                                (if (r.pages > 0) "\n" + getString(R.string.wisp_restore_pages_s, r.pages) else "") +
                                (if (r.vpn) "\n" + getString(R.string.wisp_restore_vpn) else ""))
                        .setCancelable(false)
                        .setPositiveButton(R.string.wisp_restart) { _, _ ->
                            //settings are read at start: Wisp starts again
                            val i = Intent(this@MainActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            startActivity(i)
                            Process.killProcess(Process.myPid())
                        }
                        .show()
                }
            }
        }
    }

    private fun passwordDialog(titleRes: Int, hintRes: Int, confirm: Boolean, done: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
        fun field(hint: Int) = android.widget.EditText(this).apply {
            setHint(hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            box.addView(this)
        }
        val p1 = field(hintRes)
        val p2 = if (confirm) field(R.string.wisp_backup_password_again) else null
        val msg = TextView(this)
        msg.setText(if (confirm) R.string.wisp_backup_note else R.string.wisp_restore_note)
        msg.textSize = 13f
        msg.setPadding(0, (10 * d).toInt(), 0, 0)
        box.addView(msg)
        val dlg = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pw = p1.text.toString()
                when {
                    pw.length < 6 -> p1.error = getString(R.string.wisp_backup_password_short)
                    p2 != null && p2.text.toString() != pw -> p2.error = getString(R.string.wisp_backup_password_mismatch)
                    else -> { dlg.dismiss(); done(pw) }
                }
            }
        }
        dlg.show()
    }

    override fun onStop() {
        if (com.phlox.tvwebbrowser.Brand.phone) WispSecurity.leftAt = android.os.SystemClock.elapsedRealtime()
        super.onStop()
        if (downloadServiceBound) {
            try { unbindService(downloadServiceConnection) } catch (e: Exception) {}
            downloadServiceBound = false
        }
        downloadService = null
        //On a TV the browser is rarely "closed": it's left with Home and Android ends it later.
        //Remember that clearing is due; it happens on the next fresh start (see loadState).
        if (!isFinishing && !config.incognitoMode &&
            (config.lumenClearHistoryOnExit || config.lumenClearCookiesOnExit)) {
            prefs.edit().putBoolean(PREF_CLEAR_PENDING, true).apply()
        }
    }

    override fun onResume() {
        reloadSettingsFromDisk()
        super.onResume()
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(mConnectivityChangeReceiver, intentFilter)
        tabsModel.currentTab.value?.webEngine?.onResume()
    }

    override fun onPause() {
        unregisterReceiver(mConnectivityChangeReceiver)
        tabsModel.currentTab.value?.apply {
            webEngine.onPause()
            onPause()
            if (!sessionCleared) runBlocking { tabsModel.saveTab(this@apply) }
        }
        super.onPause()
    }

    private fun toggleAdBlockForTab() {
        tabsModel.currentTab.value?.apply {
            val currentState = isAdBlockOnForTab(this)
            val newState = !currentState
            adblock = newState
            webEngine.onUpdateAdblockSetting(newState)
            onWebViewUpdated(this)
            refresh()
        }
    }

    private suspend fun showPopupBlockOptions() {
        val tab = tabsModel.currentTab.value ?: return
        val currentHostConfig = tabsModel.findHostConfig(tab,false)
        val currentBlockPopupsLevelValue = currentHostConfig?.popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE
        val hostName = currentHostConfig?.hostName ?: try { URL(tab.url).host } catch (e: Exception) { "" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.block_popups_s, hostName))
            .setSingleChoiceItems(R.array.popup_blocking_level, currentBlockPopupsLevelValue) {
                    dialog, itemId -> lifecycleScope.launch {
                        tabsModel.changePopupBlockingLevel(itemId, tab)
                        dialog.dismiss()
                    }
            }
            .show()
    }

    fun navigate(url: String) {
        Log.d(TAG, "navigate: $url")
        vb.vActionBar.setAddressBoxTextColor(ContextCompat.getColor(this@MainActivity, R.color.default_url_color))
        val tab = tabsModel.currentTab.value
        if (tab != null) {
            tab.url = url
            tab.webEngine.loadUrl(url)
        } else {
            openInNewTab(url, 0, needToHideMenuOverlay = true, navigateImmediately = true)
        }
    }

    override fun search(aText: String) {
        var text = aText
        val trimmedLowercased = text.trim { it <= ' ' }.lowercase(Locale.ROOT)
        if (Patterns.WEB_URL.matcher(text).matches() || trimmedLowercased.startsWith("http://") || trimmedLowercased.startsWith("https://")) {
            if (!text.lowercase(Locale.ROOT).contains("://")) {
                text = "https://$text"
            }
            navigate(text)
        } else {
            var query: String? = null
            try {
                query = URLEncoder.encode(text, "utf-8")
            } catch (e1: UnsupportedEncodingException) {
                e1.printStackTrace()
                Utils.showToast(this, R.string.error)
                return
            }

            val searchUrl = config.searchEngineURL.value.replace("[query]", query!!)
            navigate(searchUrl)
        }
    }

    override fun goHome() {
        navigate(settingsModel.homePage)
        hideMenuOverlay()
    }

    override fun toggleIncognitoMode() {
        toggleIncognitoMode(true)
    }

    private fun toggleIncognitoMode(andSwitchProcess: Boolean) = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "toggleIncognitoMode andSwitchProcess: $andSwitchProcess")
        val becomingIncognitoMode = !config.incognitoMode
        if (!com.phlox.tvwebbrowser.Brand.phone) vb.progressBarGeneric.visibility = View.VISIBLE
        if (!becomingIncognitoMode) {
            if (!config.isWebEngineGecko()) {
                withContext(Dispatchers.IO) {
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }

                WebEngineFactory.clearCache(this@MainActivity)
            }

            tabsModel.onCloseAllTabs().join()
            tabsModel.currentTab.value = null

            if (!config.isWebEngineGecko()) {
                viewModel.clearIncognitoData().join()
            }
        }
        vb.progressBarGeneric.visibility = View.GONE
        config.incognitoMode = becomingIncognitoMode
        if (andSwitchProcess) {
            switchProcess(becomingIncognitoMode)
        }
    }

    private fun switchProcess(incognitoMode: Boolean, intentDataToCopy: Bundle? = null, viewData: Uri? = null) {
        Log.d(TAG, "switchProcess incognitoMode: $incognitoMode")
        val activityClass = if (incognitoMode) IncognitoModeMainActivity::class.java
        else MainActivity::class.java
        val intent = Intent(this@MainActivity, activityClass)
        if (viewData != null) {
            intent.action = Intent.ACTION_VIEW
            intent.data = viewData
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(KEY_PROCESS_ID_TO_KILL, Process.myPid())
        intentDataToCopy?.let {
            intent.putExtras(it)
        }
        startActivity(intent)
        exitProcess(0)
    }

    fun toggleMenu() {
        if (vb.rlActionBar.hasFocus()) {
            hideMenuOverlay()
        } else {
            showMenuOverlay()
        }
    }

    val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            backNavigationEventsAdapter.dispatchSystemBackNavigationEvent()
        }
    }

    private val backNavigationEventsAdapter = BackNavigationEventsAdapter(
        onEmulatedBackEvent = {
            if (!hideSoftwareKeyboardIfVisible()) {
                handleBackNavigation()
            }
        }
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val localCallback = window.callback
        window.callback = object : Window.Callback by localCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                //Log.d(TAG, "dispatchKeyEvent event: $event")
                //Lumen: the remote's pointer is over the top bar
                if (vb.vToolbarCursor.active && vb.vToolbarCursor.handleKey(event)) {
                    if (event.action == KeyEvent.ACTION_UP &&
                        (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                        //a click into the address bar: put the pointer away so the keyboard can be used
                        vb.rlRoot.post {
                            if (currentFocus is android.widget.EditText) vb.vToolbarCursor.close(notify = false)
                        }
                    }
                    return true
                }
                //Lumen: selecting text on the page: OK copies, Back cancels, arrows move the end
                if (textSelecting && handleSelectionKey(event)) return true
                backNavigationEventsAdapter.dispatchKeyEvent(event)

                val keyCode = if (event.keyCode != 0) event.keyCode else event.scanCode
                val keyCodeBackNavigation = keyCode == KeyEvent.KEYCODE_ESCAPE ||
                        keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK
                val shortcutMgr = ShortcutMgr.getInstance()
                val currentTab = tabsModel.currentTab.value
                if (!keyCodeBackNavigation &&
                    shortcutMgr.handle(event, this@MainActivity, currentTab)) {
                    return true
                }

                return localCallback.dispatchKeyEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                //Log.d(TAG, "dispatchGenericMotionEvent event: $event")
                if (backNavigationEventsAdapter.dispatchGenericMotionEvent(event)) {
                    return true
                }
                return localCallback.dispatchGenericMotionEvent(event)
            }
        }
    }

    /**
     * When the IME is visible, the first back press should only dismiss it; this mirrors standard
     * Android behavior and avoids navigating away while the user is typing.
     *
     * @return true if the IME was visible and a hide was requested.
     */
    private fun hideSoftwareKeyboardIfVisible(): Boolean {
        val root = window.decorView.rootView
        val insets = ViewCompat.getRootWindowInsets(root) ?: return false
        if (!insets.isVisible(WindowInsetsCompat.Type.ime())) {
            return false
        }
        val view = currentFocus ?: root
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        return true
    }

    /** Wisp: a tap on the page (outside the top bar) ends typing an address. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (com.phlox.tvwebbrowser.Brand.phone) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && vb.vActionBar.isAddressEditing()) {
                val r = android.graphics.Rect()
                vb.rlActionBar.getGlobalVisibleRect(r)
                //(address suggestions are a separate window: touches there don't arrive here)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    vb.vActionBar.endAddressEditing()
                    //this tap only closes typing (as in Chrome): it doesn't press a link on the page
                    swallowingTouch = true
                    return true
                }
            }
            if (swallowingTouch) {
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                    ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swallowingTouch = false
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private var swallowingTouch = false

    private fun handleBackNavigation() {
        //Wisp: Back while the address field is still in typing mode ends it (stays on the page)
        if (com.phlox.tvwebbrowser.Brand.phone && vb.vActionBar.isAddressEditing()) {
            vb.vActionBar.endAddressEditing()
            return
        }
        Log.d(TAG, "handleBackNavigation")
        if (closeFindInPage()) return
        if (!com.phlox.tvwebbrowser.Brand.phone &&
            tabsModel.currentTab.value?.webEngine?.isVirtualCursorMode() == false) {
            tabsModel.currentTab.value?.webEngine?.setVirtualCursorMode(true)
            backNavigationEventsAdapter.gameControllersLongPressBForBackNavigation = false
            return
        }

        if (vb.vCursorMenu.isVisible) {
            vb.vCursorMenu.close(CursorMenuView.CloseAnimation.ROTATE_OUT)
        } else if (vb.flWebViewContainer.cursorDrawerDelegate.canHandleBackNavigation()) {
            vb.flWebViewContainer.cursorDrawerDelegate.handleBackNavigation()
        } else if (isFullscreen) {
            tabsModel.currentTab.value?.webEngine?.hideFullscreenView()
        } else if (vb.rlActionBar.hasFocus()) {
            //Back while in the top bar returns to the page
            hideMenuOverlay()
        } else {
            navigateBack()
        }
    }

    // ---------------- Lumen: long press = select & copy / paste ----------------
    private var textSelecting = false
    private var selectionIgnoreUp = false
    private var selectionBanner: android.widget.TextView? = null
    private var selectionLastX = -1f
    private var selectionLastY = -1f

    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("${com.phlox.tvwebbrowser.Brand.NAME}", text))
        val shown = if (text.length > 60) text.take(57) + "…" else text
        Toast.makeText(this, "Copied: $shown", Toast.LENGTH_SHORT).show()
    }

    private fun pageWebView(engine: com.phlox.tvwebbrowser.webengine.WebEngine?): android.webkit.WebView? =
        engine?.getView() as? android.webkit.WebView

    private fun onPageLongPress(engine: com.phlox.tvwebbrowser.webengine.WebEngine, result: String): Boolean {
        val o = try { org.json.JSONObject(result) } catch (e: Exception) { return false }
        val wv = pageWebView(engine) ?: return false
        when (o.optString("kind")) {
            "edit" -> {
                val text = clipboardText()
                if (text == null) {
                    Toast.makeText(this, "Nothing copied yet", Toast.LENGTH_SHORT).show()
                } else {
                    wv.evaluateJavascript("window.__lumenPaste(" + org.json.JSONObject.quote(text) + ")", null)
                    Toast.makeText(this, "Pasted", Toast.LENGTH_SHORT).show()
                }
            }
            "text" -> startTextSelection()
            "link" -> o.optString("href").takeIf { it.isNotEmpty() }?.let { copyToClipboard(it) }
            else -> {}
        }
        return true
    }

    private fun startTextSelection() {
        textSelecting = true
        selectionIgnoreUp = true //the release of the long press itself
        val d = vb.flWebViewContainer.cursorDrawerDelegate
        selectionLastX = d.cursorPosition.x
        selectionLastY = d.cursorPosition.y
        if (selectionBanner == null) {
            selectionBanner = android.widget.TextView(this).apply {
                text = "Move to select more  ·  OK copies  ·  Back cancels"
                setTextColor(Color.WHITE)
                textSize = 16f
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 22 * resources.displayMetrics.density
                    setColor(0xE00F1D20.toInt())
                }
                elevation = 30 * resources.displayMetrics.density
            }
            val lp = android.widget.RelativeLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            lp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
            lp.bottomMargin = (32 * resources.displayMetrics.density).toInt()
            vb.rlRoot.addView(selectionBanner, lp)
        }
        selectionBanner?.visibility = View.VISIBLE
        uiHandler.removeCallbacks(selectionTicker)
        uiHandler.postDelayed(selectionTicker, 60)
    }

    /** While selecting: the end of the selection follows the pointer. */
    private val selectionTicker = object : Runnable {
        override fun run() {
            if (!textSelecting) return
            val d = vb.flWebViewContainer.cursorDrawerDelegate
            val x = d.cursorPosition.x
            val y = d.cursorPosition.y
            if (x != selectionLastX || y != selectionLastY) {
                selectionLastX = x
                selectionLastY = y
                val wv = pageWebView(tabsModel.currentTab.value?.webEngine)
                wv?.evaluateJavascript("window.__lumenSelExtend && window.__lumenSelExtend(${x.toInt()}, ${y.toInt()}, ${wv.width})", null)
            }
            uiHandler.postDelayed(this, 60)
        }
    }

    private fun endTextSelection(copy: Boolean) {
        textSelecting = false
        uiHandler.removeCallbacks(selectionTicker)
        selectionBanner?.visibility = View.GONE
        val wv = pageWebView(tabsModel.currentTab.value?.webEngine) ?: return
        wv.evaluateJavascript("window.__lumenSelCopy ? window.__lumenSelCopy(${!copy}) : ''") { res ->
            if (!copy) return@evaluateJavascript
            val text = try { org.json.JSONArray("[$res]").getString(0) } catch (e: Exception) { "" }
            if (text.isNotBlank()) copyToClipboard(text.trim())
        }
    }

    private fun handleSelectionKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (selectionIgnoreUp) selectionIgnoreUp = false else endTextSelection(copy = true)
                } else if (event.repeatCount == 0) {
                    selectionIgnoreUp = false
                }
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) endTextSelection(copy = false)
                return true
            }
        }
        return false
    }

    /** OK held on a text field of the app (address bar, settings): paste what was copied last. */
    private fun pasteIntoFieldAt(x: Float, y: Float): Boolean {
        val field = findEditTextAt(vb.rlRoot, x, y) ?: return false
        val text = clipboardText()
        if (text == null) {
            Toast.makeText(this, "Nothing copied yet", Toast.LENGTH_SHORT).show()
            return true
        }
        field.requestFocus()
        val editable = field.text
        if (field.id == R.id.etUrl) {
            //the address bar shows the current address: replace it
            field.setText(text.trim())
            field.setSelection(field.text.length)
        } else {
            val s = field.selectionStart.coerceAtLeast(0)
            val e = field.selectionEnd.coerceAtLeast(0)
            editable.replace(minOf(s, e), maxOf(s, e), text)
        }
        Toast.makeText(this, "Pasted", Toast.LENGTH_SHORT).show()
        return true
    }

    /** The visible text field at ([x], [y]) in [root]'s coordinates (screen positions, so sliding bars count). */
    private fun findEditTextAt(root: android.view.ViewGroup, x: Float, y: Float): android.widget.EditText? {
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val sx = rootLoc[0] + x
        val sy = rootLoc[1] + y
        val loc = IntArray(2)
        var found: android.widget.EditText? = null
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE || found != null) return
            if (v is android.widget.EditText) {
                if (!v.isEnabled || !v.isShown) return
                v.getLocationOnScreen(loc)
                if (sx >= loc[0] && sx < loc[0] + v.width && sy >= loc[1] && sy < loc[1] + v.height) found = v
                return
            }
            if (v is android.view.ViewGroup) for (k in v.childCount - 1 downTo 0) walk(v.getChildAt(k))
        }
        walk(root)
        return found
    }

    // ---------------- Lumen: auto-hiding top bar ----------------
    private var toolbarAutoHidden = false
    private var scrollDownSinceShown = 0
    private var toolbarShownByUserAt = 0L
    private var hideScheduledForThisLoad = false
    private val toolbarHandler = Handler(android.os.Looper.getMainLooper())
    private val hideToolbarAfterLoad = Runnable {
        //don't hide a bar the user brought back a moment ago
        if (System.currentTimeMillis() - toolbarShownByUserAt > 4000) autoHideToolbar()
    }

    /** The page pointer reached the top: it continues over the bar (buttons, tabs, ✕) like a mouse. */
    private fun enterToolbarPointer(pageX: Float, dirX: Int, speedX: Float, speedY: Float) {
        //just above the page's top edge, where the page pointer was; if the bar is hidden it
        //slides in and the pointer rides down with it (see setToolbarShown)
        val page = vb.flWebViewContainer
        val y = page.top + page.translationY - 1f
        vb.vToolbarCursor.show(pageX + page.left, y, dirX, -1, speedX, speedY)
        showToolbar()
        toolbarShownByUserAt = System.currentTimeMillis()
    }

    /** Back to the page pointer, at [x] just below the bar (or where it was). */
    private fun returnPointerToPage(x: Float?, dirX: Int = 0, dirY: Int = 0, speedX: Float = 0f, speedY: Float = 0f) {
        val engine = tabsModel.currentTab.value?.webEngine ?: return
        val delegate = engine.getCursorDrawerDelegate()
        engine.getView()?.requestFocus()
        if (delegate == null) return
        if (x != null) {
            //continues at the page's top edge with the same speed: one smooth movement
            delegate.continueMove(x - vb.flWebViewContainer.left, 0f, dirX, dirY, speedX, speedY)
        } else {
            delegate.animateAppearing()
        }
    }

    private fun canAutoHideToolbar(): Boolean {
        if (!config.lumenAutoHideToolbar || isFullscreen) return false
        if (vb.vToolbarCursor.active) return false
        val tab = tabsModel.currentTab.value ?: return false
        if (isOnHomePage(tab)) return false
        //the user is using the bar (typing an address, picking a button)
        if (vb.rlActionBar.hasFocus()) return false
        return true
    }

    private fun autoHideToolbar() {
        if (!canAutoHideToolbar() || !toolbarShown) return
        toolbarAutoHidden = true
        setToolbarShown(false)
    }

    /** Shows the top bar again; it stays until the page is scrolled down. */
    fun showToolbar() {
        toolbarHandler.removeCallbacks(hideToolbarAfterLoad)
        scrollDownSinceShown = 0
        if (isFullscreen) return
        toolbarAutoHidden = false
        if (!toolbarShown) setToolbarShown(true)
    }

    // The top bar slides in and out. The page below it is never resized while the bar moves (that
    // re-layout made the old version jerky and could flicker): when hiding, the page first grows
    // downwards, off screen, and both slide up together; when showing, both slide down and the page
    // shrinks at the bottom, off screen, only when the slide has finished.
    private var toolbarShown = true
    private var toolbarAnimator: android.animation.ValueAnimator? = null
    private var toolbarMovingUntil = 0L

    /** Wisp (phone): the bar comes back when scrolling up and doesn't hide by itself after loading. */
    private val phoneBar get() = com.phlox.tvwebbrowser.Brand.phone

    private fun setPageTopMargin(px: Int) {
        val lp = vb.flWebViewContainer.layoutParams as android.view.ViewGroup.MarginLayoutParams
        if (lp.topMargin != px) {
            lp.topMargin = px
            vb.flWebViewContainer.layoutParams = lp
        }
    }

    /** Puts bar and page in their final place at once (no animation). */
    private fun applyToolbarLayout(show: Boolean) {
        toolbarAnimator?.cancel()
        toolbarAnimator = null
        val h = vb.rlActionBar.height
        vb.rlActionBar.visibility = if (show) View.VISIBLE else View.INVISIBLE
        vb.rlActionBar.translationY = if (show) 0f else -h.toFloat()
        vb.flWebViewContainer.translationY = 0f
        setPageTopMargin(if (show) h else 0)
    }

    private fun setToolbarShown(show: Boolean, animate: Boolean = true) {
        if (show == toolbarShown && toolbarAnimator == null) return
        toolbarShown = show
        val bar = vb.rlActionBar
        val page = vb.flWebViewContainer
        val h = bar.height.toFloat()
        if (!animate || h <= 0f || isFullscreen) {
            applyToolbarLayout(show)
            return
        }
        toolbarAnimator?.cancel()
        //scroll events caused by the move itself must not hide/show the bar again
        toolbarMovingUntil = System.currentTimeMillis() + 500
        val barFrom = bar.translationY
        val pageFrom: Float
        if (show) {
            bar.visibility = View.VISIBLE
            pageFrom = page.translationY
        } else {
            //page grows at the bottom (off screen) and is shifted down by the bar height, so nothing moves yet
            val current = (page.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin + page.translationY
            setPageTopMargin(0)
            page.translationY = current
            pageFrom = current
        }
        val barTo = if (show) 0f else -h
        val pageTo = if (show) h else 0f
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 220
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener {
            val f = it.animatedValue as Float
            bar.translationY = barFrom + (barTo - barFrom) * f
            page.translationY = pageFrom + (pageTo - pageFrom) * f
            vb.vToolbarCursor.onBarMoved()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (cancelled) return
                toolbarAnimator = null
                applyToolbarLayout(show)
            }
        })
        toolbarAnimator = anim
        anim.start()
    }

    /** Wisp: how far the page was scrolled back up since the bar was hidden (it returns after a bit). */
    private var scrollUpSinceHidden = 0

    private var topPushLast = 0L
    private var topPushSince = 0L
    private var topPushPos = Int.MIN_VALUE

    /**
     * The pointer pushes against the top edge: it goes onto the toolbar when the page is at its top,
     * or when pushing no longer scrolls anything (sites that scroll an inner box, or report a scroll
     * position that never gets back to 0) - before, the pointer stuck there and the page stretched.
     */
    private fun pageStoppedAtTop(engine: com.phlox.tvwebbrowser.webengine.WebEngine): Boolean {
        if (isPageAtTop(engine)) return true
        val view = engine.getView() ?: return true
        val pos = view.scrollY + ((view as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx)?.boxScrollY ?: 0)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - topPushLast > 250 || pos != topPushPos) {
            //a new push, or the page still scrolls up
            topPushSince = now
            topPushPos = pos
        }
        topPushLast = now
        return now - topPushSince > 350
    }

    private fun isPageAtTop(engine: com.phlox.tvwebbrowser.webengine.WebEngine): Boolean {
        val view = engine.getView() ?: return true
        val box = (view as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx)?.boxScrollY ?: 0
        return view.scrollY == 0 && box <= 0
    }

    private fun onTabScrolled(tab: WebTabState, y: Int, dy: Int) {
        if (tab != tabsModel.currentTab.value || isFullscreen) return
        if (System.currentTimeMillis() < toolbarMovingUntil) return
        if (y <= 0 && dy <= 0) {
            //scrolled (or pulled) all the way up
            if (toolbarAutoHidden || !toolbarShown) {
                showToolbar()
                toolbarShownByUserAt = System.currentTimeMillis()
            }
            return
        }
        if (dy > 0) {
            scrollDownSinceShown += dy
            scrollUpSinceHidden = 0
            if (scrollDownSinceShown > Utils.D2P(this, 48f)) autoHideToolbar()
        } else if (dy < 0) {
            scrollDownSinceShown = 0
            //Wisp: like other phone browsers, scrolling back up brings the bar back
            if (phoneBar && !toolbarShown) {
                scrollUpSinceHidden -= dy
                if (scrollUpSinceHidden > Utils.D2P(this, 24f)) {
                    scrollUpSinceHidden = 0
                    showToolbar()
                }
            }
        }
    }

    /**
     * The web page process died (usually out of memory). Android would close the app; instead the
     * tab gets a fresh page that explains what happened, without reloading the page that crashed.
     */
    /** Wisp: when the start page's process last died (it is reloaded silently once). */
    private var startPageRecoveredAt = 0L

    private fun recoverFromPageCrash(tab: WebTabState, url: String?) {
        //don't restore the saved state: it would load the crashing page again
        tab.savedState = null
        tab.removeFiles()
        val failedUrl = url?.takeIf { it.startsWith("http") } ?: tab.url
        //Wisp: the start page has nothing to lose - Android ended its process to free memory:
        //open it again without an error page (once a minute at most, so a real fault still shows)
        if (com.phlox.tvwebbrowser.Brand.phone && !failedUrl.startsWith("http") &&
            System.currentTimeMillis() - startPageRecoveredAt > 60_000 && tab == tabsModel.currentTab.value) {
            startPageRecoveredAt = System.currentTimeMillis()
            try {
                tab.url = settingsModel.homePage
                changeTab(tab)
                navigate(settingsModel.homePage)
                return
            } catch (e: Exception) {
                Log.e(TAG, "recover start page", e)
            }
        }
        if (tab != tabsModel.currentTab.value) {
            tab.url = failedUrl //rebuilt (and reloaded) when the tab is shown again
            return
        }
        showToolbar()
        try {
            //a blank start, so the new view doesn't load (and crash on) the same page right away
            tab.url = "about:blank"
            changeTab(tab)
            tab.url = failedUrl
            (tab.webEngine.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx)?.showCrashPage(failedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "recoverFromPageCrash", e)
        }
    }

    // ---------------- Lumen: number of grabbed media links on the grab button ----------------
    private var grabCountUpdatePending = false

    private fun scheduleGrabCountUpdate() {
        //media requests arrive in bursts from network threads: update at most ~4 times a second
        if (grabCountUpdatePending) return
        grabCountUpdatePending = true
        toolbarHandler.postDelayed({
            grabCountUpdatePending = false
            updateGrabCount()
        }, 250)
    }

    private fun updateGrabCount() {
        val webView = tabsModel.currentTab.value?.webEngine?.getView() as? com.phlox.tvwebbrowser.webengine.webview.WebViewEx
        val count = webView?.mediaLog?.size ?: 0
        if (count <= 0) {
            vb.tvGrabCount.visibility = View.GONE
        } else {
            vb.tvGrabCount.text = if (count > 99) "99+" else count.toString()
            vb.tvGrabCount.visibility = View.VISIBLE
        }
        vb.ibGrabLinks.contentDescription = if (count > 0)
            getString(R.string.lumen_grab) + " ($count)" else getString(R.string.lumen_grab)
    }

    private fun onTabPageStartedForToolbar(tab: WebTabState) {
        if (tab != tabsModel.currentTab.value) return
        updateGrabCount()
        hideScheduledForThisLoad = false
        showToolbar()
    }

    private fun onTabPageFinishedForToolbar(tab: WebTabState) {
        if (tab != tabsModel.currentTab.value || hideScheduledForThisLoad) return
        hideScheduledForThisLoad = true
        if (!canAutoHideToolbar() || phoneBar) return
        toolbarHandler.removeCallbacks(hideToolbarAfterLoad)
        toolbarHandler.postDelayed(hideToolbarAfterLoad, 2000)
    }

    /** The top bar is always visible; "showing the menu" moves the remote's focus into it. */
    private fun showMenuOverlay() {
        showToolbar()
        toolbarShownByUserAt = System.currentTimeMillis()
        vb.flWebViewContainer.visibility = View.VISIBLE
        vb.vActionBar.dismissExtendedAddressBarMode()
        vb.vActionBar.catchFocus()
    }

    private suspend fun displayThumbnail(currentTab: WebTabState?) {
        //Lumen: no thumbnail previews; the page itself stays visible under the top bar
        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
        if (vb.ivMiniatures.visibility == View.INVISIBLE) return
        if (currentTab != null) {
            if (tabByTitleIndex(vb.vTabs.current) != currentTab) return
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
            if (currentTab.thumbnail != null) {
                vb.ivMiniatures.setImageBitmap(currentTab.thumbnail)
            } else if (currentTab.thumbnailHash != null) {
                withContext(Dispatchers.IO) {
                    val thumbnail = currentTab.loadThumbnail()
                    withContext(Dispatchers.Main) {
                        if (thumbnail != null) {
                            vb.ivMiniatures.setImageBitmap(currentTab.thumbnail)
                        } else {
                            vb.ivMiniatures.setImageResource(0)
                        }
                    }
                }
            } else {
                vb.ivMiniatures.setImageResource(0)
            }
        } else {
            vb.llMiniaturePlaceholder.visibility = View.VISIBLE
            vb.ivMiniatures.setImageResource(0)
            vb.ivMiniatures.visibility = View.INVISIBLE
        }
    }

    /** Leaves the top bar: shows the selected tab and gives the focus back to the page. */
    private fun hideMenuOverlay(hideBottomButtons: Boolean = true) {
        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
        syncTabWithTitles()
        vb.flWebViewContainer.visibility = View.VISIBLE
        if (hideBottomButtons) {
            tabsModel.currentTab.value?.webEngine?.getView()?.requestFocus()
        }
    }

    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vb.vTabs.current)
        if (tab == null) {
            if (tabsModel.tabsStates.isEmpty()) {
                openInNewTab(settingsModel.homePage, 0, needToHideMenuOverlay = false, navigateImmediately = true)
            } else {
                //stale index (e.g. right after closing a tab): point back at the visible tab
                tabsModel.currentTab.value?.let { vb.vTabs.current = tabsModel.tabsStates.indexOf(it) }
            }
        } else if (!tab.selected) {
            changeTab(tab)
        }
    }

    private fun hideBottomPanel() {
        //the buttons are part of the permanent top bar now
    }

    private fun onDownloadStarted(fileName: String) {
        if (com.phlox.tvwebbrowser.Brand.phone) {
            vb.vActionBar.dropTypedAddress()
            tabsModel.currentTab.value?.let { t ->
                //the page shown is still the one before (navigate() already wrote the typed address in)
                val shown = t.webEngine.url
                if (shown != null && shown != t.url && (shown.startsWith("http") || shown.startsWith(Config.HOME_PAGE_URL))) t.url = shown
                vb.vActionBar.setAddressBoxText(t.url)
            }
        }
        Utils.showToast(this, getString(R.string.download_started,
                com.phlox.tvwebbrowser.sofa.LumenDownloads.folderLabel(this) + File.separator + fileName))
    }

    override fun initiateVoiceSearch() {
        //Lumen has no microphone access: the search key opens the address bar for typing instead
        vb.vActionBar.focusAddressBar()
    }

    private fun onEditHomePageBookmark(favoriteItem: FavoriteItem) {
        FavoriteEditorDialog(this, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                viewModel.onHomePageLinkEdited(item)
            }
        }, favoriteItem).show()
    }

    private inner class WebEngineCallback(val tab: WebTabState) : WebEngineWindowProviderCallback {
        override fun getActivity(): Activity {
            return this@MainActivity
        }

        override fun onOpenInNewTabRequested(url: String, navigateImmediately: Boolean): WebEngine? {
            var index = tabsModel.tabsStates.indexOf(tabsModel.currentTab.value)
            index = if (index == -1) tabsModel.tabsStates.size else index + 1
            return openInNewTab(url, index, true, navigateImmediately)
        }

        override fun onDownloadRequested(url: String) {
            Log.i(TAG, "onDownloadRequested url: $url")
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
            //an address without a file name (e.g. ".../image?id=5") used to stop the download here
            val fileName = DownloadUtils.guessFileName(url, null, mimeType)
            onDownloadRequested(url, tab.url,
                fileName, pageUserAgent(tab), mimeType)
        }

        override fun onDownloadRequested(url: String, referer: String,
                                         originalDownloadFileName: String?, userAgent: String?, mimeType: String?,
                                         operationAfterDownload: Download.OperationAfterDownload, base64BlobData: String?,
                                         stream: InputStream?, size: Long, contentDisposition: String?) {
            val fileName = DownloadUtils.guessFileName(url, contentDisposition, mimeType)

            this@MainActivity.onDownloadRequested(url, referer, fileName,
                userAgent, mimeType, operationAfterDownload, base64BlobData, stream, size)
        }

        override fun onDownloadRequested(url: String, userAgent: String?, contentDisposition: String,
                                         mimetype: String?, contentLength: Long ) {
            Log.i(TAG, "DownloadListener.onDownloadStart url: $url")
            onDownloadRequested(url= url, referer = tab.url, originalDownloadFileName = null,
                userAgent = userAgent, mimeType = mimetype, size = contentLength, contentDisposition = contentDisposition)
        }

        override fun onProgressChanged(newProgress: Int) {
            if (com.phlox.tvwebbrowser.Brand.phone && tab == tabsModel.currentTab.value) {
                vb.vActionBar.setLoading(newProgress < 100 && !isOnHomePage(tab))
            }
            //Wisp: the start page is built in, so no loading line for it
            if (com.phlox.tvwebbrowser.Brand.phone && isOnHomePage(tab)) {
                uiHandler.removeCallbacks(progressBarHideRunnable)
                vb.progressBar.clearAnimation()
                vb.progressBar.visibility = View.GONE
                return
            }
            vb.progressBar.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                vb.progressBar.setProgress(newProgress, true)
            } else {
                vb.progressBar.progress = newProgress
            }
            uiHandler.removeCallbacks(progressBarHideRunnable)
            if (newProgress == 100) {
                uiHandler.postDelayed(progressBarHideRunnable, 1000)
            } else {
                uiHandler.postDelayed(progressBarHideRunnable, 5000)
            }
        }

        override fun onReceivedTitle(title: String) {
            tab.title = title
            vb.vTabs.onTabTitleUpdated(tab)
            viewModel.onTabTitleUpdated(tab)
        }

        override fun requestPermissions(array: Array<String>): Int {
            val requestCode = lastCommonRequestsCode++
            this@MainActivity.requestPermissions(array, requestCode)
            return requestCode
        }

        override fun onShowFileChooser(intent: Intent): Boolean {
            try {
                startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
            } catch (e: ActivityNotFoundException) {
                try {
                    //trying again with type */* (seems file pickers usually doesn't support specific types in intent filters but still can do the job)
                    intent.type = "*/*"
                    startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    Utils.showToast(applicationContext, getString(R.string.err_cant_open_file_chooser))
                    return false
                }
            }
            return true
        }

        override fun onReceivedIcon(icon: Bitmap) {
            vb.vTabs.onFavIconUpdated(tab)
            if (tab === tabsModel.currentTab.value && !isLumenHome(tab.url)) {
                siteIconUrl = null
                vb.vActionBar.setSiteIcon(icon)
                siteIconUrl = tab.url
            }
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            tab.lastLoadingUrl = url

            val uri = try {
                Uri.parse(url)
            } catch (e: Exception) {
                Log.e(TAG, "shouldOverrideUrlLoading: ", e)
                return true
            }

            if (uri.scheme == null) {
                Log.d(TAG, "shouldOverrideUrlLoading: no scheme: $url")
                return true
            }

            if (URLUtil.isNetworkUrl(url) || uri.scheme.equals("javascript", true) ||
                    uri.scheme.equals("data", true) || uri.scheme.equals("about", true) ||
                    uri.scheme.equals("blob", true)) {
                Log.d(TAG, "shouldOverrideUrlLoading: network url: $url")
                return false
            }

            if (uri.scheme.equals("intent", true)) {
                Log.d(TAG, "shouldOverrideUrlLoading: intent url: $url")
                onOpenInExternalAppRequested(url)
                return true
            }

            //try to handle intent for non-network urls by external apps
            //TODO: ask user if he wants to open this url in external app
            return try {
                Log.d(TAG, "shouldOverrideUrlLoading: non-network url: $url")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(TVBro.instance.packageManager) != null) {
                    runOnUiThread {
                        askUserAndOpenInExternalApp(url, intent)
                    }
                    true
                } else {
                    Log.d(TAG, "shouldOverrideUrlLoading: no activity to handle intent")
                    runOnUiThread {
                        Utils.showToast(applicationContext, getString(R.string.err_no_app_to_handle_url))
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "shouldOverrideUrlLoading: ", e)
                true
            }
        }

        override fun onPageScrolled(y: Int, dy: Int) {
            onTabScrolled(tab, y, dy)
        }

        override fun onPullToRefresh(distance: Float, released: Boolean) {
            this@MainActivity.onPullToRefresh(tab, distance, released)
        }

        override fun onRenderProcessGone(url: String?) {
            recoverFromPageCrash(tab, url)
        }

        override fun onPageStateChanged() {
            onWebViewUpdated(tab)
        }

        override fun onVideoStarted() {
            //a video started in the page: give it the whole screen
            if (!phoneBar && tab == tabsModel.currentTab.value && toolbarShown && canAutoHideToolbar() &&
                System.currentTimeMillis() - toolbarShownByUserAt > 3000) {
                autoHideToolbar()
            }
        }

        override fun onUrlChanged(url: String) {
            if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
            if (tab.url == url) return
            tab.url = url
            if (tabByTitleIndex(vb.vTabs.current) == tab && !vb.vActionBar.hasFocus()) {
                vb.vActionBar.setAddressBoxText(url)
            }
            updatePhoneTabCount()
        }

        override fun onPageStarted(url: String?) {
            onTabPageStartedForToolbar(tab)
            onWebViewUpdated(tab)
            if (url != null && url.startsWith(Config.HOME_PAGE_URL)) {
                //pick up bookmarks added since the home page was last shown
                viewModel.refreshHomePageLinks()
            }
            //the close button depends on the page (see TabsAdapter)
            vb.vTabs.onTabTitleUpdated(tab)
            val webViewUrl = tab.webEngine.url
            if (webViewUrl != null) {
                tab.url = webViewUrl
            } else if (url != null) {
                tab.url = url
            }
            if (tabByTitleIndex(vb.vTabs.current) == tab) {
                vb.vActionBar.setAddressBoxText(tab.url)
            }
            tab.blockedAds = 0
            tab.blockedPopups = 0
            updatePhoneTabCount()
        }

        override fun onPageFinished(url: String?) {
            if (tabsModel.currentTab.value == null) {
                return
            }
            onWebViewUpdated(tab)
            onTabPageFinishedForToolbar(tab)

            val webViewUrl = tab.webEngine.url
            if (webViewUrl != null) {
                tab.url = webViewUrl
            } else if (url != null) {
                tab.url = url
            }
            if (tabByTitleIndex(vb.vTabs.current) == tab) {
                vb.vActionBar.setAddressBoxText(tab.url)
            }

            //Lumen: no tab thumbnails (they aren't shown): saves memory and work on every page load
            //(Wisp shows them in its tab grid)
            if (!com.phlox.tvwebbrowser.Brand.phone) tabsModel.tabsStates.onEach { it.thumbnail = null }
            updatePhoneTabCount()
            if (com.phlox.tvwebbrowser.Brand.phone) checkPageConsistency(tab)
        }

        override fun onPageCertificateError(url: String?) {
            vb.vActionBar.setAddressBoxTextColor(Color.RED)
        }

        override fun isAd(url: Uri, acceptHeader: String?, baseUri: Uri): Boolean? {
            return adblockModel.isAd(url, acceptHeader, baseUri)
        }

        override fun isAdBlockingEnabled(): Boolean {
            return isAdBlockOnForTab(tab)
        }

        override fun isDialogsBlockingEnabled(): Boolean {
            if (tab.url == Config.HOME_PAGE_URL) return false
            return shouldBlockNewWindow(dialog = true, userGesture = false)
        }

        override fun shouldBlockNewWindow(dialog: Boolean, userGesture: Boolean): Boolean {
            val hostConfig = runBlocking(Dispatchers.Main.immediate){ tabsModel.findHostConfig(tab, false) }
            val currentBlockPopupsLevelValue = hostConfig?.popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE
            return when (currentBlockPopupsLevelValue) {
                HostConfig.POPUP_BLOCK_NONE -> false
                HostConfig.POPUP_BLOCK_DIALOGS -> dialog
                HostConfig.POPUP_BLOCK_NEW_AUTO_OPENED_TABS -> dialog || !userGesture
                else -> true
            }
        }

        override fun onBlockedAd(uri: String) {
            Log.i(TAG, "onBlockedAd: $uri")
            if (!config.adBlockEnabled) return
            tab.blockedAds++
            vb.tvBlockedAdCounter.visibility = if (tab.blockedAds > 0) View.VISIBLE else View.GONE
            vb.tvBlockedAdCounter.text = tab.blockedAds.toString()
        }

        override fun onBlockedDialog(newTab: Boolean) {
            tab.blockedPopups++
            runOnUiThread {
                vb.tvBlockedPopupCounter.visibility = if (tab.blockedPopups > 0) View.VISIBLE else View.GONE
                vb.tvBlockedPopupCounter.text = tab.blockedPopups.toString()
                val msg = getString(if (newTab) R.string.new_tab_blocked else R.string.popup_dialog_blocked)
                NotificationView.showBottomRight(vb.rlRoot, R.drawable.ic_block_popups, msg)
            }
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): View? {
            if (shouldBlockNewWindow(dialog, userGesture)) {
                onBlockedDialog(!dialog)
                return null
            }
            val tab = WebTabState(incognito = config.incognitoMode)
            val webView = createWebView(tab) ?: return null
            val currentTab = this@MainActivity.tabsModel.currentTab.value ?: return null
            val index = tabsModel.tabsStates.indexOf(currentTab) + 1
            tabsModel.tabsStates.add(index, tab)
            changeTab(tab)
            return webView
        }

        override fun closeWindow(internalRepresentation: Any) {
            for (tab in tabsModel.tabsStates) {
                if (tab.webEngine.isSameSession(internalRepresentation)) {
                    closeTab(tab)
                    break
                }
            }
        }

        override fun onScaleChanged(oldScale: Float, newScale: Float) {
            Log.d(TAG, "onScaleChanged: oldScale: $oldScale newScale: $newScale")
            tab.scale = newScale
        }

        override fun onCopyTextToClipboardRequested(url: String) {
            val clipBoard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("URL", url)
            clipBoard.setPrimaryClip(clipData)
            Toast.makeText(this@MainActivity, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }

        override fun onShareUrlRequested(url: String) {
            val share = Intent(Intent.ACTION_SEND)
            share.type = "text/plain"
            share.putExtra(Intent.EXTRA_SUBJECT, R.string.share_url)
            share.putExtra(Intent.EXTRA_TEXT, url)
            try {
                startActivity(share)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onOpenInExternalAppRequested(url: String) {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            val activityComponent = intent.resolveActivity(this@MainActivity.packageManager)
            if (activityComponent != null && activityComponent.packageName == this@MainActivity.packageName) {
                Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
                return
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
            }
        }

        override fun initiateVoiceSearch() {
            this@MainActivity.initiateVoiceSearch()
        }

        override fun onEditHomePageBookmarkSelected(index: Int) {
            lifecycleScope.launch {
                val bookmark = viewModel.homePageLinks.firstOrNull { it.order == index }
                var favoriteItem: FavoriteItem? = bookmark?.favoriteId?.let {
                    AppDatabase.db.favoritesDao().getById(it)
                }

                if (favoriteItem == null) {
                    favoriteItem = FavoriteItem()
                    favoriteItem.title = bookmark?.title
                    favoriteItem.url = bookmark?.url
                    favoriteItem.order = index
                    favoriteItem.homePageBookmark = true
                    onEditHomePageBookmark(favoriteItem)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.bookmarks)
                        .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                            when (which) {
                                0 -> onEditHomePageBookmark(favoriteItem)
                                1 -> viewModel.removeHomePageLink(bookmark!!)
                            }
                        }
                        .show()
                }
            }
        }

        override fun getHomePageLinks(): List<HomePageLink> {
            return viewModel.homePageLinks
        }

        override fun onPrepareForFullscreen() {
            touchMenuButton?.visibility = View.GONE
            toolbarShown = false
            applyToolbarLayout(false)
            vb.rlActionBar.visibility = View.GONE
            //window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            //        WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setLumenImmersive(true)
            isFullscreen = true
            if (com.phlox.tvwebbrowser.Brand.phone) {
                vb.rlRoot.setBackgroundColor(Color.BLACK)
                vb.rlRoot.requestApplyInsets()
            }
        }

        override fun onExitFullscreen() {
            touchMenuButton?.visibility = View.VISIBLE
            toolbarShown = true
            applyToolbarLayout(true)
            toolbarAutoHidden = false
            scrollDownSinceShown = 0
            //window.setFlags(0, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            if (!config.keepScreenOn) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setLumenImmersive(false)
            isFullscreen = false
            if (com.phlox.tvwebbrowser.Brand.phone) {
                vb.rlRoot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.wisp_bar))
                vb.rlRoot.requestApplyInsets()
            }
        }

        override fun onVisited(url: String) {
            val tab = tabsModel.currentTab.value ?: return

            //Wisp: history for this session only (deleted by Quit)
            if (!config.incognitoMode || com.phlox.tvwebbrowser.Brand.phone) {
                viewModel.logVisitedHistory(tab.title, url, tab.faviconHash)
            }
        }

        override fun onPageLongPress(engine: com.phlox.tvwebbrowser.webengine.WebEngine, result: String, x: Int, y: Int): Boolean =
            this@MainActivity.onPageLongPress(engine, result)

        override fun onContextMenu(
            cursorDrawer: CursorDrawerDelegate,
            baseUri: String?,
            linkUri: String?,
            srcUri: String?,
            title: String?,
            altText: String?,
            textContent: String?,
            x: Int,
            y: Int
        ) {
            uiHandler.post {
                vb.vCursorMenu.show(
                    tab,this, cursorDrawer,
                    baseUri, linkUri, srcUri,
                    title, altText, textContent,
                    x, y,
                    backNavigationEventsAdapter
                )
            }
        }

        override fun suggestActionsForLink(baseUri: String?, linkUri: String?, srcUri: String?,
                                           title: String?, altText: String?, textContent: String?,
                                           x: Int, y: Int) {
            var s = linkUri ?: srcUri
            if (s != null && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
            }
            val url = s
            if (com.phlox.tvwebbrowser.Brand.phone) {
                //the page script usually reports the same long press with more detail: wait for it
                val l = linkUri?.trim('"')
                val i = srcUri?.trim('"')
                val label = title ?: altText
                uiHandler.postDelayed({
                    if (android.os.SystemClock.uptimeMillis() - lastScriptLongPress > 1500) showPhoneLinkSheet(l, i, label, false)
                }, 500)
                return
            }
            val isHTTPUrl = url != null && (url.startsWith("http://") || url.startsWith("https://"))
            val anchor = View(this@MainActivity)
            val lp = FrameLayout.LayoutParams(1, 1)
            lp.setMargins(x, y, 0, 0)
            vb.flWebViewContainer.addView(anchor, lp)
            linkActionsMenu = PopupMenu(this@MainActivity, anchor, Gravity.BOTTOM).also {
                it.inflate(R.menu.menu_link)
                it.menu.findItem(R.id.miOpenInNewTab).isVisible = isHTTPUrl
                it.menu.findItem(R.id.miOpenInExternalApp).isVisible = isHTTPUrl
                it.menu.findItem(R.id.miDownload).isVisible = isHTTPUrl
                it.menu.findItem(R.id.miCopyToClipboard).isVisible = url != null
                it.menu.findItem(R.id.miShare).isVisible = url != null
                it.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.miRefreshPage -> tab.webEngine.reload()
                        R.id.miOpenInNewTab -> onOpenInNewTabRequested(url!!, true)
                        R.id.miOpenInExternalApp -> onOpenInExternalAppRequested(url!!)
                        R.id.miDownload -> onDownloadRequested(url!!)
                        R.id.miCopyToClipboard -> onCopyTextToClipboardRequested(url!!)
                        R.id.miShare -> onShareUrlRequested(url!!)
                    }
                    true
                }

                it.setOnDismissListener {
                    vb.flWebViewContainer.removeView(anchor)
                    linkActionsMenu = null
                }
                it.show()
            }
        }

        /** Wisp: long press on a link and/or an image - a plain sheet instead of the TV's popup menu. */
        override fun onWispLongPress(json: String) {
            if (tab != tabsModel.currentTab.value) return
            val o = try { org.json.JSONObject(json) } catch (e: Exception) { return }
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
            lastScriptLongPress = android.os.SystemClock.uptimeMillis()
            showPhoneLinkSheet(str("link"), str("image"), str("label"), o.optBoolean("hide"))
        }

        private fun showPhoneLinkSheet(link: String?, image: String?, label: String?, canHide: Boolean) {
            fun web(u: String?) = u != null && (u.startsWith("http://") || u.startsWith("https://"))
            val linkOk = web(link) && link != image
            val imageOk = web(image) || (image?.startsWith("data:image/") == true)
            if (!linkOk && !imageOk && !canHide) return
            val hideRows = if (!canHide) emptyList() else listOf(
                WispSheets.MenuItem(R.drawable.ic_wisp_private, getString(R.string.wisp_hide_element),
                    subtitle = getString(R.string.wisp_hide_element_sum)) {
                    val wv = tab.webEngine.getView() as? android.webkit.WebView ?: return@MenuItem
                    val host = try { Uri.parse(tab.url).host } catch (e: Exception) { null }
                    wv.evaluateJavascript("window.__wispHidePicked ? window.__wispHidePicked() : ''") { r ->
                        val sel = try { org.json.JSONArray("[$r]").getString(0) } catch (e: Exception) { "" }
                        if (sel.isNotBlank()) {
                            config.addWispHidden(host, sel)
                            Toast.makeText(this@MainActivity, R.string.wisp_hidden_done, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            val linkRows = if (!linkOk) emptyList() else listOf(
                WispSheets.MenuItem(R.drawable.ic_wisp_open_new, getString(R.string.wisp_open_new_tab)) {
                    onOpenInNewTabRequested(link!!, true)
                },
                WispSheets.MenuItem(R.drawable.ic_wisp_copy, getString(R.string.wisp_copy_link)) {
                    onCopyTextToClipboardRequested(link!!)
                },
                WispSheets.MenuItem(R.drawable.ic_wisp_share, getString(R.string.wisp_share_link)) {
                    onShareUrlRequested(link!!)
                },
                WispSheets.MenuItem(R.drawable.ic_wisp_download, getString(R.string.wisp_download_link)) {
                    downloadFromPage(link!!)
                }
            )
            val imageRows = if (!imageOk) emptyList() else listOfNotNull(
                WispSheets.MenuItem(R.drawable.ic_wisp_download, getString(R.string.wisp_download_image)) {
                    downloadFromPage(image!!)
                },
                if (web(image)) WispSheets.MenuItem(R.drawable.ic_wisp_image, getString(R.string.wisp_open_image)) {
                    onOpenInNewTabRequested(image!!, true)
                } else null,
                if (web(image)) WispSheets.MenuItem(R.drawable.ic_wisp_copy, getString(R.string.wisp_copy_image_link)) {
                    onCopyTextToClipboardRequested(image!!)
                } else null,
                if (web(image)) WispSheets.MenuItem(R.drawable.ic_wisp_share, getString(R.string.wisp_share_image_link)) {
                    onShareUrlRequested(image!!)
                } else null
            )
            val shown = if (linkOk) link else if (web(image)) image else null
            //one menu at a time (the WebView and the page script can both report the same press)
            linkSheet?.dismiss()
            linkSheet = WispSheets.showList(this@MainActivity, label?.takeIf { it.isNotBlank() }?.take(120),
                shown?.let { Uri.decode(it) }?.take(300), listOf(linkRows, imageRows, hideRows))
            //a long press on a text link also selects its words: the menu replaces that
            if (linkOk || imageOk) (tab.webEngine.getView() as? android.webkit.WebView)
                ?.evaluateJavascript("try{window.getSelection().removeAllRanges()}catch(e){}", null)
        }

        /** Saves an address found on the page (a link or an image), with this tab's address as referer. */
        private fun downloadFromPage(url: String) {
            if (url.startsWith("data:")) {
                //an image inside the page itself (data:image/png;base64,...)
                val mime = url.substringAfter("data:").substringBefore(";").substringBefore(",")
                if (!url.substringBefore(",").endsWith(";base64")) {
                    Toast.makeText(this@MainActivity, R.string.wisp_download_failed, Toast.LENGTH_SHORT).show()
                    return
                }
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
                val name = "image_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date()) + "." + ext
                this@MainActivity.onDownloadRequested(url, tab.url, name, pageUserAgent(tab), mime,
                    base64BlobData = url, stream = null)
                return
            }
            onDownloadRequested(url)
        }

        override fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
            viewModel.markBookmarkRecommendationAsUseful(bookmarkOrder)
        }
    }

    private fun askUserAndOpenInExternalApp(url: String, intent: Intent) {
        if (openUrlInExternalAppDialog != null) {
            return
        }
        openUrlInExternalAppDialog = AlertDialog.Builder(this)
            .setTitle(R.string.site_asks_to_open_unknown_url)
            .setMessage(getString(R.string.site_asks_to_open_unknown_url_message) + "\n\n" + url)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.no, null)
            .setOnDismissListener {
                openUrlInExternalAppDialog = null
            }
            .show()
    }

    private val downloadServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as? DownloadService.Binder
            if (binder == null) {
                Log.e(TAG, "Download service connection failed")
                //probably service still in another process due to incognito mode process switch
                //so we will try to reconnect in a few seconds
                uiHandler.postDelayed({
                    bindService(Intent(this@MainActivity, DownloadService::class.java),
                        this, Context.BIND_AUTO_CREATE)
                }, 1000)
                return
            }
            downloadService = binder.service
            startPendingDownloads()
            //downloads interrupted by an error or by closing the app continue (once per app start)
            binder.service.resumeInterruptedOnce()
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            downloadService = null
        }
    }
}
