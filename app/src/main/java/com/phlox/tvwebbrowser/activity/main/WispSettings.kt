package com.phlox.tvwebbrowser.activity.main

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.sofa.LumenDownloads
import com.phlox.tvwebbrowser.sofa.LumenUpdater
import com.phlox.tvwebbrowser.sofa.SofaPasswords
import com.phlox.tvwebbrowser.sofa.SofaVpn
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wisp (phone build): settings as a phone screen - a list of sections, each opening its own page
 * with switches and choices (Lumen keeps its TV settings dialog). Only phone-relevant settings:
 * nothing for the remote, the cursor or TV video chips.
 */
class WispSettings(
    private val activity: MainActivity,
    private val settingsModel: SettingsModel,
    private val adblockModel: AdblockModel
) {
    private val config: Config = AppContext.provideConfig()
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun str(id: Int, vararg args: Any): String = activity.getString(id, *args)

    private val dialog = object : Dialog(activity, R.style.WispSettingsScreen) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            if (pages.size > 1) {
                pages.removeAt(pages.size - 1)
                render()
            } else {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
    private val title = TextView(activity)
    private val content = LinearLayout(activity)
    private val scroll = ScrollView(activity)

    /** Open pages, the current one last. */
    private val pages = ArrayList<Page>()

    private enum class Page(val title: Int) {
        MAIN(R.string.wisp_settings),
        GENERAL(R.string.wisp_set_general),
        PRIVACY(R.string.wisp_set_privacy),
        ADBLOCK(R.string.wisp_set_adblock),
        CONTENT(R.string.wisp_set_content),
        DOWNLOADS(R.string.wisp_set_downloads),
        VPN(R.string.wisp_set_vpn),
        SITES(R.string.wisp_set_sites),
        SITE(R.string.wisp_set_sites),
        SECURITY(R.string.wisp_set_security),
        ABOUT(R.string.wisp_set_about),
        DIAG(R.string.wisp_diag)
    }

    private val vpnObserver: (SofaVpn.Status) -> Unit = { if (pages.lastOrNull() == Page.VPN || pages.lastOrNull() == Page.MAIN) render() }
    private val adblockObserver: (Boolean) -> Unit = { if (pages.lastOrNull() == Page.ADBLOCK) render() }

    init {
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(color(R.color.wisp_bg))

        //app bar: ← Title
        val bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(px(4f), px(4f), px(16f), px(4f))
        bar.setBackgroundColor(color(R.color.wisp_bar))
        val back = ImageView(activity)
        back.setImageResource(R.drawable.ic_wisp_back)
        back.setColorFilter(color(R.color.wisp_icon))
        back.scaleType = ImageView.ScaleType.CENTER_INSIDE
        back.background = ripple()
        back.contentDescription = str(R.string.wisp_back)
        @Suppress("DEPRECATION")
        back.setOnClickListener { dialog.onBackPressed() }
        bar.addView(back, LinearLayout.LayoutParams(px(48f), px(48f)))
        title.textSize = 20f
        title.setTextColor(color(R.color.wisp_text))
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setPadding(px(8f), 0, 0, 0)
        bar.addView(title)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60f)))

        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, px(4f), 0, px(32f))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        //Android 15+ draws full-screen windows under the status and navigation bars (the title sat
        //under the tablet's clock): keep the content clear of them; the status bar area takes the
        //app bar's colour
        root.setBackgroundColor(color(R.color.wisp_bar))
        scroll.setBackgroundColor(color(R.color.wisp_bg))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            activity.refreshEngineButton()
            scroll.removeCallbacks(vpnPoll)
            SofaVpn.status.unsubscribe(vpnObserver)
            adblockModel.clientLoading.unsubscribe(adblockObserver)
        }
        dialog.window?.let {
            it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            it.setWindowAnimations(android.R.style.Animation_Activity)
        }
    }

    /** The tunnel runs in the main process: its state is read again every 1.5 s while settings are open. */
    private val vpnPoll = object : Runnable {
        override fun run() {
            SofaVpn.refreshStatus()
            if (dialog.isShowing) scroll.postDelayed(this, 1500)
        }
    }

    fun show() {
        pages.clear()
        pages.add(Page.MAIN)
        SofaVpn.refreshStatus()
        scroll.postDelayed(vpnPoll, 1500)
        SofaVpn.status.subscribe(vpnObserver, false)
        adblockModel.clientLoading.subscribe(adblockObserver, false)
        render()
        dialog.show()
    }

    private fun open(page: Page) {
        pages.add(page)
        render()
        scroll.scrollTo(0, 0)
    }

    private fun render() {
        val page = pages.last()
        title.text = if (page == Page.SITE) siteShown else str(page.title)
        val y = scroll.scrollY
        content.removeAllViews()
        when (page) {
            Page.MAIN -> mainPage()
            Page.GENERAL -> generalPage()
            Page.PRIVACY -> privacyPage()
            Page.ADBLOCK -> adblockPage()
            Page.CONTENT -> contentPage()
            Page.DOWNLOADS -> downloadsPage()
            Page.VPN -> vpnPage()
            Page.SITES -> sitesPage()
            Page.SITE -> sitePage(siteShown)
            Page.SECURITY -> securityPage()
            Page.ABOUT -> aboutPage()
            Page.DIAG -> diagPage()
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---------------- pages ----------------

    private fun mainPage() {
        nav(R.drawable.ic_settings_grey_900_24dp, R.string.wisp_set_general, str(R.string.wisp_set_general_sum), Page.GENERAL)
        nav(R.drawable.ic_wisp_shield, R.string.wisp_set_privacy, str(R.string.wisp_set_privacy_sum), Page.PRIVACY)
        nav(R.drawable.ic_wisp_block, R.string.wisp_set_adblock,
            str(if (config.adBlockEnabled) R.string.wisp_on else R.string.wisp_off), Page.ADBLOCK)
        nav(R.drawable.ic_wisp_globe, R.string.wisp_set_content, str(R.string.wisp_set_content_sum), Page.CONTENT)
        nav(R.drawable.ic_wisp_download, R.string.wisp_set_downloads, LumenDownloads.folderLabel(activity), Page.DOWNLOADS)
        nav(R.drawable.ic_wisp_vpn, R.string.wisp_set_vpn, vpnStatusText(), Page.VPN)
        nav(R.drawable.ic_wisp_globe, R.string.wisp_set_sites, str(R.string.wisp_set_sites_sum), Page.SITES)
        nav(R.drawable.ic_wisp_lock, R.string.wisp_set_security, str(R.string.wisp_set_security_sum), Page.SECURITY)
        nav(R.drawable.ic_wisp_info, R.string.wisp_set_about, BuildConfig.VERSION_NAME, Page.ABOUT)
    }

    private fun generalPage() {
        header(R.string.wisp_set_search)
        val engineIndex = Config.SearchEnginesURLs.indexOf(config.searchEngineURL.value)
            .let { if (it < 0 || config.searchEngineURL.value.isEmpty()) Config.SearchEnginesTitles.size - 1 else it }
        choice(R.string.wisp_set_search_engine, Config.SearchEnginesTitles.toList(), engineIndex) { i ->
            if (i == Config.SearchEnginesTitles.size - 1) {
                textInput(R.string.wisp_set_search_engine, config.searchEngineURL.value, str(R.string.wisp_set_search_custom_hint)) { url ->
                    if (url.contains("[query]")) settingsModel.setSearchEngineURL(url)
                    else Toast.makeText(activity, R.string.wisp_set_search_custom_hint, Toast.LENGTH_LONG).show()
                    render()
                }
            } else {
                settingsModel.setSearchEngineURL(Config.SearchEnginesURLs[i])
            }
        }

        header(R.string.wisp_set_start)
        val modes = activity.resources.getStringArray(R.array.home_page_modes).toList()
        choice(R.string.wisp_set_start_page, modes, settingsModel.homePageMode.ordinal) { i ->
            val mode = Config.HomePageMode.entries[i]
            if (mode == Config.HomePageMode.CUSTOM) {
                textInput(R.string.wisp_set_start_page, if (settingsModel.homePage.startsWith("http")) settingsModel.homePage else "https://",
                    "https://…") { url ->
                    settingsModel.setHomePageProperties(mode, url, settingsModel.homePageLinksMode)
                    render()
                }
            } else {
                settingsModel.setHomePageProperties(mode, null, settingsModel.homePageLinksMode)
            }
        }
        if (settingsModel.homePageMode == Config.HomePageMode.HOME_PAGE) {
            val links = activity.resources.getStringArray(R.array.home_page_links_modes).toList()
            choice(R.string.wisp_set_start_shows, links, settingsModel.homePageLinksMode.ordinal) { i ->
                settingsModel.setHomePageProperties(settingsModel.homePageMode, null, Config.HomePageLinksMode.entries[i])
            }
        }

        header(R.string.wisp_set_look)
        val themes = listOf(str(R.string.wisp_theme_system), str(R.string.wisp_theme_light), str(R.string.wisp_theme_dark))
        choice(R.string.wisp_set_theme, themes, config.theme.value.ordinal) { i ->
            config.theme.value = Config.Theme.values()[i]
        }
        val zooms = listOf(75, 85, 100, 115, 130, 150, 175, 200)
        val zoomIndex = zooms.indices.minByOrNull { kotlin.math.abs(zooms[it] - config.wispTextZoom) } ?: 2
        choice(R.string.wisp_set_text_size, zooms.map { "$it %" }, zoomIndex) { i ->
            config.wispTextZoom = zooms[i]
            activity.applyTextZoom()
        }
        switch(R.string.wisp_set_hide_bar, R.string.wisp_set_hide_bar_sum, config.lumenAutoHideToolbar) {
            config.lumenAutoHideToolbar = it
            if (!it) activity.showToolbar()
        }
        switch(R.string.wisp_set_pull_refresh, R.string.wisp_set_pull_refresh_sum, config.wispPullToRefresh) {
            config.wispPullToRefresh = it
        }

        header(R.string.wisp_set_identity)
        //desktop identities are per site (menu → Desktop site), so only phone ones are offered here
        val allTitles = settingsModel.userAgentStringTitles.toList()
        val shown = allTitles.indices.filter { !allTitles[it].contains("Desktop") }
        val uaTitles = shown.map { allTitles[it] }
        val ua = config.userAgentString.value
        val fullIndex = if (ua == null) 0 else settingsModel.uaStrings.indexOf(ua).let { if (it < 0) allTitles.size - 1 else it }
        val uaIndex = shown.indexOf(fullIndex).let { if (it < 0) 0 else it }
        choice(R.string.wisp_set_user_agent, uaTitles, uaIndex) { pick ->
            val i = shown[pick]
            if (i == allTitles.size - 1) {
                textInput(R.string.wisp_set_user_agent, ua ?: "", "Mozilla/5.0 …") { text ->
                    config.userAgentString.value = text.trim().ifEmpty { null }
                    render()
                }
            } else {
                config.userAgentString.value = settingsModel.uaStrings[i].ifEmpty { null }
            }
        }
    }

    private fun privacyPage() {
        header(R.string.wisp_set_private_browsing)
        //Wisp is always private: Quit ends the session (tabs, cookies, site data, cache)
        note(R.string.wisp_always_private_note)
        val kept = config.wispKeepSites.sorted()
        action(R.string.wisp_stay_logged_in_sites, if (kept.isEmpty()) str(R.string.wisp_set_none) else kept.joinToString(", ")) {
            editSiteList(R.string.wisp_stay_logged_in_sites, kept) {
                config.wispKeepSites = it.toSet()
                render()
            }
        }
        action(R.string.wisp_set_clear_now, str(R.string.wisp_set_clear_now_sum)) { clearBrowsingData() }
        action(R.string.wisp_set_clear_cache, str(R.string.wisp_set_clear_cache_sum)) {
            activity.lifecycleScope.launch {
                WebEngineFactory.clearCache(activity)
                Toast.makeText(activity, R.string.wisp_set_done, Toast.LENGTH_SHORT).show()
            }
        }

        header(R.string.wisp_set_tracking)
        switch(R.string.wisp_set_https_first, R.string.wisp_set_https_first_sum, config.wispHttpsFirst) {
            config.wispHttpsFirst = it
        }
        switch(R.string.wisp_set_webrtc, R.string.wisp_set_webrtc_sum, config.wispWebrtcRelay) {
            config.wispWebrtcRelay = it
            Toast.makeText(activity, R.string.wisp_set_reload_hint, Toast.LENGTH_SHORT).show()
        }
        switch(R.string.wisp_set_block_3p, R.string.wisp_set_block_3p_sum, config.wispBlockThirdPartyCookies) {
            config.wispBlockThirdPartyCookies = it
            activity.reapplyCookiePolicy()
        }
        val allowed = config.wispThirdPartyCookieSites.sorted()
        action(R.string.wisp_set_3p_sites, if (allowed.isEmpty()) str(R.string.wisp_set_none) else allowed.joinToString(", ")) {
            editSiteList(R.string.wisp_set_3p_sites, allowed) {
                config.wispThirdPartyCookieSites = it.toSet()
                activity.reapplyCookiePolicy()
            }
        }
        switch(R.string.wisp_set_verify_compat, R.string.wisp_set_verify_compat_sum, config.sofaCloudflareCompat) {
            config.sofaCloudflareCompat = it
            Toast.makeText(activity, R.string.wisp_set_restart_hint, Toast.LENGTH_SHORT).show()
        }

        header(R.string.wisp_set_passwords)
        switch(R.string.wisp_set_save_passwords, R.string.wisp_set_save_passwords_sum, config.sofaSavePasswords) {
            config.sofaSavePasswords = it
        }
        action(R.string.wisp_set_saved_passwords, null) { activity.openPasswordManager() }

        note(R.string.wisp_privacy_note)
    }

    private fun adblockPage() {
        switch(R.string.wisp_set_block_ads, R.string.wisp_set_block_ads_sum, config.adBlockEnabled) {
            config.adBlockEnabled = it
            render()
        }
        if (!config.adBlockEnabled) return
        switch(R.string.wisp_set_cosmetic, R.string.wisp_set_cosmetic_sum, config.sofaCosmeticFilter) {
            config.sofaCosmeticFilter = it
        }
        switch(R.string.wisp_set_popups, R.string.wisp_set_popups_sum, config.sofaPopupBlocker) {
            config.sofaPopupBlocker = it
        }
        switch(R.string.wisp_set_small_ads, R.string.wisp_set_small_ads_sum, config.wispHideSmallAds) {
            config.wispHideSmallAds = it
        }
        header(R.string.wisp_set_filter_lists)
        val lists = config.adBlockListURL.value.split('\n', ' ', ',').count { it.isNotBlank() }
        action(R.string.wisp_set_filter_lists, activity.resources.getQuantityString(R.plurals.wisp_lists_n, lists, lists)) {
            textInput(R.string.wisp_set_filter_lists, config.adBlockListURL.value, "https://…", multiLine = true) { text ->
                config.adBlockListURL.value = text.trim().ifEmpty { Config.DEFAULT_ADBLOCK_LIST_URL }
                render()
            }
        }
        val last = if (config.adBlockListLastUpdate == 0L) str(R.string.never)
            else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(config.adBlockListLastUpdate))
        val loading = adblockModel.clientLoading.value
        action(R.string.wisp_set_update_lists, if (loading) str(R.string.wisp_set_updating) else str(R.string.wisp_set_last_update_s, last),
            enabled = !loading) {
            adblockModel.loadAdBlockList(true)
            render()
        }
        val sites = config.adBlockDisabledHosts.sorted()
        action(R.string.wisp_set_adblock_sites, if (sites.isEmpty()) str(R.string.wisp_set_none) else sites.joinToString(", ")) {
            editSiteList(R.string.wisp_set_adblock_sites, sites) { config.adBlockDisabledHosts = it.toSet() }
        }
        note(R.string.wisp_set_reload_note)
    }

    private fun contentPage() {
        header(R.string.wisp_set_dark)
        switch(R.string.wisp_set_force_dark, R.string.wisp_set_force_dark_sum, config.sofaForceDark) { config.sofaForceDark = it }
        switch(R.string.wisp_set_algo_dark, R.string.wisp_set_algo_dark_sum, config.webviewUseAlgorithmicDarkeningWithDarkUiMode) {
            config.webviewUseAlgorithmicDarkeningWithDarkUiMode = it
        }
        header(R.string.wisp_set_media)
        switch(R.string.wisp_set_autoplay, R.string.wisp_set_autoplay_sum, config.allowAutoplayMedia) { config.allowAutoplayMedia = it }
        switch(R.string.wisp_set_max_quality, R.string.wisp_set_max_quality_sum, config.sofaAutoMaxQuality) { config.sofaAutoMaxQuality = it }
        switch(R.string.wisp_set_stop_mini, R.string.wisp_set_stop_mini_sum, config.lumenStopMiniPlayer) { config.lumenStopMiniPlayer = it }
        switch(R.string.wisp_set_subtitle_box, R.string.wisp_set_subtitle_box_sum, config.lumenSubtitleBox) { config.lumenSubtitleBox = it }
        header(R.string.wisp_set_scripts)
        action(R.string.wisp_set_user_script,
            if (config.sofaUserScript.isBlank()) str(R.string.wisp_set_none) else str(R.string.wisp_set_user_script_set)) {
            textInput(R.string.wisp_set_user_script, config.sofaUserScript, "document.body.style…", multiLine = true, code = true) {
                config.sofaUserScript = it
                render()
            }
        }
        note(R.string.wisp_set_reload_note)
    }

    private fun downloadsPage() {
        action(R.string.wisp_set_download_folder, LumenDownloads.folderLabel(activity)) {
            activity.pickDownloadFolder { render() }
        }
        if (config.lumenDownloadFolderUri.isNotEmpty()) {
            action(R.string.wisp_set_default_folder, null) {
                config.lumenDownloadFolderUri = ""
                render()
            }
        }
        val parts = (1..16).toList()
        choice(R.string.wisp_set_parts, parts.map { it.toString() }, (config.lumenDownloadParts - 1).coerceIn(0, 15),
            summary = str(R.string.wisp_set_parts_sum)) { i -> config.lumenDownloadParts = parts[i] }
        val max = (1..6).toList()
        choice(R.string.wisp_set_max_downloads, max.map { it.toString() }, (config.lumenMaxDownloads - 1).coerceIn(0, 5)) { i ->
            config.lumenMaxDownloads = max[i]
        }
        //KB/s: off, 0.5, 1, 2, 3, 5, 8, 12, 20, 40 MB/s
        val limits = intArrayOf(0, 512, 1024, 2048, 3072, 5120, 8192, 12288, 20480, 40960)
        fun limitText(k: Int) = if (k == 0) str(R.string.wisp_set_no_limit) else if (k < 1024) "$k KB/s" else
            (if (k % 1024 == 0) (k / 1024).toString() else String.format(Locale.US, "%.1f", k / 1024.0)) + " MB/s"
        val cur = limits.indices.minByOrNull { kotlin.math.abs(limits[it] - config.lumenSpeedLimitKBps) } ?: 0
        choice(R.string.wisp_set_speed_limit, limits.map { limitText(it) }, cur) { i -> config.lumenSpeedLimitKBps = limits[i] }
    }

    private fun vpnPage() {
        val status = SofaVpn.status.value
        val configured = status != SofaVpn.Status.NOT_CONFIGURED
        action(R.string.wisp_set_vpn_status, vpnStatusText(), enabled = false) {}
        if (configured) {
            val connected = status == SofaVpn.Status.CONNECTED
            action(if (connected) R.string.sofa_vpn_disconnect else R.string.sofa_vpn_connect, null,
                enabled = status != SofaVpn.Status.CONNECTING) {
                if (connected) activity.lifecycleScope.launch { SofaVpn.disconnect(activity.applicationContext) }
                else activity.requestVpnConnect()
            }
        }
        header(R.string.wisp_set_vpn_config)
        action(R.string.sofa_vpn_import_file, null) { activity.pickVpnConfigFile() }
        action(R.string.sofa_vpn_paste, null) { pasteVpnConfig() }
        action(R.string.sofa_vpn_import_any, str(R.string.sofa_vpn_import_any_sum)) { activity.pickVpnFileOrArchive() }
        if (configured) {
            action(R.string.sofa_vpn_remove, null, danger = true) {
                confirm(R.string.sofa_vpn_remove, str(R.string.sofa_vpn_remove_q)) { SofaVpn.removeConfig(activity) }
            }
        }
        header(R.string.wisp_set_vpn_options)
        switch(R.string.sofa_vpn_browser_only, R.string.sofa_vpn_browser_only_desc, config.sofaVpnBrowserOnly) { config.sofaVpnBrowserOnly = it }
        switch(R.string.sofa_vpn_auto_connect, R.string.sofa_vpn_auto_connect_desc, config.sofaVpnAutoConnect) { config.sofaVpnAutoConnect = it }
    }

    // ---------------- security & backup ----------------

    private fun securityPage() {
        header(R.string.wisp_set_backup)
        action(R.string.wisp_set_backup_now, str(R.string.wisp_set_backup_now_sum)) {
            dialog.dismiss()
            activity.startBackup()
        }
        action(R.string.wisp_set_restore, str(R.string.wisp_set_restore_sum)) {
            dialog.dismiss()
            activity.startRestore()
        }
        note(R.string.wisp_set_backup_note)
    }

    // ---------------- site settings ----------------

    private var siteShown = ""

    /** Every site with a setting of its own, by site ("m.", "www." and the plain address together). */
    private fun customizedSites(): List<String> {
        val all = HashSet<String>()
        fun add(list: Collection<String>) = list.forEach { val b = Config.baseSite(it); if (b.isNotEmpty()) all.add(b) }
        add(config.desktopSites)
        add(config.wispKeepSites)
        add(config.wispThirdPartyCookieSites)
        add(config.adBlockDisabledHosts)
        add(config.wispHiddenElements.map { it.substringBefore('\t') })
        add(config.lumenFirefoxSites)
        add(config.lumenExternalSites)
        return all.sorted()
    }

    private fun siteSummary(site: String): String {
        val parts = ArrayList<String>()
        if (config.isDesktopSite(site)) parts.add(str(R.string.wisp_desktop))
        if (config.isKeepSite(site)) parts.add(str(R.string.wisp_stay_logged_in))
        if (config.isThirdPartyCookieSite(site)) parts.add(str(R.string.wisp_site_3p_cookies))
        if (config.isAdBlockDisabledForHost(site)) parts.add(str(R.string.wisp_site_adblock_off))
        val hidden = config.wispHiddenFor(site).size
        if (hidden > 0) parts.add(activity.resources.getQuantityString(R.plurals.wisp_hidden_n, hidden, hidden))
        return parts.joinToString(" · ")
    }

    private fun sitesPage() {
        val sites = customizedSites()
        if (sites.isEmpty()) {
            note(R.string.wisp_set_sites_none)
            return
        }
        for (site in sites) {
            row(site, siteSummary(site), R.drawable.ic_wisp_globe, null) {
                siteShown = site
                open(Page.SITE)
            }
        }
    }

    private fun removeSite(list: Set<String>, site: String) = list.filterNot { Config.baseSite(it) == site }.toSet()

    private fun sitePage(site: String) {
        switch(R.string.wisp_desktop, R.string.wisp_set_site_desktop_sum, config.isDesktopSite(site)) { on ->
            config.desktopSites = removeSite(config.desktopSites, site) + (if (on) setOf(site) else emptySet())
        }
        switch(R.string.wisp_stay_logged_in, R.string.wisp_stay_logged_in_sum, config.isKeepSite(site)) { on ->
            config.wispKeepSites = removeSite(config.wispKeepSites, site) + (if (on) setOf(site) else emptySet())
        }
        switch(R.string.wisp_site_3p_cookies, R.string.wisp_site_3p_cookies_desc, config.isThirdPartyCookieSite(site)) { on ->
            config.wispThirdPartyCookieSites = removeSite(config.wispThirdPartyCookieSites, site) + (if (on) setOf(site) else emptySet())
        }
        switch(R.string.wisp_site_adblock, R.string.wisp_set_site_adblock_sum, !config.isAdBlockDisabledForHost(site)) { on ->
            config.adBlockDisabledHosts = removeSite(config.adBlockDisabledHosts, site) + (if (on) emptySet() else setOf(site))
        }
        val hidden = config.wispHiddenFor(site).size
        if (hidden > 0) {
            action(R.string.wisp_site_unhide, activity.resources.getQuantityString(R.plurals.wisp_hidden_n, hidden, hidden)) {
                config.clearWispHidden(site)
                render()
            }
        }
        action(R.string.wisp_set_site_reset, null, danger = true) {
            config.desktopSites = removeSite(config.desktopSites, site)
            config.wispKeepSites = removeSite(config.wispKeepSites, site)
            config.wispThirdPartyCookieSites = removeSite(config.wispThirdPartyCookieSites, site)
            config.adBlockDisabledHosts = removeSite(config.adBlockDisabledHosts, site)
            config.lumenFirefoxSites = removeSite(config.lumenFirefoxSites, site)
            config.lumenExternalSites = removeSite(config.lumenExternalSites, site)
            config.clearWispHidden(site)
            Toast.makeText(activity, R.string.wisp_set_done, Toast.LENGTH_SHORT).show()
            dialog.onBackPressed()
        }
        note(R.string.wisp_set_site_note)
    }

    private fun aboutPage() {
        nav(R.drawable.ic_wisp_diag, R.string.wisp_diag, str(R.string.wisp_diag_sum), Page.DIAG)
        action(R.string.wisp_set_version, "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}\n" +
                str(R.string.wisp_set_engine_s, WebEngineFactory.getWebEngineVersionString()), enabled = false) {}
        header(R.string.wisp_set_updates)
        action(R.string.wisp_set_check_updates, null) {
            dialog.dismiss()
            LumenUpdater.check(activity, quiet = false)
        }
        switch(R.string.wisp_set_auto_updates, R.string.wisp_set_auto_updates_sum, config.lumenAutoCheckUpdates) {
            config.lumenAutoCheckUpdates = it
        }
        action(R.string.lumen_update_source, config.lumenUpdateRepo +
                if (LumenUpdater.hasToken()) " · " + str(R.string.wisp_set_token_saved) else "") { updateSource() }
        action(R.string.wisp_set_browser_check, str(R.string.wisp_set_browser_check_sum)) {
            dialog.dismiss()
            activity.openBrowserCheck()
        }
        header(R.string.wisp_set_credits)
        note(R.string.sofabrowse_credit)
        action(R.string.view_license, null) {
            dialog.dismiss()
            activity.navigate("https://github.com/truefedex/tv-bro/blob/master/LICENSE.md")
        }
    }

    private fun diagPage() {
        val log = com.phlox.tvwebbrowser.sofa.WispLog
        log.flush()
        val lines = log.lines(activity)
        val errors = lines.count { it.startsWith("E ") && !it.contains("[check]") }
        val checks = lines.count { it.contains("[check]") }
        note(R.string.wisp_diag_note)
        val last = log.lastProblem(activity)?.lineSequence()?.take(3)?.joinToString("\n")?.take(400)
        row(str(R.string.wisp_diag_last_problem), last ?: str(R.string.wisp_diag_none), enabled = false, onClick = null)
        row(str(R.string.wisp_diag_counts_s, lines.size, errors, checks), null, enabled = false, onClick = null)
        action(R.string.wisp_diag_copy, str(R.string.wisp_diag_copy_sum)) {
            copyText(log.report(activity))
            Toast.makeText(activity, R.string.wisp_diag_copied, Toast.LENGTH_SHORT).show()
        }
        action(R.string.wisp_diag_share, str(R.string.wisp_diag_share_sum)) {
            log.flush()
            val pics = com.phlox.tvwebbrowser.sofa.WispSnapshot.sharePictures(activity)
            val send = (if (pics.isEmpty()) android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
                    .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, pics)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Wisp diagnostics")
                .putExtra(android.content.Intent.EXTRA_TEXT, log.report(activity))
            try {
                activity.startActivity(android.content.Intent.createChooser(send, str(R.string.wisp_diag_share)))
            } catch (e: Exception) {
                Toast.makeText(activity, e.message ?: "", Toast.LENGTH_SHORT).show()
            }
        }
        action(R.string.wisp_diag_report, str(R.string.wisp_diag_report_sum)) {
            //the picture is of the browser behind Settings; Settings' own layout goes in as text
            activity.reportProblem(dialog.window) { render() }
        }
        switch(R.string.wisp_diag_full, R.string.wisp_diag_full_sum, log.fullAddresses) { log.fullAddresses = it }
        action(R.string.wisp_diag_clear, null, danger = true) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.wisp_diag_clear_q)
                .setPositiveButton(R.string.wisp_diag_clear) { _, _ ->
                    log.clear(activity)
                    scroll.postDelayed({ render() }, 300)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        header(R.string.wisp_diag_recent)
        val t = TextView(activity)
        t.text = lines.takeLast(60).reversed().joinToString("\n").ifEmpty { str(R.string.wisp_diag_none) }
        t.typeface = Typeface.MONOSPACE
        t.textSize = 11f
        t.setTextColor(color(R.color.wisp_text_secondary))
        t.setTextIsSelectable(true)
        t.setPadding(px(20f), px(4f), px(20f), px(24f))
        content.addView(t)
    }

    private fun copyText(text: String) {
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Wisp diagnostics", text))
    }

    // ---------------- actions ----------------

    private fun vpnStatusText(): String {
        val server = SofaVpn.endpointLabel()
        return when (SofaVpn.status.value) {
            SofaVpn.Status.NOT_CONFIGURED -> str(R.string.sofa_vpn_status_none)
            SofaVpn.Status.DISCONNECTED -> str(R.string.sofa_vpn_status_off, server ?: "")
            SofaVpn.Status.CONNECTING -> str(R.string.sofa_vpn_status_connecting)
            SofaVpn.Status.CONNECTED -> str(R.string.sofa_vpn_status_on, server ?: "",
                str(if (config.sofaVpnBrowserOnly) R.string.sofa_vpn_scope_browser else R.string.sofa_vpn_scope_device))
            SofaVpn.Status.ERROR -> str(R.string.sofa_vpn_status_error, SofaVpn.lastError ?: "")
        }
    }

    private fun clearBrowsingData() {
        val labels = arrayOf(str(R.string.wisp_clear_history), str(R.string.wisp_clear_cookies), str(R.string.wisp_clear_cache))
        val checked = booleanArrayOf(true, true, true)
        AlertDialog.Builder(activity)
            .setTitle(R.string.wisp_set_clear_now)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.wisp_clear) { _, _ ->
                activity.lifecycleScope.launch {
                    if (checked[0]) {
                        withContext(Dispatchers.IO) {
                            try { AppDatabase.db.historyDao().deleteWhereTimeLessThan(Long.MAX_VALUE) } catch (e: Exception) {}
                        }
                        activity.clearClosedTabs() //"Recently closed" is history too
                    }
                    if (checked[1]) {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                    }
                    if (checked[2]) WebEngineFactory.clearCache(activity)
                    Toast.makeText(activity, R.string.wisp_set_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A list of sites; untick the ones to remove. */
    private fun editSiteList(titleRes: Int, sites: List<String>, save: (List<String>) -> Unit) {
        if (sites.isEmpty()) {
            Toast.makeText(activity, R.string.wisp_set_sites_empty, Toast.LENGTH_LONG).show()
            return
        }
        val checked = BooleanArray(sites.size) { true }
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMultiChoiceItems(sites.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.wisp_save) { _, _ ->
                save(sites.filterIndexed { i, _ -> checked[i] })
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pasteVpnConfig() {
        textInput(R.string.sofa_vpn_paste, "", "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …",
            multiLine = true, code = true) { text ->
            val error = SofaVpn.importConfig(text)
            Toast.makeText(activity,
                if (error == null) str(R.string.sofa_vpn_imported) else str(R.string.sofa_vpn_bad_config, error),
                Toast.LENGTH_LONG).show()
            SofaVpn.refreshStatus()
            render()
        }
    }

    private fun updateSource() {
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(px(22f), px(8f), px(22f), 0)
        val repo = EditText(activity)
        repo.hint = "owner/repository"
        repo.isSingleLine = true
        repo.setText(config.lumenUpdateRepo)
        val token = EditText(activity)
        token.hint = str(R.string.lumen_update_token_hint)
        token.isSingleLine = true
        token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val help = TextView(activity)
        help.text = str(R.string.lumen_update_token_help)
        help.textSize = 13f
        help.setTextColor(color(R.color.wisp_text_secondary))
        help.setPadding(0, px(8f), 0, 0)
        box.addView(repo)
        box.addView(token)
        box.addView(help)
        AlertDialog.Builder(activity)
            .setTitle(R.string.lumen_update_source)
            .setView(box)
            .setPositiveButton(R.string.wisp_save) { _, _ ->
                val r = repo.text.toString().trim().removePrefix("https://github.com/").trim('/')
                if (r.matches(Regex("""[\w.-]+/[\w.-]+"""))) config.lumenUpdateRepo = r
                val t = token.text.toString().trim()
                if (t.isNotEmpty()) LumenUpdater.setToken(t)
                render()
            }
            .setNeutralButton(R.string.lumen_update_token_clear) { _, _ ->
                LumenUpdater.setToken(null)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun textInput(titleRes: Int, value: String, hint: String, multiLine: Boolean = false, code: Boolean = false,
                          save: (String) -> Unit) {
        val box = LinearLayout(activity)
        box.setPadding(px(22f), px(8f), px(22f), 0)
        val edit = EditText(activity)
        edit.setText(value)
        edit.hint = hint
        if (multiLine) {
            edit.minLines = 4
            edit.maxLines = 10
            edit.gravity = Gravity.TOP or Gravity.START
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            edit.isSingleLine = true
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (code) {
            edit.typeface = Typeface.MONOSPACE
            edit.textSize = 13f
        }
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(R.string.wisp_save) { _, _ -> save(edit.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(titleRes: Int, message: String, yes: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> yes() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- rows ----------------

    private fun ripple(): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getDrawable(activity, tv.resourceId) else null
    }

    private fun header(textRes: Int, text: String? = null) {
        val t = TextView(activity)
        t.text = text ?: str(textRes)
        t.textSize = 13f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextColor(color(R.color.wisp_accent))
        t.setPadding(px(20f), px(22f), px(20f), px(6f))
        content.addView(t)
    }

    private fun note(textRes: Int) {
        val t = TextView(activity)
        t.text = str(textRes)
        t.textSize = 13f
        t.setTextColor(color(R.color.wisp_text_secondary))
        t.setPadding(px(20f), px(16f), px(20f), px(8f))
        content.addView(t)
    }

    /** Title, second line, and something on the right (switch, chevron or nothing). */
    private fun row(title: String, summary: String?, icon: Int? = null, right: View? = null, enabled: Boolean = true,
                    danger: Boolean = false, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = px(56f)
        row.setPadding(px(20f), px(10f), px(16f), px(10f))
        if (onClick != null && enabled) {
            row.background = ripple()
            row.setOnClickListener { onClick() }
        }
        if (icon != null) {
            val iv = ImageView(activity)
            iv.setImageResource(icon)
            iv.setColorFilter(color(R.color.wisp_icon))
            val lp = LinearLayout.LayoutParams(px(24f), px(24f))
            lp.marginEnd = px(20f)
            row.addView(iv, lp)
        }
        val texts = LinearLayout(activity)
        texts.orientation = LinearLayout.VERTICAL
        val t = TextView(activity)
        t.text = title
        t.textSize = 16f
        t.setTextColor(if (danger) 0xFFD84343.toInt() else color(R.color.wisp_text))
        texts.addView(t)
        if (!summary.isNullOrEmpty()) {
            val s = TextView(activity)
            s.text = summary
            s.textSize = 13.5f
            s.setTextColor(color(R.color.wisp_text_secondary))
            s.maxLines = 4
            s.ellipsize = TextUtils.TruncateAt.END
            s.setPadding(0, px(2f), 0, 0)
            texts.addView(s)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = px(12f)
            row.addView(right, lp)
        }
        row.alpha = if (enabled || onClick == null) 1f else 0.4f
        content.addView(row)
        return row
    }

    private fun nav(icon: Int, titleRes: Int, summary: String?, page: Page) {
        val chevron = ImageView(activity)
        chevron.setImageResource(R.drawable.ic_wisp_chevron)
        chevron.setColorFilter(color(R.color.wisp_text_secondary))
        row(str(titleRes), summary, icon, chevron) { open(page) }
    }

    private fun switch(titleRes: Int, summaryRes: Int, value: Boolean, save: (Boolean) -> Unit) {
        val sw = android.widget.Switch(activity)
        sw.isChecked = value
        sw.isClickable = false
        sw.isFocusable = false
        WispSheets.tintSwitch(sw, activity)
        row(str(titleRes), str(summaryRes), right = sw) {
            sw.isChecked = !sw.isChecked
            save(sw.isChecked)
        }
    }

    private fun action(titleRes: Int, summary: String?, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
        row(str(titleRes), summary, enabled = enabled, danger = danger, onClick = onClick)
    }

    /** A setting with a few choices: shows the current one, opens a list to pick another. */
    private fun choice(titleRes: Int, labels: List<String>, current: Int, summary: String? = null, pick: (Int) -> Unit) {
        val shown = labels.getOrNull(current) ?: ""
        row(str(titleRes), if (summary != null) "$shown · $summary" else shown) {
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                    d.dismiss()
                    pick(which)
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
