package com.phlox.tvwebbrowser

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.observable.ObservableValue

class Config(val prefs: SharedPreferences) {
    companion object {
        const val SEARCH_ENGINE_URL_PREF_KEY = "search_engine_url"
        const val SEARCH_ENGINE_AS_HOME_PAGE_KEY = "search_engine_as_home_page"
        const val HOME_PAGE_KEY = "home_page"
        const val USER_AGENT_PREF_KEY = "user_agent"
        const val THEME_KEY = "theme"
        const val LAST_UPDATE_USER_NOTIFICATION_TIME_KEY = "last_update_notif"
        const val AUTO_CHECK_UPDATES_KEY = "auto_check_updates"
        const val UPDATE_CHANNEL_KEY = "update_channel"
        const val TV_BRO_UA_PREFIX = "TV Bro/1.0 "
        const val HOME_URL_ALIAS = "about:home"
        const val KEEP_SCREEN_ON_KEY = "keep_screen_on"
        /** When true, analog stick / hat axes from generic motion events are not translated to DPAD keys. */
        const val DISABLE_MOTION_AXES_DPAD_NAVIGATION_KEY = "disable_motion_axes_dpad_navigation"
        /** Percent of built-in default (100 = default). Range [CURSOR_PHYSICS_PERCENT_MIN], [CURSOR_PHYSICS_PERCENT_MAX]. */
        const val CURSOR_MAX_SPEED_PERCENT_KEY = "cursor_max_speed_percent"
        const val CURSOR_ACCELERATION_PERCENT_KEY = "cursor_acceleration_percent"
        const val CURSOR_PHYSICS_PERCENT_MIN = 25
        const val CURSOR_PHYSICS_PERCENT_MAX = 200
        const val INCOGNITO_MODE_KEY = "incognito_mode"
        const val INCOGNITO_MODE_HINT_SUPPRESS_KEY = "incognito_mode_hint_suppress"
        const val DIRECT_NAVIGATION_MODE_HINT_SUPPRESS_KEY = "direct_navigation_mode_hint_suppress"
        const val HOME_PAGE_MODE = "home_page_mode"
        const val HOME_PAGE_SUGGESTIONS_MODE = "home_page_suggestions_mode"
        const val WEB_ENGINE = "web_engine"
        const val ALLOW_AUTOPLAY_MEDIA = "allow_autoplay_media"
        const val WEBVIEW_USE_ALGORITHMIC_DARKENING_WITH_DARK_UI_MODE_KEY =
            "webview_use_algorithmic_darkening_with_dark_ui_mode"
        const val WEB_ENGINE_DEBUG_KEY = "web_engine_debug"
        //const val HOME_PAGE_VERSION_EXTRACTED = "home_page_version_extracted"
        const val INITIAL_BOOKMARKS_SUGGESTIONS_LOADED = "initial_bookmarks_suggestions_loaded"
        const val ADBLOCK_ENABLED_PREF_KEY = "adblock_enabled"
        const val ADBLOCK_LAST_UPDATE_LIST_KEY = "adblock_last_update"
        const val ADBLOCK_LIST_URL_KEY = "adblock_list_url"
        const val APP_WEB_EXTENSION_VERSION_KEY = "app_web_extension_version"
        const val NOTIFICATION_ABOUT_ENGINE_CHANGE_SHOWN_KEY = "notification_about_engine_change_shown"
        const val APP_VERSION_CODE_MARK_KEY = "app_version_code_mark"

        const val ENGINE_GECKO_VIEW = "GeckoView"
        const val ENGINE_WEB_VIEW = "WebView"

        //SofaBrowse: several lists, separated by whitespace/new lines
        const val DEFAULT_ADBLOCK_LIST_URL = "https://easylist.to/easylist/easylist.txt\n" +
                "https://easylist.to/easylist/easyprivacy.txt\n" +
                "https://ublockorigin.github.io/uAssets/filters/filters.txt\n" +
                "https://ublockorigin.github.io/uAssets/filters/badware.txt\n" +
                "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"

        //SofaBrowse extras
        const val SOFA_COSMETIC_FILTER_KEY = "sofa_cosmetic_filter"
        const val SOFA_POPUP_BLOCKER_KEY = "sofa_popup_blocker"
        const val SOFA_PLAYER_OVERLAY_KEY = "sofa_player_overlay"
        const val SOFA_AUTO_MAX_QUALITY_KEY = "sofa_auto_max_quality"
        const val SOFA_DEINTERLACE_KEY = "sofa_deinterlace"
        const val SOFA_FORCE_DARK_KEY = "sofa_force_dark"
        const val SOFA_CLOUDFLARE_COMPAT_KEY = "sofa_cloudflare_compat"
        const val SOFA_USER_SCRIPT_KEY = "sofa_user_script"
        const val SOFA_PLAYER_TUNNELING_KEY = "sofa_player_tunneling"
        const val SOFA_MATCH_FRAME_RATE_KEY = "sofa_match_frame_rate"
        const val SOFA_SAVE_PASSWORDS_KEY = "sofa_save_passwords"
        const val SOFA_PASSWORD_NEVER_HOSTS_KEY = "sofa_password_never_hosts"
        const val SOFA_ADBLOCK_DISABLED_HOSTS_KEY = "sofa_adblock_disabled_hosts"
        const val SOFA_VPN_CONFIG_KEY = "sofa_vpn_config_enc"
        const val SOFA_VPN_BROWSER_ONLY_KEY = "sofa_vpn_browser_only"
        const val SOFA_VPN_AUTO_CONNECT_KEY = "sofa_vpn_auto_connect"
        const val SOFA_DESKTOP_SITES_KEY = "sofa_desktop_sites"
        const val LUMEN_CLEAR_HISTORY_ON_EXIT_KEY = "lumen_clear_history_on_exit"
        const val LUMEN_AUTO_HIDE_TOOLBAR_KEY = "lumen_auto_hide_toolbar"
        const val LUMEN_DEFAULT_UPDATE_REPO = "RonEH148/Player"
        const val LUMEN_CLEAR_COOKIES_ON_EXIT_KEY = "lumen_clear_cookies_on_exit"
        const val LUMEN_DOWNLOAD_FOLDER_URI_KEY = "lumen_download_folder_uri"
        const val LUMEN_DOWNLOAD_PARTS_KEY = "lumen_download_parts"

        /** Host used for per-site settings: lower case, without a leading "www." */
        fun siteKey(host: String?): String = (host ?: "").lowercase().removePrefix("www.")

        /** The site an address belongs to: "m.example.com" → "example.com", "news.bbc.co.uk" → "bbc.co.uk". */
        fun baseSite(host: String?): String {
            val h = siteKey(host).trimEnd('.')
            if (h.isEmpty() || h.all { it.isDigit() || it == '.' } || h.contains(':')) return h
            val p = h.split('.')
            if (p.size <= 2) return h
            val second = p[p.size - 2]
            val country = p.last().length == 2
            return if (country && second in setOf("co", "com", "net", "org", "gov", "edu", "ac", "ne", "or", "go"))
                p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
        }
        val SearchEnginesTitles = arrayOf("Google", "Bing", "Yahoo!", "DuckDuckGo", "Yandex", "Startpage", "Custom")
        val SearchEnginesNames = arrayOf("google", "bing", "yahoo", "ddg", "yandex", "startpage", "custom")
        val SearchEnginesURLs = listOf("https://www.google.com/search?q=[query]", "https://www.bing.com/search?q=[query]",
            "https://search.yahoo.com/search?p=[query]", "https://duckduckgo.com/?q=[query]",
            "https://yandex.com/search/?text=[query]", "https://www.startpage.com/sp/search?query=[query]", "")
        val SupportedWebEngines = arrayOf(ENGINE_GECKO_VIEW, ENGINE_WEB_VIEW)
        const val HOME_PAGE_URL = "https://tvbro.phlox.dev/appcontent/home/"
        //const val HOME_PAGE_URL = "http://10.0.2.2:5000/appcontent/home/"

        fun canRecommendGeckoView(): Boolean {
            //disable recommendation for now
            //There are plans to keep its support, but user reports and internal testing show
            // that, at least for now, in terms of performance and stability, it is inferior to WebView.
            return false
        }
    }

