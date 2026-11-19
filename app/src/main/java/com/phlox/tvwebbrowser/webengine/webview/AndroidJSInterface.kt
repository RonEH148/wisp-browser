package com.phlox.tvwebbrowser.webengine.webview

import android.net.http.SslError
import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadUtils
import org.json.JSONArray


class AndroidJSInterface(private val webEngine: WebViewWebEngine) {
    @JavascriptInterface
    fun currentUrl(): String {
        if (!webEngine.tab.url.startsWith(WebViewEx.INTERNAL_SCHEME)) return ""
        return webEngine.tab.url
    }

    @JavascriptInterface
    fun reloadWithSslTrust() {
        val callback = webEngine.callback ?: return
        if ((webEngine.getView() as WebViewEx).currentOriginalUrl?.scheme != "file") return
        callback.getActivity().runOnUiThread {
            val webview = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            webview.trustSsl = true
            webEngine.tab.url.apply { webEngine.loadUrl(this) }
        }
    }

    @JavascriptInterface
    fun getStringByName(name: String): String {
        val ctx = TVBro.instance
        //val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        //return ctx.getString(resId)
        when (name) {
            "connection_isnt_secure" -> return ctx.getString(R.string.connection_isnt_secure)
            "hostname" -> return ctx.getString(R.string.hostname)
            "err_desk" -> return ctx.getString(R.string.err_desk)
            "details" -> return ctx.getString(R.string.details)
            "back_to_safety" -> return ctx.getString(R.string.back_to_safety)
            "go_im_aware" -> return ctx.getString(R.string.go_im_aware)
            else -> return ""
        }
    }

    @JavascriptInterface
    fun startVoiceSearch() {
        //Lumen: voice search removed (no microphone access)
    }

    @JavascriptInterface
    fun setSearchEngine(engine: String, customSearchEngineURL: String) {
        if (!isHomePage()) return
        AppContext.provideConfig().searchEngineURL.value = customSearchEngineURL
    }