    enum class Theme {
        SYSTEM, WHITE, BLACK;
    }

    enum class HomePageMode {
        HOME_PAGE, SEARCH_ENGINE, CUSTOM, BLANK
    }

    enum class HomePageLinksMode {
        BOOKMARKS, LATEST_HISTORY, MOST_VISITED
    }

    var incognitoMode: Boolean
        get() = prefs.getBoolean(INCOGNITO_MODE_KEY, false)
        @SuppressLint("ApplySharedPref")
        set(value) {
            prefs.edit().putBoolean(INCOGNITO_MODE_KEY, value).commit()
        }

    var incognitoModeHintSuppress: Boolean
        get() = prefs.getBoolean(INCOGNITO_MODE_HINT_SUPPRESS_KEY, false)
        set(value) {
            prefs.edit().putBoolean(INCOGNITO_MODE_HINT_SUPPRESS_KEY, value).apply()
        }

    var directNavigationModeHintSuppress: Boolean
        get() = prefs.getBoolean(DIRECT_NAVIGATION_MODE_HINT_SUPPRESS_KEY, false)
        set(value) {
            prefs.edit().putBoolean(DIRECT_NAVIGATION_MODE_HINT_SUPPRESS_KEY, value).apply()
        }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON_KEY, false)
        set(value) {
            prefs.edit().putBoolean(KEEP_SCREEN_ON_KEY, value).apply()
        }

    var disableMotionAxesDpadNavigation: Boolean
        get() = prefs.getBoolean(DISABLE_MOTION_AXES_DPAD_NAVIGATION_KEY, false)
        set(value) {
            prefs.edit().putBoolean(DISABLE_MOTION_AXES_DPAD_NAVIGATION_KEY, value).apply()
        }

    /** Virtual cursor max speed as a percent of the default (screen-width–based) cap. */
    var cursorMaxSpeedPercent: Int
        get() = prefs.getInt(CURSOR_MAX_SPEED_PERCENT_KEY, 100)
            .coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
        set(value) {
            prefs.edit().putInt(
                CURSOR_MAX_SPEED_PERCENT_KEY,
                value.coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
            ).apply()
        }

    /** Virtual cursor acceleration as a percent of the default ramp-up per frame. */
    var cursorAccelerationPercent: Int
        get() = prefs.getInt(CURSOR_ACCELERATION_PERCENT_KEY, 100)
            .coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
        set(value) {
            prefs.edit().putInt(
                CURSOR_ACCELERATION_PERCENT_KEY,
                value.coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
            ).apply()
        }

    var theme = object : ObservableValue<Theme>(Theme.SYSTEM) {
        override var value: Theme = Theme.entries[prefs.getInt(THEME_KEY, 0)]
            set(value) {
                prefs.edit().putInt(THEME_KEY, value.ordinal).apply()
                field = value
                notifyObservers()
            }
    }

    var homePageMode: HomePageMode
        get() = prefs.getInt(HOME_PAGE_MODE, 0)
            .let {
                //ignore value if search engine as home page is set
                if (prefs.getBoolean(SEARCH_ENGINE_AS_HOME_PAGE_KEY, false)) {
                    prefs.edit().remove(SEARCH_ENGINE_AS_HOME_PAGE_KEY).apply()
                    HomePageMode.SEARCH_ENGINE.ordinal
                } else it
            }
            .let { if (it < 0 || it >= HomePageMode.values().size) 0 else it }
            .let { HomePageMode.values()[it] }
        set(value) {
            prefs.edit().putInt(HOME_PAGE_MODE, value.ordinal).apply()
        }

    var homePageLinksMode: HomePageLinksMode
        get() = prefs.getInt(HOME_PAGE_SUGGESTIONS_MODE, 0)
            .let { if (it < 0 || it >= HomePageLinksMode.values().size) 0 else it }
            .let { HomePageLinksMode.values()[it] }
        set(value) {
            prefs.edit().putInt(HOME_PAGE_SUGGESTIONS_MODE, value.ordinal).apply()
        }

    var homePage: String
        get() = prefs.getString(HOME_PAGE_KEY, HOME_URL_ALIAS)!!
        set(value) {
            prefs.edit().putString(HOME_PAGE_KEY, value).apply()
        }

    //Wisp (phone, de-Googled) starts with DuckDuckGo; Lumen keeps TV Bro's default
    var searchEngineURL = ObservableStringPreference(
        if (Brand.phone) SearchEnginesURLs[SearchEnginesNames.indexOf("ddg")] else SearchEnginesURLs[0],
        SEARCH_ENGINE_URL_PREF_KEY)

    var webEngine: String
        get() {
            if (!prefs.contains(WEB_ENGINE)) {
                prefs.edit().putString(
                    WEB_ENGINE, if (canRecommendGeckoView())
                    SupportedWebEngines[0] else SupportedWebEngines[1]).apply()
            }

            return prefs.getString(WEB_ENGINE, SupportedWebEngines[0])!!
        }
        set(value) {
            prefs.edit().putString(WEB_ENGINE, value).apply()
        }

    var allowAutoplayMedia: Boolean
        get() = prefs.getBoolean(ALLOW_AUTOPLAY_MEDIA, false)
        set(value) {
            prefs.edit().putBoolean(ALLOW_AUTOPLAY_MEDIA, value).apply()
        }

    var webviewUseAlgorithmicDarkeningWithDarkUiMode: Boolean
        get() = prefs.getBoolean(WEBVIEW_USE_ALGORITHMIC_DARKENING_WITH_DARK_UI_MODE_KEY, true)
        set(value) {
            prefs.edit().putBoolean(WEBVIEW_USE_ALGORITHMIC_DARKENING_WITH_DARK_UI_MODE_KEY, value).apply()
        }

    var webEngineDebug: Boolean
        get() = prefs.getBoolean(WEB_ENGINE_DEBUG_KEY, false)
        set(value) {
            prefs.edit().putBoolean(WEB_ENGINE_DEBUG_KEY, value).apply()
        }

    /*var homePageVersionExtracted: Int
        get() = prefs.getInt(HOME_PAGE_VERSION_EXTRACTED, 0)
        set(value) {
            prefs.edit().putInt(HOME_PAGE_VERSION_EXTRACTED, value).apply()
        }*/

    var initialBookmarksSuggestionsLoaded: Boolean
        get() = prefs.getBoolean(INITIAL_BOOKMARKS_SUGGESTIONS_LOADED, false)
        set(value) {
            prefs.edit().putBoolean(INITIAL_BOOKMARKS_SUGGESTIONS_LOADED, value).apply()
        }

    var userAgentString = ObservableOptStringPreference(null, USER_AGENT_PREF_KEY)

    var adBlockEnabled: Boolean = prefs.getBoolean(ADBLOCK_ENABLED_PREF_KEY, true)
        set(value) {
            field = value
            prefs.edit().putBoolean(ADBLOCK_ENABLED_PREF_KEY, field).apply()
        }

    var adBlockListURL = ObservableStringPreference(DEFAULT_ADBLOCK_LIST_URL, ADBLOCK_LIST_URL_KEY)

    var adBlockListLastUpdate: Long
        get() = prefs.getLong(ADBLOCK_LAST_UPDATE_LIST_KEY, 0)
        set(value) {
            prefs.edit().putLong(ADBLOCK_LAST_UPDATE_LIST_KEY, value).apply()
        }

    var appWebExtensionVersion: Int
        get() = prefs.getInt(APP_WEB_EXTENSION_VERSION_KEY, 0)
        set(value) {
            prefs.edit().putInt(APP_WEB_EXTENSION_VERSION_KEY, value).apply()
        }

    //0 - not shown, any other value - app version code where notification already shown (to show only once after update)
    var notificationAboutEngineChangeShown: Int
        get() = prefs.getInt(NOTIFICATION_ABOUT_ENGINE_CHANGE_SHOWN_KEY, 0)
        set(value) {
            prefs.edit().putInt(NOTIFICATION_ABOUT_ENGINE_CHANGE_SHOWN_KEY, value).apply()
        }

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(AUTO_CHECK_UPDATES_KEY, Utils.isInstalledByAPK(AppContext.get()))
        set(value) {
            prefs.edit().putBoolean(AUTO_CHECK_UPDATES_KEY, value).apply()
        }

    var updateChannel: String
        get() = prefs.getString(UPDATE_CHANNEL_KEY, "release")!!
        set(value) {
            prefs.edit().putString(UPDATE_CHANNEL_KEY, value).apply()
        }

    var appVersionCodeMark: Int
        get() = prefs.getInt(APP_VERSION_CODE_MARK_KEY, 0)
        set(value) {
            prefs.edit().putInt(APP_VERSION_CODE_MARK_KEY, value).apply()
        }

    var sofaCosmeticFilter: Boolean
        get() = prefs.getBoolean(SOFA_COSMETIC_FILTER_KEY, true)
        set(value) { prefs.edit().putBoolean(SOFA_COSMETIC_FILTER_KEY, value).apply() }

    var sofaPopupBlocker: Boolean
        get() = prefs.getBoolean(SOFA_POPUP_BLOCKER_KEY, true)
        set(value) { prefs.edit().putBoolean(SOFA_POPUP_BLOCKER_KEY, value).apply() }

    var sofaPlayerOverlay: Boolean
        get() = prefs.getBoolean(SOFA_PLAYER_OVERLAY_KEY, true)
        set(value) { prefs.edit().putBoolean(SOFA_PLAYER_OVERLAY_KEY, value).apply() }

    /** Lumen: the remote's cursor clicks like a mouse instead of a finger (TVs have no touch screen). */
    var lumenMouseClicks: Boolean
        get() = prefs.getBoolean("lumen_mouse_clicks", false)
        set(value) { prefs.edit().putBoolean("lumen_mouse_clicks", value).apply() }

    /** Lumen: pause and close a site's floating mini-player when its video page is left. */
    var lumenStopMiniPlayer: Boolean
        get() = prefs.getBoolean("lumen_stop_mini_player", true)
        set(value) { prefs.edit().putBoolean("lumen_stop_mini_player", value).apply() }

    var sofaAutoMaxQuality: Boolean
        //Wisp: off by default - on a phone it costs mobile data and makes videos start slower
        get() = prefs.getBoolean(SOFA_AUTO_MAX_QUALITY_KEY, !Brand.phone)
        set(value) { prefs.edit().putBoolean(SOFA_AUTO_MAX_QUALITY_KEY, value).apply() }

    /** Remembered state of the deinterlace toggle in the video controls. */
    var sofaDeinterlace: Boolean
        get() = prefs.getBoolean(SOFA_DEINTERLACE_KEY, false)
        set(value) { prefs.edit().putBoolean(SOFA_DEINTERLACE_KEY, value).apply() }

    var sofaForceDark: Boolean
        get() = prefs.getBoolean(SOFA_FORCE_DARK_KEY, false)
        set(value) { prefs.edit().putBoolean(SOFA_FORCE_DARK_KEY, value).apply() }

    var sofaCloudflareCompat: Boolean
        get() = prefs.getBoolean(SOFA_CLOUDFLARE_COMPAT_KEY, true)
        set(value) { prefs.edit().putBoolean(SOFA_CLOUDFLARE_COMPAT_KEY, value).apply() }

    var sofaUserScript: String
        get() = prefs.getString(SOFA_USER_SCRIPT_KEY, "") ?: ""
        set(value) { prefs.edit().putString(SOFA_USER_SCRIPT_KEY, value).apply() }

    var sofaPlayerTunneling: Boolean
        get() = prefs.getBoolean(SOFA_PLAYER_TUNNELING_KEY, false)
        set(value) { prefs.edit().putBoolean(SOFA_PLAYER_TUNNELING_KEY, value).apply() }

    var sofaMatchFrameRate: Boolean
        get() = prefs.getBoolean(SOFA_MATCH_FRAME_RATE_KEY, true)
        set(value) { prefs.edit().putBoolean(SOFA_MATCH_FRAME_RATE_KEY, value).apply() }

    var sofaSavePasswords: Boolean
        get() = prefs.getBoolean(SOFA_SAVE_PASSWORDS_KEY, true)
        set(value) { prefs.edit().putBoolean(SOFA_SAVE_PASSWORDS_KEY, value).apply() }

    var sofaPasswordNeverHosts: Set<String>
        get() = HashSet(prefs.getStringSet(SOFA_PASSWORD_NEVER_HOSTS_KEY, emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet(SOFA_PASSWORD_NEVER_HOSTS_KEY, HashSet(value)).apply() }

    /** Sites where the ad blocker is switched off permanently. */
    var adBlockDisabledHosts: Set<String>
        get() = HashSet(prefs.getStringSet(SOFA_ADBLOCK_DISABLED_HOSTS_KEY, emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet(SOFA_ADBLOCK_DISABLED_HOSTS_KEY, HashSet(value)).apply() }

    fun isAdBlockDisabledForHost(host: String?): Boolean {
        val key = siteKey(host)
        if (key.isEmpty()) return false
        val disabled = adBlockDisabledHosts
        //Wisp: for the whole site (m., www. and the plain address alike), like "Desktop site"
        if (Brand.phone) { val base = baseSite(key); return disabled.any { baseSite(it) == base } }
        //also match parent domains: "example.com" covers "video.example.com"
        return disabled.any { key == it || key.endsWith(".$it") }
    }

    /** GitHub repository (owner/name) whose Releases contain Lumen builds. */
    var lumenUpdateRepo: String
        get() = prefs.getString("lumen_update_repo", LUMEN_DEFAULT_UPDATE_REPO)!!.ifBlank { LUMEN_DEFAULT_UPDATE_REPO }
        set(value) { prefs.edit().putString("lumen_update_repo", value.trim()).apply() }

    /** Read-only GitHub token for a private repository, encrypted with the Android keystore. */
    var lumenUpdateTokenEncrypted: String
        get() = prefs.getString("lumen_update_token", "")!!
        set(value) { prefs.edit().putString("lumen_update_token", value).apply() }

    var lumenAutoCheckUpdates: Boolean
        get() = prefs.getBoolean("lumen_auto_check_updates", true)
        set(value) { prefs.edit().putBoolean("lumen_auto_check_updates", value).apply() }

    var lumenLastUpdateCheck: Long
        get() = prefs.getLong("lumen_last_update_check", 0L)
        set(value) { prefs.edit().putLong("lumen_last_update_check", value).apply() }

    /** Sites (host, subdomains included) where anime deband switches on by itself. */
    var lumenDebandSites: Set<String>
        get() = prefs.getStringSet("lumen_deband_sites", emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet("lumen_deband_sites", value).apply() }

    fun isDebandSite(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val sites = lumenDebandSites
        var h: String = host.lowercase()
        while (true) {
            if (sites.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Switch anime deband on by itself when a page video shows interlacing lines. */
    var lumenAutoDetectLines: Boolean
        get() = prefs.getBoolean("lumen_auto_detect_lines", true)
        set(value) { prefs.edit().putBoolean("lumen_auto_detect_lines", value).apply() }

    /** Subtitle text size in Lumen's player: 0 small, 1 normal, 2 large, 3 huge. */
    var lumenSubtitleSize: Int
        get() = prefs.getInt("lumen_subtitle_size", 1)
        set(value) { prefs.edit().putInt("lumen_subtitle_size", value).apply() }

    /** Subtitles on a dark box (easier to read on bright scenes) instead of only an outline. */
    var lumenSubtitleBox: Boolean
        get() = prefs.getBoolean("lumen_subtitle_box", false)
        set(value) { prefs.edit().putBoolean("lumen_subtitle_box", value).apply() }

    /** Hide the top bar on websites while reading; it comes back at the top of the page. */
    var lumenAutoHideToolbar: Boolean
        get() = prefs.getBoolean(LUMEN_AUTO_HIDE_TOOLBAR_KEY, true)
        set(value) { prefs.edit().putBoolean(LUMEN_AUTO_HIDE_TOOLBAR_KEY, value).apply() }

    var lumenClearHistoryOnExit: Boolean
        get() = prefs.getBoolean(LUMEN_CLEAR_HISTORY_ON_EXIT_KEY, false)
        set(value) { prefs.edit().putBoolean(LUMEN_CLEAR_HISTORY_ON_EXIT_KEY, value).apply() }

    var lumenClearCookiesOnExit: Boolean
        get() = prefs.getBoolean(LUMEN_CLEAR_COOKIES_ON_EXIT_KEY, false)
        set(value) { prefs.edit().putBoolean(LUMEN_CLEAR_COOKIES_ON_EXIT_KEY, value).apply() }

    /** Folder picked with the system file manager (content:// tree URI), empty = default folder. */
    var lumenDownloadFolderUri: String
        get() = prefs.getString(LUMEN_DOWNLOAD_FOLDER_URI_KEY, "") ?: ""
        set(value) { prefs.edit().putString(LUMEN_DOWNLOAD_FOLDER_URI_KEY, value).apply() }

    /** Number of parallel connections per download (1 = off). */
    /** Lumen: downloads running at the same time; the others wait in the queue. */
    var lumenMaxDownloads: Int
        get() = prefs.getInt("lumen_max_downloads", 3).coerceIn(1, 6)
        set(value) { prefs.edit().putInt("lumen_max_downloads", value.coerceIn(1, 6)).apply() }

    /** Lumen: download speed limit for all downloads together, KB/s (0 = no limit). */
    var lumenSpeedLimitKBps: Int
        get() = prefs.getInt("lumen_speed_limit_kbps", 0).coerceAtLeast(0)
        set(value) { prefs.edit().putInt("lumen_speed_limit_kbps", value.coerceAtLeast(0)).apply() }

    var lumenDownloadParts: Int
        //Wisp: 16 connections (the most) by default for the fastest downloads; servers that refuse
        //that many fall back to fewer by themselves (DownloadTask)
        get() = prefs.getInt(LUMEN_DOWNLOAD_PARTS_KEY, if (Brand.phone) 16 else 8).coerceIn(1, 16)
        set(value) { prefs.edit().putInt(LUMEN_DOWNLOAD_PARTS_KEY, value.coerceIn(1, 16)).apply() }

    /**
     * 4K and unsupported videos in web pages: "external" (Just Player or another app),
     * "native" (Lumen's player) or "page" (leave them in the page). Empty = automatic.
     */
    var lumenBigVideoPlayer: String
        get() = prefs.getString("lumen_big_video_player", "") ?: ""
        set(value) { prefs.edit().putString("lumen_big_video_player", value).apply() }

    /** Sites that always open in another browser (e.g. Firefox) instead of Lumen. */
    var lumenExternalSites: Set<String>
        get() = HashSet(prefs.getStringSet("lumen_external_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("lumen_external_sites", HashSet(value)).apply() }

    fun isExternalSite(host: String?): Boolean {
        val key = siteKey(host)
        if (key.isEmpty()) return false
        return lumenExternalSites.any { key == it || key.endsWith(".$it") }
    }

    /**
     * Lumen: inverse telecine / deinterlacing in web pages, per site: "site|mode" with mode
     * auto (measure, then decide - like Just Player URL+), ivtc, mixed or deint.
     */
    var lumenIvtcSites: Set<String>
        get() = HashSet(prefs.getStringSet("lumen_ivtc_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("lumen_ivtc_sites", HashSet(value)).apply() }

    fun ivtcModeFor(host: String?): String {
        val key = siteKey(host)
        if (key.isEmpty()) return ""
        for (e in lumenIvtcSites) {
            val site = e.substringBefore('|')
            if (key == site || key.endsWith(".$site")) return e.substringAfter('|', "")
        }
        return ""
    }

    fun setIvtcMode(site: String, mode: String) {
        val rest = lumenIvtcSites.filterNot { it.substringBefore('|') == site }.toMutableSet()
        if (mode.isNotEmpty()) rest.add("$site|$mode")
        lumenIvtcSites = rest
    }

    /**
     * Lumen: sites whose videos are drawn by the browser instead of the TV's hardware video layer
     * (for videos that break into blocks only in the page, on one device).
     */
    var lumenVideoCompatSites: Set<String>
        get() = HashSet(prefs.getStringSet("lumen_video_compat_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("lumen_video_compat_sites", HashSet(value)).apply() }

    fun isVideoCompatSite(host: String?): Boolean {
        val key = siteKey(host)
        if (key.isEmpty()) return false
        return lumenVideoCompatSites.any { key == it || key.endsWith(".$it") }
    }

    /**
     * Lumen: "Lumen's player, downloaded by the page" per site: "site|on" / "site|off".
     * Not listed = automatic (on for TVs when [lumenTvPagePlayer] is on).
     */
    var lumenPagePlayerSites: Set<String>
        get() = HashSet(prefs.getStringSet("lumen_page_player_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("lumen_page_player_sites", HashSet(value)).apply() }

    fun pagePlayerModeFor(host: String?): String {
        val key = siteKey(host)
        if (key.isEmpty()) return ""
        for (e in lumenPagePlayerSites) {
            val site = e.substringBefore('|')
            if (key == site || key.endsWith(".$site")) return e.substringAfter('|', "")
        }
        return ""
    }

    fun setPagePlayerMode(site: String, mode: String) {
        val rest = lumenPagePlayerSites.filterNot { it.substringBefore('|') == site }.toMutableSet()
        if (mode.isNotEmpty()) rest.add("$site|$mode")
        lumenPagePlayerSites = rest
    }

    /** Lumen: on TVs, streamed web videos play in Lumen's player (downloaded by the page). */
    var lumenTvPagePlayer: Boolean
        get() = prefs.getBoolean("lumen_tv_page_player", true)
        set(value) { prefs.edit().putBoolean("lumen_tv_page_player", value).apply() }

    /** Lumen: sites whose videos always open in Just Player (or the chosen player app). */
    var lumenExternalVideoSites: Set<String>
        get() = HashSet(prefs.getStringSet("lumen_external_video_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("lumen_external_video_sites", HashSet(value)).apply() }

    fun isExternalVideoSite(host: String?): Boolean {
        val key = siteKey(host)
        if (key.isEmpty()) return false
        return lumenExternalVideoSites.any { key == it || key.endsWith(".$it") }
    }

    /** Sites that are told this is Firefox (some only show their ad/verification gate to Chrome). */
    var lumenFirefoxSites: Set<String>
        get() = HashSet(prefs.getStringSet("lumen_firefox_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("lumen_firefox_sites", HashSet(value)).apply() }

    // ---------------- Wisp privacy (phone build; off in Lumen) ----------------

    /** HTTPS first: plain http:// addresses are tried over https:// first, falling back when a site has no https. */
    var wispHttpsFirst: Boolean
        get() = prefs.getBoolean("wisp_https_first", Brand.phone)
        set(value) { prefs.edit().putBoolean("wisp_https_first", value).apply() }

    /** Block third-party cookies (trackers in embedded frames); sites in [wispThirdPartyCookieSites] are exempt. */
    var wispBlockThirdPartyCookies: Boolean
        get() = prefs.getBoolean("wisp_block_3p_cookies", Brand.phone)
        set(value) { prefs.edit().putBoolean("wisp_block_3p_cookies", value).apply() }

    /** Wisp: every fresh start opens in private mode (switching to normal lasts until the app is closed). */
    var wispAlwaysPrivate: Boolean
        get() = prefs.getBoolean("wisp_always_private", true)
        set(value) { prefs.edit().putBoolean("wisp_always_private", value).apply() }

    /** Wisp: text size of web pages in percent (WebView text zoom). */
    var wispTextZoom: Int
        get() = prefs.getInt("wisp_text_zoom", 100).coerceIn(50, 300)
        set(value) { prefs.edit().putInt("wisp_text_zoom", value).apply() }

    /** Wisp: pull down at the top of a page to reload it. */
    var wispPullToRefresh: Boolean
        get() = prefs.getBoolean("wisp_pull_to_refresh", true)
        set(value) { prefs.edit().putBoolean("wisp_pull_to_refresh", value).apply() }

    /** Sites (and their subdomains) where third-party cookies are allowed (logins, comments, payments). */
    var wispThirdPartyCookieSites: Set<String>
        get() = HashSet(prefs.getStringSet("wisp_3p_cookie_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("wisp_3p_cookie_sites", HashSet(value)).apply() }

    fun isThirdPartyCookieSite(host: String?): Boolean {
        val key = siteKey(host)
        if (key.isEmpty()) return false
        if (Brand.phone) { val base = baseSite(key); return wispThirdPartyCookieSites.any { baseSite(it) == base } }
        return wispThirdPartyCookieSites.any { key == it || key.endsWith(".$it") }
    }

    /** Wisp: WebRTC only over relays, so pages can't read the phone's IP addresses. */
    var wispWebrtcRelay: Boolean
        get() = prefs.getBoolean("wisp_webrtc_relay", true)
        set(value) { prefs.edit().putBoolean("wisp_webrtc_relay", value).apply() }

    /** Wisp: hide small animated ads (banner-sized frames from other sites, moving pictures linking elsewhere). */
    var wispHideSmallAds: Boolean
        get() = prefs.getBoolean("wisp_hide_small_ads", true)
        set(value) { prefs.edit().putBoolean("wisp_hide_small_ads", value).apply() }

    /** Wisp: Wisp asks for the fingerprint / screen lock when opened (and after a minute away). */
    var wispLockApp: Boolean
        get() = prefs.getBoolean("wisp_lock_app", false)
        set(value) { prefs.edit().putBoolean("wisp_lock_app", value).apply() }

    /** Wisp: saved passwords can only be viewed after the fingerprint / screen lock. */
    var wispLockPasswords: Boolean
        get() = prefs.getBoolean("wisp_lock_passwords", true)
        set(value) { prefs.edit().putBoolean("wisp_lock_passwords", value).apply() }

    /** Wisp: text size of the reader view (px). */
    var wispReaderFont: Int
        get() = prefs.getInt("wisp_reader_font", 18)
        set(value) { prefs.edit().putInt("wisp_reader_font", value).apply() }

    /** Wisp: sites that keep their logins (cookies, site data) when Wisp is quit. */
    var wispKeepSites: Set<String>
        get() = HashSet(prefs.getStringSet("wisp_keep_sites", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("wisp_keep_sites", HashSet(value)).apply() }

    fun isKeepSite(host: String?): Boolean {
        val base = baseSite(host)
        return base.isNotEmpty() && wispKeepSites.any { baseSite(it) == base }
    }

    /** Wisp: page elements hidden with a long press ("Hide element"), as "site<TAB>CSS selector". */
    var wispHiddenElements: Set<String>
        get() = HashSet(prefs.getStringSet("wisp_hidden_elements", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("wisp_hidden_elements", HashSet(value)).apply() }

    /** Selectors hidden on this site (and on the sites it is part of). */
    fun wispHiddenFor(host: String?): List<String> {
        val key = siteKey(host)
        if (key.isEmpty()) return emptyList()
        //the whole site: hidden on m.example.com also means hidden on www.example.com
        val base = baseSite(key)
        return wispHiddenElements.mapNotNull {
            val site = it.substringBefore('\t')
            if (baseSite(site) == base) it.substringAfter('\t') else null
        }.distinct()
    }

    fun addWispHidden(host: String?, selector: String) {
        val key = siteKey(host)
        if (key.isEmpty() || selector.isBlank() || selector.contains('{') || selector.contains('}')) return
        wispHiddenElements = wispHiddenElements + (baseSite(key) + "\t" + selector)
    }

    fun clearWispHidden(host: String?) {
        val key = siteKey(host)
        val base = baseSite(key)
        wispHiddenElements = wispHiddenElements.filterNot { baseSite(it.substringBefore('\t')) == base }.toSet()
    }

    fun isFirefoxSite(host: String?): Boolean {
        val key = siteKey(host)
        if (key.isEmpty()) return false
        return lumenFirefoxSites.any { key == it || key.endsWith(".$it") }
    }

    /** Sites that always load as desktop sites. */
    var desktopSites: Set<String>
        get() = HashSet(prefs.getStringSet(SOFA_DESKTOP_SITES_KEY, emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet(SOFA_DESKTOP_SITES_KEY, HashSet(value)).apply() }

    fun isDesktopSite(host: String?): Boolean {
        val key = siteKey(host)
        if (key.isEmpty()) return false
        if (Brand.phone) {
            //Wisp: the whole site (m.example.com, www.example.com and example.com alike): sites
            //send phones to "m." and desktops to "www.", so a switch made on one address was
            //missing on the other ("desktop, but the menu says off")
            val base = baseSite(key)
            return desktopSites.any { baseSite(it) == base }
        }
        return desktopSites.any { key == it || key.endsWith(".$it") }
    }

    /** WireGuard configuration, encrypted with a key kept in the Android keystore. */
    var sofaVpnConfigEncrypted: String
        get() = prefs.getString(SOFA_VPN_CONFIG_KEY, "") ?: ""
        set(value) { prefs.edit().putString(SOFA_VPN_CONFIG_KEY, value).apply() }

    var sofaVpnBrowserOnly: Boolean
        get() = prefs.getBoolean(SOFA_VPN_BROWSER_ONLY_KEY, true)
        set(value) { prefs.edit().putBoolean(SOFA_VPN_BROWSER_ONLY_KEY, value).apply() }

    var sofaVpnAutoConnect: Boolean
        get() = prefs.getBoolean(SOFA_VPN_AUTO_CONNECT_KEY, false)
        set(value) { prefs.edit().putBoolean(SOFA_VPN_AUTO_CONNECT_KEY, value).apply() }

    fun isWebEngineGecko(): Boolean {
        return webEngine == SupportedWebEngines[0]
    }

    fun isWebEngineNotSet(): Boolean {
        return !prefs.contains(WEB_ENGINE)
    }

    fun guessSearchEngineName(): String {
        val url = searchEngineURL.value
        val index = SearchEnginesURLs.indexOf(url)
        return if (index != -1 && index < SearchEnginesNames.size)
            SearchEnginesNames[index] else SearchEnginesNames[SearchEnginesNames.size - 1]
    }

    inner class ObservableStringPreference(default: String, private val prefsKey: String) : ObservableValue<String>(default) {
        override var value: String = prefs.getString(prefsKey, default)!!
            set(value) {
                prefs.edit().putString(prefsKey, value).apply()
                field = value
                notifyObservers()
            }
    }

    inner class ObservableOptStringPreference(default: String?, private val prefsKey: String) : ObservableValue<String?>(default) {
        override var value: String? = prefs.getString(prefsKey, default)
            set(value) {
                if (value == null) prefs.edit().remove(prefsKey).apply()
                else prefs.edit().putString(prefsKey, value).apply()
                field = value
                notifyObservers()
            }
    }
}