    @JavascriptInterface
    fun onEditBookmark(index: Int) {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.onEditHomePageBookmarkSelected(index) }
    }

    @JavascriptInterface
    fun onHomePageLoaded() {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread {
            val cfg = AppContext.provideConfig()
            val jsArr = JSONArray()
            for (item in callback.getHomePageLinks()) {
                jsArr.put(item.toJsonObj())
            }
            var links = jsArr.toString()
            links = links.replace("'", "\\'")
            webEngine.evaluateJavascript("renderLinks('${cfg.homePageLinksMode.name}', $links)")
            webEngine.evaluateJavascript(
                "applySearchEngine(\"${cfg.guessSearchEngineName()}\", \"${cfg.searchEngineURL.value}\")")
        }
    }

    @JavascriptInterface
    fun lastSSLError(getDetails: Boolean): String {
        val lastSSLError = (webEngine.getView() as? WebViewEx)?.lastSSLError ?: return "unknown"
        return if (getDetails) {
            lastSSLError.toString()
        } else {
            when (lastSSLError.primaryError) {
                SslError.SSL_EXPIRED -> TVBro.instance.getString(R.string.ssl_expired)
                SslError.SSL_IDMISMATCH -> TVBro.instance.getString(R.string.ssl_idmismatch)
                SslError.SSL_DATE_INVALID -> TVBro.instance.getString(R.string.ssl_date_invalid)
                SslError.SSL_INVALID -> TVBro.instance.getString(R.string.ssl_invalid)
                else -> "unknown"
            }
        }
    }

    @JavascriptInterface
    fun takeBlobDownloadData(base64BlobData: String, fileName: String?, url: String, mimetype: String) {
        val callback = webEngine.callback ?: return
        val finalFileName = fileName ?: DownloadUtils.guessFileName(url, null, mimetype)
        callback.onDownloadRequested(url, "",
                finalFileName, "TV Bro",
            mimetype, Download.OperationAfterDownload.NOP, base64BlobData)
    }

    @JavascriptInterface
    fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.markBookmarkRecommendationAsUseful(bookmarkOrder) }
    }

    //Lumen: read by assets/sofa_enhancements.js in every frame. Exposes only on/off switches.
    @JavascriptInterface
    fun sofaSettings(frameHost: String?): String {
        //per-site settings follow the top-level page, like the ad blocker does
        val pageHost = try { android.net.Uri.parse(webEngine.tab.url).host } catch (e: Exception) { null }
        return WebViewEx.sofaSettingsJson(AppContext.provideConfig(), pageHost ?: frameHost)
    }

    /** Lumen: adblock-rust element hiding for this frame (JSON), "" when not available. */
    @JavascriptInterface
    fun lumenCosmetic(frameUrl: String?): String {
        if (frameUrl == null || !(frameUrl.startsWith("http://") || frameUrl.startsWith("https://"))) return ""
        return try { com.phlox.tvwebbrowser.sofa.LumenAdblock.cosmetic(frameUrl) ?: "" } catch (e: Exception) { "" }
    }

    /** Lumen: CSS for generic ad rules matching the page's classes and ids (space separated). */
    @JavascriptInterface
    fun lumenClassIds(frameUrl: String?, classes: String?, ids: String?): String {
        if (frameUrl == null || !(frameUrl.startsWith("http://") || frameUrl.startsWith("https://"))) return ""
        return try {
            com.phlox.tvwebbrowser.sofa.LumenAdblock.classIds(frameUrl, classes ?: "", ids ?: "") ?: ""
        } catch (e: Exception) { "" }
    }

    /** Desktop-site check for the frame's own host (asked at document start, before the tab URL updates). */
    @JavascriptInterface
    fun sofaIsDesktopSite(host: String?): Boolean = AppContext.provideConfig().isDesktopSite(host)

    /** Video bar buttons: mode is "native" (4K/HDR player) or "external" (app chooser). */
    @JavascriptInterface
    fun sofaOpenVideo(mode: String, src: String?, pageUrl: String?, title: String?, positionSec: Double) {
        val callback = webEngine.callback ?: return
        val activity = callback.getActivity()
        activity.runOnUiThread {
            val webView = webEngine.getView() as? WebViewEx
            //the page's video had deband on: keep it on in Lumen's player
            val m = if (mode == com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MODE_NATIVE && webView?.pageDeband == true)
                com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MODE_NATIVE_DEBAND else mode
            com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.open(
                activity, m, src, pageUrl ?: webEngine.tab.url, title ?: webEngine.tab.title,
                positionSec, webView?.settings?.userAgentString, webView?.mediaLog
            )
        }
    }

    /**
     * A 4K video (or one the page's player can't decode: HEVC, MKV, DTS, ...) started loading.
     * Returns true when it is handed to a real player (the page then pauses its copy).
     */
    @JavascriptInterface
    fun sofaBigVideo(reason: String?, src: String?, pageUrl: String?, title: String?, positionSec: Double): Boolean {
        val callback = webEngine.callback ?: return false
        val activity = callback.getActivity()
        val handoff = com.phlox.tvwebbrowser.sofa.SofaVideoHandoff
        val host = try { android.net.Uri.parse(webEngine.tab.url).host } catch (e: Exception) { null }
        var mode = handoff.videoMode(activity, reason, host)
        if (mode == handoff.MODE_NATIVE_PAGE && !handoff.hasManifest((webEngine.getView() as? WebViewEx)?.mediaLog)) {
            //not a streamed (HLS/DASH) video, e.g. YouTube: it stays in the page
            mode = handoff.videoMode(activity, reason, host, allowPagePlayer = false)
        }
        if (mode == handoff.MODE_PAGE) return false
        if (System.currentTimeMillis() - handoff.lastLaunchAt < 5000) return false
        activity.runOnUiThread {
            val webView = webEngine.getView() as? WebViewEx
            val what = when (reason) {
                "4k" -> "4K video: "
                "unsupported" -> "This video can't play inside the page: "
                else -> ""
            }
            val where = when (mode) {
                handoff.MODE_EXTERNAL -> if (handoff.justPlayerPackage(activity) != null) "Just Player" else "your player app"
                handoff.MODE_ASK -> "the player you choose"
                handoff.MODE_NATIVE_PAGE -> "${com.phlox.tvwebbrowser.Brand.NAME}'s player (the page keeps downloading it)"
                else -> "${com.phlox.tvwebbrowser.Brand.NAME}'s player"
            }
            android.widget.Toast.makeText(activity, "${what}opening it in $where (Settings → Videos from web pages)",
                android.widget.Toast.LENGTH_LONG).show()
            handoff.openBig(activity, mode, src, pageUrl ?: webEngine.tab.url, title ?: webEngine.tab.title,
                positionSec, webView?.settings?.userAgentString, webView?.mediaLog)
        }
        return true
    }

    /** A playlist exactly as the page's player received it (served to external players instead of asking again). */
    @JavascriptInterface
    fun sofaManifestCopy(url: String?, text: String?) {
        if (url.isNullOrEmpty() || text.isNullOrEmpty() || !url.startsWith("http")) return
        com.phlox.tvwebbrowser.sofa.LumenStreamProxy.rememberCopy(url, text)
    }

    /** A full-size inner box of a web app scrolled (CSS px); see "auto-hiding top bar" in the page script. */
    @JavascriptInterface
    fun sofaPageScroll(y: Int, dy: Int) {
        val callback = webEngine.callback ?: return
        val activity = callback.getActivity()
        activity.runOnUiThread {
            val webView = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            val density = activity.resources.displayMetrics.density
            webView.boxScrollY = (y * density).toInt()
            webEngine.callback?.onPageScrolled(webView.boxScrollY + webView.scrollY, (dy * density).toInt())
        }
    }

    /** Wisp: text size chosen in the reader view. */
    @JavascriptInterface
    fun wispReaderFont(size: Int) {
        if (!com.phlox.tvwebbrowser.Brand.phone) return
        AppContext.provideConfig().wispReaderFont = size.coerceIn(13, 30)
    }

    /** Wisp: CSS selectors of the elements hidden on this site (JSON array). */
    @JavascriptInterface
    fun wispHidden(host: String?): String {
        if (!com.phlox.tvwebbrowser.Brand.phone) return "[]"
        return org.json.JSONArray(AppContext.provideConfig().wispHiddenFor(host)).toString()
    }

    /** Wisp: a long press on an image, a link or a video/frame (found by the page script). */
    @JavascriptInterface
    fun wispLongPress(json: String?) {
        if (!com.phlox.tvwebbrowser.Brand.phone || json.isNullOrEmpty()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { webEngine.callback?.onWispLongPress(json) }
    }

    /** Wisp: "Hide element" was chosen: remember it for the site. */
    @JavascriptInterface
    fun wispRememberHidden(host: String?, selector: String?) {
        if (!com.phlox.tvwebbrowser.Brand.phone || selector.isNullOrBlank()) return
        AppContext.provideConfig().addWispHidden(host, selector)
    }

    /** The page is a Cloudflare verification page: reload it with nothing modified. */
    @JavascriptInterface
    fun sofaChallenge(url: String?) {
        if (url == null || !url.startsWith("http")) return
        val activity = webEngine.callback?.getActivity() ?: return
        activity.runOnUiThread {
            (webEngine.getView() as? WebViewEx)?.onVerificationPage(url)
        }
    }

    /** In-page deband couldn't run for a video: tell the user once. */
    @JavascriptInterface
    fun sofaDebandStatus(code: String?) {
        val callback = webEngine.callback ?: return
        val activity = callback.getActivity()
        activity.runOnUiThread {
            val webView = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            val c = code ?: return@runOnUiThread
            if (c.startsWith("auto:")) {
                //switched on by itself: lines found in the video, or a site set to "always"
                if (webView.pageDeband) return@runOnUiThread
                webView.pageDeband = true
                webView.debandConfirmed = false
                if (c == "auto:lines") {
                    android.widget.Toast.makeText(activity, R.string.lumen_deband_auto_lines, android.widget.Toast.LENGTH_LONG).show()
                }
                callback.onPageStateChanged()
                return@runOnUiThread
            }
            if (c == "tvpage") {
                //turned on by a site setting from an older build: nothing runs on TVs, say nothing
                webView.pageDeband = false
                callback.onPageStateChanged()
                return@runOnUiThread
            }
            if (!webView.pageDeband) return@runOnUiThread
            if (c.startsWith("slow:")) {
                android.widget.Toast.makeText(activity,
                    if (c == "slow:light") "Deband switched to Light: this device couldn't keep up"
                    else "Deband stopped for this video: this device couldn't keep up. " +
                            "Use \"Play in ${com.phlox.tvwebbrowser.Brand.NAME} player with deband\" (Light) instead",
                    android.widget.Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            if (c.startsWith("scan:")) {
                webView.debandScans.add(c.substring(5))
                return@runOnUiThread
            }
            if (c.startsWith("ok:")) {
                //confirmation that the filter really runs, once per page
                if (webView.debandConfirmed) return@runOnUiThread
                webView.debandConfirmed = true
                android.widget.Toast.makeText(activity,
                    activity.getString(R.string.lumen_deband_running, c.substring(3).replace('x', '×')),
                    android.widget.Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val now = System.currentTimeMillis()
            if (now - webView.lastDebandReport < 5000) return@runOnUiThread
            webView.lastDebandReport = now
            val text = when {
                c == "nogl" -> activity.getString(R.string.lumen_deband_no_gl)
                c == "fsvideo" -> activity.getString(R.string.lumen_deband_reenter_fullscreen)
                c.startsWith("error:") -> activity.getString(R.string.lumen_deband_error_s, c.substring(6).take(160))
                else -> activity.getString(R.string.lumen_deband_blocked)
            }
            android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Called by the page script after a login form is submitted in the top frame. */
    @JavascriptInterface
    fun sofaOfferSaveLogin(username: String?, password: String?) {
        if (password.isNullOrEmpty()) return
        val callback = webEngine.callback ?: return
        val activity = callback.getActivity()
        activity.runOnUiThread {
            val webView = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            com.phlox.tvwebbrowser.sofa.SofaPasswords.offerToSave(activity, webView, username ?: "", password)
        }
    }

    /** The user picked a saved account in the page's "Sign in as…" box (top frame only). */
    @JavascriptInterface
    fun sofaFillLogin(index: Int) {
        val activity = webEngine.callback?.getActivity() ?: return
        activity.runOnUiThread {
            val webView = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            com.phlox.tvwebbrowser.sofa.SofaPasswords.fillChosen(webView, index)
        }
    }

    /** The user clicked a link (its address), so going there is not an ad redirect. */
    @JavascriptInterface
    fun sofaLinkClicked(href: String?) {
        val host = try { android.net.Uri.parse(href ?: return).host } catch (e: Exception) { null } ?: return
        val wv = webEngine.getView() as? WebViewEx ?: return
        wv.lastLinkHost = host
        wv.lastLinkTime = System.currentTimeMillis()
    }

    /** "Open" in the "Redirect blocked" bar. */
    @JavascriptInterface
    fun sofaAllowNav(url: String?) {
        val wv = webEngine.getView() as? WebViewEx ?: return
        wv.allowedNav = url
    }

    /** A video started playing in this page or one of its frames. */
    @JavascriptInterface
    fun sofaVideoStarted() {
        val activity = webEngine.callback?.getActivity() ?: return
        activity.runOnUiThread { webEngine.callback?.onVideoStarted() }
    }

    /** A new account's login (sign-up form or the suggested strong password): saved right away. */
    @JavascriptInterface
    fun sofaAutoSaveLogin(username: String?, password: String?) {
        if (password.isNullOrEmpty()) return
        val activity = webEngine.callback?.getActivity() ?: return
        activity.runOnUiThread {
            val webView = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            com.phlox.tvwebbrowser.sofa.SofaPasswords.autoSave(activity, webView, username ?: "", password)
        }
    }

    @JavascriptInterface
    fun sofaSetDeinterlace(enabled: Boolean) {
        AppContext.provideConfig().sofaDeinterlace = enabled
    }

    private fun isHomePage(): Boolean {
        return webEngine.tab.url == Config.HOME_PAGE_URL || webEngine.tab.url == Config.HOME_URL_ALIAS
    }
}
