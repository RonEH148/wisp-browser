package com.phlox.tvwebbrowser.webengine.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaDrm
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Brand
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.Utils
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID


/**
 * Copyright (c) 2016 Fedir Tsapana.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
open class WebViewEx(context: Context, val callback: Callback, val jsInterface: AndroidJSInterface) : WebView(context) {
    companion object {
        /** Domain endings that ad networks register by the thousand (rotated every few days). */
        private val STREAM_EXT = listOf(".m3u8", ".m3u", ".mpd", ".ts", ".m4s", ".m2ts", ".aac", ".vtt", ".webvtt", ".srt", ".ass", ".key")
        private val JUNK_TLD = Regex("cyou|sbs|cfd|icu|buzz|monster|quest|rest|bond|autos|boats|beauty|hair|skin|makeup|homes|yachts|motorcycles|mom|pics|click|lol|top|xyz|cam|win|bid|loan|work|gq|ml|cf|ga|tk")
        private const val PULL_TO_SHOW_DP = 40f
        private val VIDEO_FILE_EXTENSIONS = listOf(".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".wmv", ".flv", ".3gp", ".mpg", ".mpeg", ".m2ts", ".mts")
        val TAG = WebViewEx::class.java.simpleName
        const val WEB_VIEW_TAG = "TV Bro WebView"
        const val INTERNAL_SCHEME = "internal://"
        const val INTERNAL_SCHEME_WARNING_DOMAIN = "warning"
        const val INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT = "certificate"
        val WIDEVINE_UUID = UUID(-0x121074568629b532L,-0x5c37d8232ae2de13L)

        private var sofaScriptCache: String? = null

        /** Wisp: sites without a working https:// version (this session only). */
        private val httpOnlyHosts: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

        /** Settings consumed by assets/sofa_enhancements.js */
        fun sofaSettingsJson(config: Config, host: String? = null): String = JSONObject().apply {
            put("cosmetic", config.adBlockEnabled && config.sofaCosmeticFilter && !config.isAdBlockDisabledForHost(host))
            put("popups", config.sofaPopupBlocker)
            put("overlay", config.sofaPlayerOverlay)
            put("maxQuality", config.sofaAutoMaxQuality)
            put("stopMini", config.lumenStopMiniPlayer)
            put("videoCompat", config.isVideoCompatSite(host) && !Brand.phone)
            //Wisp: none of the TV's video picture fixes
            put("ivtc", if (Brand.phone) "" else config.ivtcModeFor(host))
            put("deinterlace", config.sofaDeinterlace && !Brand.phone)
            put("dark", config.sofaForceDark)
            put("passwords", config.sofaSavePasswords && (!config.incognitoMode || Brand.phone))
            put("desktop", config.isDesktopSite(host))
            put("deband", config.isDebandSite(host) && !Brand.phone)
            put("autoLines", config.lumenAutoDetectLines && !Brand.phone)
            put("verifySafe", com.phlox.tvwebbrowser.sofa.LumenVerify.isVerifySafe(host))
            //TV chips: the in-page video filter starts in its light mode
            put("tv", com.phlox.tvwebbrowser.utils.Utils.isTV(com.phlox.tvwebbrowser.AppContext.get()))
            //Wisp: lighter page work on phones (fewer generic ad-rule look-ups, top frame only)
            put("phone", Brand.phone)
            //Wisp: WebRTC may not reveal the phone's IP addresses (Settings → Privacy)
            put("webrtcRelay", Brand.phone && config.wispWebrtcRelay)
            //Wisp: hide small animated ads that the filter lists miss (Settings → Ad blocker)
            put("smallAds", Brand.phone && config.adBlockEnabled && config.wispHideSmallAds && !config.isAdBlockDisabledForHost(host))
        }.toString()

        /**
         * Android WebView marks itself with "; wv" and "Version/4.0" in its default user agent.
         * Many anti-bot checks (Cloudflare "Verify you are human", Google sign-in) treat that as
         * an embedded/automated browser and fail. Removing the markers gives the same user agent
         * Chrome on the same device sends.
         */
        fun cleanUserAgent(ua: String): String =
            ua.replace("; wv)", ")").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
    }

    private var sofaDocumentStartInstalled = false

    /** Stream files requested by the current page, for the native/external player hand-off. */
    val mediaLog = com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.MediaLog()

    /** Anime deband switched on for the page currently shown (reset when a new page loads). */
    var pageDeband = false
    var lastDebandReport = 0L
    var debandConfirmed = false
    /** What each frame of the page reported after deband was switched on (for the diagnosis). */
    val debandScans: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** Scroll position of a full-size inner box, for web apps that scroll one instead of the page. */
    var boxScrollY = 0
    private var touchDownY = 0f
    private var touchStartedAtTop = false
    private var pullReported = false

    private var virtualCursorMode: Boolean = true
    private var genericInjects: String? = null
    private var webChromeClient_: WebChromeClient
    private var fullscreenViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pickFileCallback: ValueCallback<Array<Uri>>? = null
    private var permRequestDialog: AlertDialog? = null
    private var webPermissionsRequest: PermissionRequest? = null
    private var requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions: ArrayList<String>? = null
    private var geoPermissionOrigin: String? = null
    private var geoPermissionsCallback: GeolocationPermissions.Callback? = null
    var lastSSLError: SslError? = null
    var trustSsl: Boolean = false
    var currentOriginalUrl: Uri? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val config = AppContext.provideConfig()

    interface Callback {
        fun getActivity(): Activity?
        fun onOpenInNewTabRequested(url: String)
        fun onDownloadRequested(url: String)
        fun onThumbnailError()
        fun onShowCustomView(view: View)
        fun onHideCustomView()
        fun onProgressChanged(newProgress: Int)
        fun onReceivedTitle(title: String)
        fun onShowFileChooser(intent: Intent): Boolean
        fun onReceivedIcon(icon: Bitmap)
        fun requestPermissions(array: Array<String>, geo: Boolean)
        fun shouldOverrideUrlLoading(url: String): Boolean
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onPageCertificateError(url: String?)
        fun isAdBlockingEnabled(): Boolean
        fun isDialogsBlockingEnabled(): Boolean
        fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean
        fun onBlockedAd(url: Uri)
        fun onBlockedDialog(newTab: Boolean)
        fun onCreateWindow(dialog: Boolean, userGesture: Boolean): WebViewEx?
        fun closeWindow(window: WebView)
        fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String?, contentLength: Long)
        fun onScaleChanged(oldScale: Float, newScale: Float)
        fun onCopyTextToClipboardRequested(url: String)
        fun onShareUrlRequested(url: String)
        fun onOpenInExternalAppRequested(url: String)
        fun onVisited(url: String)
        fun onContextMenu(baseUrl: String?, href: String?, x: Int, y: Int)
        /** The page scrolled to [y] (px) by [dy]; y = 0, dy < 0 also means "pulled down at the top". */
        /** Wisp: pulled down past the top of the page by [distance] px; [released]: finger lifted. */
        fun onPullToRefresh(distance: Float, released: Boolean) {}
        fun onPageScrolled(y: Int, dy: Int) {}
        /** The web page process crashed or was killed; this WebView can't be used any more. */
        fun onRenderProcessGone(url: String?) {}
        /** The page's address changed (also without a new page load). */
        fun onUrlChanged(url: String) {}
    }

    init {
        //Lumen: no stretch/glow at the page edges: with the remote's pointer it only looked broken
        overScrollMode = View.OVER_SCROLL_NEVER
        with(settings) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //Wisp: no Google Safe Browsing (it sends the addresses you visit, hashed, to Google)
                safeBrowsingEnabled = !com.phlox.tvwebbrowser.Brand.phone && callback.isAdBlockingEnabled()
            }
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            domStorageEnabled = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = !config.allowAutoplayMedia
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setNeedInitialFocus(false)

            domStorageEnabled = true
            //Wisp has no remote debugging (an old setting from before is ignored)
            if (config.webEngineDebug && !Brand.phone) {
                setWebContentsDebuggingEnabled(true)
            }

            val allowDarkening = config.webviewUseAlgorithmicDarkeningWithDarkUiMode
            val uiNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    if (uiNightMode == Configuration.UI_MODE_NIGHT_YES && allowDarkening) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                    } else {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                    }
                }
            } else {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    if (uiNightMode == Configuration.UI_MODE_NIGHT_YES && allowDarkening) {
                        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
                    } else {
                        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_OFF)
                    }
                }
            }
        }

        //Lumen: a long press with a finger selects text (copy/paste works on tablets and phones);
        //only the TV remote's cursor long press is kept for the browser's own link menu
        setOnLongClickListener { _ ->
            !lastTouchFromFinger
        }

        if (config.sofaCloudflareCompat) {
            //Human-verification widgets (Cloudflare Turnstile, hCaptcha, reCAPTCHA) live in
            //third-party iframes and need cookies there; WebView blocks them by default.
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            //Android adds "X-Requested-With: <app name>" to every request of a WebView app. Bot checks
            //(Cloudflare and others) read it as "embedded browser" and fail; Chrome doesn't send it.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                try {
                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                } catch (e: Exception) {
                    Log.e(TAG, "X-Requested-With", e)
                }
            }
        }
        //Wisp: third-party cookies only on sites that need them (decided again for every page)
        applyThirdPartyCookiePolicy(null)
        //Wisp: text size chosen in Settings → General
        if (com.phlox.tvwebbrowser.Brand.phone) settings.textZoom = config.wispTextZoom
        applyDefaultUserAgentIfNeeded()
        installSofaEnhancements()
        //Lumen: lets Lumen's player have the page download a video (LumenPageFetch)
        com.phlox.tvwebbrowser.sofa.LumenPageFetch.install(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //keep the page renderer at high priority so video decoding isn't throttled
            setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        }

        webChromeClient_ = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                //Wisp reader page: its text size, when the page has no TVBro bridge (sites opened in clean mode)
                if (com.phlox.tvwebbrowser.Brand.phone && message == "wisp-reader-font") {
                    defaultValue.toIntOrNull()?.let { config.wispReaderFont = it.coerceIn(13, 30) }
                    result.confirm("")
                    return true
                }
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsPrompt(view, url, message, defaultValue, result)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                this@WebViewEx.callback.onShowCustomView(view)
                fullscreenViewCallback = callback
            }

            override fun onHideCustomView() {
                callback.onHideCustomView()
                fullscreenViewCallback?.onCustomViewHidden()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                callback.onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                callback.onReceivedTitle(title)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.size == 1 &&
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID == request.resources[0]) {
                    //fast path for grant/deny RESOURCE_PROTECTED_MEDIA_ID
                    if (MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
                        val widevineKeyDrm = MediaDrm(WIDEVINE_UUID)
                        val version = widevineKeyDrm.getPropertyString(MediaDrm.PROPERTY_VERSION)
                        Log.i(TAG, "DRM widevine version = " + version)
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                    return
                }

                //Lumen: websites never get the microphone
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    val others = request.resources.filter { it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (others.isEmpty()) {
                        request.deny()
                        return
                    }
                }
                val activity = callback.getActivity() ?: return
                webPermissionsRequest = request
                permRequestDialog = AlertDialog.Builder(activity)
                        .setMessage(activity.getString(R.string.web_perm_request_confirmation, TextUtils.join("\n", request.resources)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny) { _, _ ->
                            webPermissionsRequest?.deny()
                            permRequestDialog = null
                            webPermissionsRequest = null
                        }
                        .setPositiveButton(R.string.allow) { dialog, which ->
                            val webPermissionsRequest = this@WebViewEx.webPermissionsRequest
                            this@WebViewEx.webPermissionsRequest = null
                            if (webPermissionsRequest == null) {
                                return@setPositiveButton
                            }

                            val neededPermissions = ArrayList<String>()
                            val resourcesThatDoNotNeedToGrantPerms = ArrayList<String>()
                            for (resource in webPermissionsRequest.resources) {
                                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE == resource) {
                                    //microphone is never granted
                                } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == resource) {
                                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                        neededPermissions.add(Manifest.permission.CAMERA)
                                    } else {
                                        resourcesThatDoNotNeedToGrantPerms.add(resource)
                                    }
                                } else {
                                    resourcesThatDoNotNeedToGrantPerms.add(resource)
                                }
                            }

                            if (neededPermissions.isNotEmpty()) {
                                requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions = resourcesThatDoNotNeedToGrantPerms
                                callback.requestPermissions(neededPermissions.toTypedArray(), false)
                            } else {
                                webPermissionsRequest.grant(resourcesThatDoNotNeedToGrantPerms.toTypedArray())
                            }

                            permRequestDialog = null
                        }
                        .create()
                permRequestDialog!!.show()
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                permRequestDialog?.apply {
                    dismiss()
                    permRequestDialog = null
                }
                webPermissionsRequest = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val activity = this@WebViewEx.callback.getActivity() ?: return
                geoPermissionOrigin = origin
                geoPermissionsCallback = callback
                permRequestDialog = AlertDialog.Builder(activity)
                        .setMessage(activity.getString(R.string.web_perm_request_confirmation, activity.getString(R.string.location)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny) { dialog, which ->
                            geoPermissionsCallback!!.invoke(geoPermissionOrigin, false, false)
                            permRequestDialog = null
                            geoPermissionsCallback = null
                        }
                        .setPositiveButton(R.string.allow) { dialog, which ->
                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                this@WebViewEx.callback.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), true)
                            } else {
                                geoPermissionsCallback!!.invoke(geoPermissionOrigin, true, true)
                                geoPermissionsCallback = null
                            }
                            permRequestDialog = null
                        }
                        .create()
                permRequestDialog!!.show()
            }

            override fun onGeolocationPermissionsHidePrompt() {
                if (permRequestDialog != null) {
                    permRequestDialog!!.dismiss()
                    permRequestDialog = null
                }
                geoPermissionsCallback = null
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg: String = "(" + consoleMessage.sourceId() + "[" + consoleMessage.lineNumber() + "]): " + consoleMessage.message()
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> {
                        Log.e(WEB_VIEW_TAG, msg)
                        //errors from Wisp's own scripts (they run without a source file) or the custom script
                        if (Brand.phone && (consoleMessage.sourceId().isNullOrEmpty() ||
                                    consoleMessage.message().contains("custom script"))) {
                            com.phlox.tvwebbrowser.sofa.WispLog.mismatch("script error on " +
                                    com.phlox.tvwebbrowser.sofa.WispLog.site(url), consoleMessage.message().take(200))
                        }
                    }
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(WEB_VIEW_TAG, msg)
                    else -> Log.i(WEB_VIEW_TAG, msg)
                }
                return true
            }


            override fun onShowFileChooser(mWebView: WebView, callback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
                pickFileCallback = callback

                val result = this@WebViewEx.callback.onShowFileChooser(fileChooserParams.createIntent())
                if (!result) {
                    pickFileCallback = null
                }
                return result
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap) {
                Log.d(TAG, "onReceivedIcon: ${icon.width}x${icon.height}")
                callback.onReceivedIcon(icon)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val webView = callback.onCreateWindow(isDialog, isUserGesture) ?: return false
                (resultMsg.obj as WebView.WebViewTransport).webView = webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                callback.closeWindow(window)
            }
        }

        webViewClient = object : WebViewClient() {
            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                //without this Android closes the whole app when a page's process dies
                //(e.g. it ran out of memory playing a very high-bitrate video)
                Log.e(TAG, "Web page process gone, crashed: ${detail.didCrash()}")
                val failed = currentOriginalUrl?.toString()
                com.phlox.tvwebbrowser.sofa.WispLog.error("page", "page process " +
                        (if (detail.didCrash()) "crashed" else "was closed by Android (memory)") +
                        " on ${com.phlox.tvwebbrowser.sofa.WispLog.site(failed)}")
                uiHandler.post { callback.onRenderProcessGone(failed) }
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                Log.d(TAG, "shouldOverrideUrlLoading url: ${request.url}")
                if (request.isForMainFrame) {
                    //Wisp: HTTPS first - a plain http:// link is opened over https:// when the site has it
                    if (httpsFirstUpgrade(request.url)) return true
                    applyThirdPartyCookiePolicy(request.url.host)
                    //Lumen: sites set to open in Firefox (Site fixes)
                    if (openExternallyIfWanted(request.url.toString())) return true
                    //Lumen: an ad script sending the whole tab to an ad site
                    if (blockAdRedirect(request)) return true
                    //Lumen: stream links (.m3u8 / .mpd) play in the built-in hardware player
                    if (openStreamInPlayer(request.url.toString())) return true
                    //a video file address entered on the home page plays in the built-in player too
                    if (isOnInternalHomePage() && isDirectVideoFile(request.url.toString()) &&
                        openStreamInPlayer(request.url.toString(), force = true)) return true
                    //Wisp: switching the user agent here restarts the load: open the address anew instead
                    if (request.method.equals("GET", ignoreCase = true) && Brand.phone) {
                        if (callback.shouldOverrideUrlLoading(request.url.toString())) return true
                        if (reloadWithUserAgentIfNeeded(request.url.toString())) return true
                        applyUserAgentFor(request.url.toString())
                        return false
                    }
                    applyUserAgentFor(request.url.toString())
                }
                return callback.shouldOverrideUrlLoading(request.url.toString())
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                Log.d(TAG, "shouldInterceptRequest url: ${request.url}")
                val currentPageUrl = currentOriginalUrl
                //a human-verification widget (Cloudflare Turnstile, hCaptcha) on a sign-up or login
                //form: it fails when the user agent doesn't match the engine, so switch once
                //Wisp: also Cloudflare's own challenge script on the site ("Just a moment…" pages) and
                //reCAPTCHA: they load before the check's frame, so its cookies are allowed in time
                if (com.phlox.tvwebbrowser.sofa.LumenVerify.isVerificationResource(request.url.host) ||
                    request.url.path?.startsWith("/cdn-cgi/challenge-platform/") == true ||
                    request.url.path?.startsWith("/recaptcha/") == true) {
                    onVerificationWidget(currentPageUrl)
                }
                if (!request.isForMainFrame) {
                    com.phlox.tvwebbrowser.sofa.LumenStreamProxy.rememberHostHeaders(request.url.toString(), request.requestHeaders)
                }
                mediaLog.onRequest(request.url.toString(), request.requestHeaders["Referer"] ?: currentPageUrl?.toString(),
                    request.requestHeaders)

                val onHomePage = currentPageUrl != null && currentPageUrl.toString().startsWith(Config.HOME_PAGE_URL,
                        ignoreCase = true)
                //the start page always comes from the app itself, also when it is reached with Back
                //(then the page address isn't updated yet): never ask TV Bro's old server for it
                if (onHomePage || request.url.toString().startsWith(Config.HOME_PAGE_URL)) {
                    if (onHomePage) HomePageHelper.shouldInterceptRequest(view, request)?.let {
                        return it
                    }
                    if (request.url.toString().startsWith(Config.HOME_PAGE_URL)) {
                        var relativePath = request.url.toString().substring(Config.HOME_PAGE_URL.length)
                        if (relativePath.isEmpty() || relativePath == "/") {
                            relativePath = "index.html"
                        }
                        val assetsPath = "pages/home/$relativePath"
                        val response = Utils.getWebResourceResponseFromAssets(view.context, assetsPath)
                        if (response != null) {
                            Log.d(TAG, "shouldInterceptRequest url: ${request.url} -> $assetsPath")
                            return response
                        } else {
                            Log.w(TAG, "shouldInterceptRequest url: ${request.url} -> not found in assets")
                        }
                        return response ?: WebResourceResponse("text/plain", "utf-8", "".byteInputStream()).also {
                            it.setStatusCodeAndReasonPhrase(404, "Not Found")
                        }
                    }
                }

                if (!callback.isAdBlockingEnabled() || cleanMode ||
                    com.phlox.tvwebbrowser.sofa.LumenVerify.isVerificationResource(request.url.host) ||
                    request.url.path?.startsWith("/recaptcha/") == true) {
                    return super.shouldInterceptRequest(view, request)
                }
                //Lumen: a page or link you open yourself is never blocked, even if its domain is
                //on a filter list (file hosts often are) - only the ads inside pages are
                if (request.isForMainFrame) {
                    return super.shouldInterceptRequest(view, request)
                }

                //Lumen: pieces of a video stream (playlists, segments, subtitles) are never blocked:
                //a filter list matching one of them breaks the picture into blocks or stops the video
                if (isStreamPiece(request.url)) {
                    return super.shouldInterceptRequest(view, request)
                }
                val ad = currentPageUrl?.let { callback.isAd(request, it)} ?: false
                return if (ad) {
                    Log.d(TAG, "Blocked ads request: ${request.url}")
                    uiHandler.post { callback.onBlockedAd(request.url) }
                    val response = WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                    response.setStatusCodeAndReasonPhrase(403, "Blocked")
                    response
                } else super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted url: $url")
                if (Brand.phone && url.startsWith("http")) com.phlox.tvwebbrowser.sofa.WispLog.pageStarted(url)
                currentOriginalUrl = url.toUri()
                mediaLog.clear()
                pageDeband = false
                debandConfirmed = false
                boxScrollY = 0
                verificationOnPage = false
                applyThirdPartyCookiePolicy(currentOriginalUrl?.host)
                callback.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                typedNavigationPending = false
                //the https:// version of an upgraded address loaded: no fallback needed any more
                if (url != null && url.startsWith("https://") && pendingUpgradeHost == url.toUri().host) {
                    pendingUpgradeHost = null
                    pendingUpgradeOriginal = null
                }
                Log.d(TAG, "onPageFinished url: $url")
                if (Brand.phone && url != null && url.startsWith("http")) checkIdentity(url)
                callback.onPageFinished(url)
                if (com.phlox.tvwebbrowser.Brand.phone && (cleanMode || verificationOnPage)) startChallengeWatch()
                if (cleanMode) {
                    checkVerificationPassed(url)
                    return
                }
                if (!sofaDocumentStartInstalled) {
                    //older WebView: no document-start scripts, inject into the main frame after load
                    evaluateJavascript(wrapSofaScript(sofaSettingsJson(config, currentOriginalUrl?.host)), null)
                }
                evaluateJavascript(getGenericJSInjects(), null)
                com.phlox.tvwebbrowser.sofa.SofaPasswords.fillIfSaved(this@WebViewEx, url)
                val userScript = config.sofaUserScript
                if (userScript.isNotBlank() && (url?.startsWith("http") == true)) {
                    evaluateJavascript("(function(){try{\n" + userScript +
                            "\n}catch(e){console.error('${com.phlox.tvwebbrowser.Brand.NAME} custom script: ' + e)}})();", null)
                }
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
                //Log.d(TAG, "onLoadResource url: $url")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                Log.e(TAG, "onReceivedSslError url: ${error.url}")
                if (Brand.phone) com.phlox.tvwebbrowser.sofa.WispLog.warn("load",
                    "certificate problem ${error.primaryError} on ${com.phlox.tvwebbrowser.sofa.WispLog.site(error.url)}")
                if (trustSsl && lastSSLError?.certificate?.toString()?.equals(error.certificate.toString()) == true) {
                    trustSsl = false
                    lastSSLError = null
                    handler.proceed()
                    return
                }
                handler.cancel()
                //Wisp: the https:// version of an http:// link has a broken certificate - the site
                //only works over http (as the link said), so open that instead of a warning page
                if (httpsFirstFallback(error.url)) return
                val errUrl = error.url ?: return
                val origUrl = currentOriginalUrl ?: return
                if (Uri.parse(errUrl).host == origUrl.host) {//skip ssl errors during loading non-page resources (Chrome did like this too)
                    showCertificateErrorPage(error)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                if (error.errorCode == ERROR_UNSUPPORTED_SCHEME) return
                if (Brand.phone) com.phlox.tvwebbrowser.sofa.WispLog.warn("load", "could not open " +
                        "${com.phlox.tvwebbrowser.sofa.WispLog.site(request.url.toString())}: error ${error.errorCode} ${error.description}")
                //Wisp: the site has no https:// version (and the name does exist): back to http://
                if (error.errorCode != ERROR_HOST_LOOKUP && httpsFirstFallback(request.url.toString())) return
                showLoadErrorPage(request.url.toString(), error.errorCode, error.description?.toString() ?: "")
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                callback.onScaleChanged(oldScale, newScale)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String, isReload: Boolean) {
                //also called when a site changes its address without loading a new page
                //(history.pushState: video sites, YouTube, ...): keep the address bar up to date
                if (url.startsWith("http") && url != currentOriginalUrl?.toString()) {
                    currentOriginalUrl = url.toUri()
                }
                callback.onUrlChanged(url)
                if (!isReload) {
                    callback.onVisited(url)
                }
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                val userNameEdit = EditText(context).also {
                    it.hint = context.getString(com.phlox.tvwebbrowser.common.R.string.username)
                    it.isSingleLine = true
                }
                val passwordEdit = EditText(context).also {
                    it.hint = context.getString(com.phlox.tvwebbrowser.common.R.string.password)
                    it.isSingleLine = true
                    it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                val container = LinearLayout(context).also {
                    it.orientation = LinearLayout.VERTICAL
                    it.addView(userNameEdit)
                    it.addView(passwordEdit)
                }
                AlertDialog.Builder(context)
                    .setTitle(R.string.http_auth_title)
                    .setCancelable(false)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface, _:Int ->
                        handler?.proceed(userNameEdit.text.toString(), passwordEdit.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _: DialogInterface, _:Int ->
                        handler?.cancel()
                    }
                    .show()
            }
        }

        webChromeClient = webChromeClient_

        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "DownloadListener.onDownloadStart url: $url")
            if (url.startsWith("blob:")) {
                //nop. we handle this by injected js on onPageFinished
            } else if (isHlsMime(mimetype) && openStreamInPlayer(url, force = true)) {
                //a playlist "download" is really a video stream: play it
            } else if (typedNavigationPending && mimetype?.lowercase()?.startsWith("video/") == true &&
                openStreamInPlayer(url, force = true)) {
                //a pasted/typed video address: play it instead of downloading it
                typedNavigationPending = false
            } else {
                callback.onDownloadStart(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }

        addJavascriptInterface(jsInterface, "TVBro")
    }

    override fun restoreState(inState: Bundle): WebBackForwardList? {
        val result = super.restoreState(inState)
        currentOriginalUrl = url?.toUri()
        return result
    }

    /** The last touch came from a finger (the remote's cursor sends events without a source). */
    var lastTouchFromFinger = false
        private set
    /** Where the last touch started, in this view's pixels (Wisp: the link menu opens there). */
    var lastTouchX = 0f
        private set
    var lastTouchY = 0f
        private set
    /** When the screen was last touched (uptime ms). */
    private var lastTouchTime = 0L

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            //the remote's virtual cursor sends synthetic events without an input source
            lastTouchFromFinger = (event.source and android.view.InputDevice.SOURCE_TOUCHSCREEN) ==
                    android.view.InputDevice.SOURCE_TOUCHSCREEN
            lastTouchX = event.x
            lastTouchY = event.y
            lastTouchTime = android.os.SystemClock.uptimeMillis()
            touchDownY = event.y
            touchStartedAtTop = scrollY == 0 && boxScrollY <= 0
            pullReported = false
            overscrolledTop = false
            pullStartY = -1f
            if (pullDistance > 0f) callback.onPullToRefresh(0f, true)
            pullDistance = 0f
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && touchStartedAtTop && !pullReported) {
            //pulling down on a page that is already at the top (finger, or the remote's cursor
            //pushing up at the top edge): bring the top bar back
            if (scrollY == 0 && boxScrollY <= 0 && event.y - touchDownY > PULL_TO_SHOW_DP * resources.displayMetrics.density) {
                pullReported = true
                callback.onPageScrolled(0, -1)
            }
        }
        trackPullToRefresh(event)
        return super.dispatchTouchEvent(event)
    }

    // ---------------- Wisp: pull to refresh ----------------
    /** The page couldn't scroll further up during this drag (it overscrolled at the top). */
    private var overscrolledTop = false
    private var pullStartY = -1f
    private var pullDistance = 0f

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        //only real overscroll counts: pages that use the drag themselves (maps, sliders, games)
        //don't overscroll, so they never trigger a reload
        if (clampedY && scrollY == 0 && lastTouchFromFinger) overscrolledTop = true
    }

    private fun trackPullToRefresh(event: MotionEvent) {
        if (!com.phlox.tvwebbrowser.Brand.phone || !lastTouchFromFinger || !config.wispPullToRefresh) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    //pinch zoom: no reload
                    if (pullDistance > 0f) callback.onPullToRefresh(0f, true)
                    pullDistance = 0f
                    touchStartedAtTop = false
                    return
                }
                if (!touchStartedAtTop || !overscrolledTop || scrollY != 0 || boxScrollY > 0) return
                if (pullStartY < 0f) pullStartY = event.y
                val d = (event.y - pullStartY).coerceAtLeast(0f)
                if (d > 0f || pullDistance > 0f) {
                    pullDistance = d
                    callback.onPullToRefresh(d, false)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pullDistance > 0f) callback.onPullToRefresh(pullDistance, true)
                pullDistance = 0f
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pullDistance > 0f) callback.onPullToRefresh(0f, true)
                pullDistance = 0f
            }
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != oldt) callback.onPageScrolled(t, t - oldt)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (virtualCursorMode && DPADNavigationEventsAdapter.isNavigationGenericMotionSource(event.source))
            return false
        return super.dispatchGenericMotionEvent(event)
    }

    private fun showCertificateErrorPage(error: SslError) {
        callback.onPageCertificateError(error.url)
        lastSSLError = error
        val url = INTERNAL_SCHEME + INTERNAL_SCHEME_WARNING_DOMAIN +
                "?type=" + INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT +
                "&url=" + URLEncoder.encode(error.url, "UTF-8")
        loadUrl(url)
    }

    override fun loadUrl(url: String) {
        when {
            //Wisp: a page saved offline (also when a tab with one is restored or reopened)
            com.phlox.tvwebbrowser.Brand.phone && com.phlox.tvwebbrowser.activity.main.WispPages.isSavedPage(context, url) ->
                Uri.parse(url).path?.let { openSavedPage(it) }
            Config.HOME_URL_ALIAS == url -> {
                when (config.homePageMode) {
                    Config.HomePageMode.BLANK -> {
                        loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                    }
                    Config.HomePageMode.CUSTOM, Config.HomePageMode.SEARCH_ENGINE -> {
                        try {
                            currentOriginalUrl = config.homePage.toUri()
                            super.loadUrl(config.homePage)
                        } catch (e: Exception) {
                            Log.e(TAG, "LoadUrl error", e)
                            loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                        }

                    }
                    Config.HomePageMode.HOME_PAGE -> {
                        currentOriginalUrl = Config.HOME_PAGE_URL.toUri()
                        super.loadUrl(Config.HOME_PAGE_URL)
                    }
                }

            }
            url.startsWith(INTERNAL_SCHEME) -> {
                val uri = Uri.parse(url)
                when (uri.authority) {
                    INTERNAL_SCHEME_WARNING_DOMAIN -> {
                        when (uri.getQueryParameter("type")) {
                            INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT -> {
                                val data = context.assets.open("pages/warning-certificate.html").bufferedReader().use { it.readText() }
                                loadDataWithBaseURL("file:///android_asset/", data, "text/html", "UTF-8", uri.getQueryParameter("url"))
                            }
                        }
                    }
                }
            }
            openStreamInPlayer(url) -> {
                //typed or bookmarked stream link: played in the built-in player
            }
            isDirectVideoFile(url) && openStreamInPlayer(url, force = true) -> {
                //typed or pasted video file (.mp4, .mkv, .webm, ...): the built-in player decodes it
                //in hardware and copes with high bitrates far better than the web page player
            }
            openExternallyIfWanted(url) -> {
                //opened in Firefox (Site fixes)
            }
            else -> {
                typedNavigationPending = true
                currentOriginalUrl = Uri.parse(url)
                //Wisp: a user agent switch during a running load restarts that load (and the new
                //address was lost: "typed address, nothing happens")
                if (Brand.phone) {
                    val host = try { url.toUri().host } catch (e: Exception) { null }
                    if (isDesktopUa(userAgentFor(host)) != isDesktopUa(settings.userAgentString ?: "")) stopLoading()
                }
                applyUserAgentFor(url)
                super.loadUrl(url)
            }
        }
    }

    /** Set while an address typed/pasted by the user loads; see the download listener. */
    private var typedNavigationPending = false

    private fun isOnInternalHomePage(): Boolean =
        currentOriginalUrl?.toString()?.startsWith(Config.HOME_PAGE_URL) == true

    private fun isDirectVideoFile(url: String): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return VIDEO_FILE_EXTENSIONS.any { path.endsWith(it) }
    }

    /** Shown instead of a dead page after the page's process crashed. */
    fun showCrashPage(failedUrl: String?) {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val target = failedUrl?.takeIf { it.startsWith("http") }
        val retry = if (target != null) "<a class=\"b\" href=\"${esc(target)}\">Reload page</a>" else ""
        val html = if (Brand.phone) {
            //Wisp: plain, phone-sized, in Wisp's colours
            """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><style>
:root{--bg:#F4F4F5;--t:#1A1A1C;--m:#6E6E73;--a:#2C2C30;--on:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#121213;--t:#EDEDEF;--m:#A0A0A6;--a:#E4E4E7;--on:#18181A}}
body{font-family:system-ui,sans-serif;background:var(--bg);color:var(--t);margin:0;min-height:90vh;display:flex;align-items:center}
.c{padding:28px}h1{font-size:22px;margin:0 0 10px}p{font-size:15px;line-height:1.5;color:var(--m);margin:0 0 8px}
a.b{display:inline-block;margin-top:14px;padding:11px 22px;border-radius:22px;background:var(--a);color:var(--on);text-decoration:none;font-weight:600;font-size:15px}</style>
</head><body><div class="c"><h1>This page was closed</h1>
<p>Android ended the page to free memory, or the page crashed.</p>
$retry</div></body></html>"""
        } else """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;background:#0f1d20;color:#e8f5f2;display:flex;align-items:center;justify-content:center;min-height:90vh;margin:0}
.c{max-width:640px;padding:24px}h1{font-size:28px;color:#20c997;margin:0 0 12px}p{font-size:18px;line-height:1.5}
a.b{display:inline-block;margin-top:12px;padding:12px 22px;border-radius:12px;background:#20c997;color:#062b2f;text-decoration:none;font-weight:bold;font-size:18px}</style>
</head><body><div class="c"><h1>This page stopped working</h1>
<p>The page used more memory than this device allows. That often happens with very high-bitrate videos.</p>
<p>Tip: paste the address of a video file or stream (.mp4, .mkv, .m3u8, …) into the address bar, or use the grab button to copy it, to play it in Lumen's own hardware player.</p>
$retry</div></body></html>"""
        loadDataWithBaseURL(target, html, "text/html", "UTF-8", null)
    }

    private fun isHlsMime(mime: String?): Boolean {
        val m = mime?.lowercase() ?: return false
        return m.contains("mpegurl") || m == "application/dash+xml"
    }

    /**
     * Opens HLS/DASH stream URLs in Lumen's built-in player (Media3, hardware decoding) instead of
     * showing the playlist text or downloading it. Returns true when the URL was handled.
     */
    fun openStreamInPlayer(url: String, force: Boolean = false): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        if (!force && !com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.isManifest(url)) return false
        val referer = currentOriginalUrl?.toString()?.takeIf { it.startsWith("http") && it != url }
        val stream = com.phlox.tvwebbrowser.sofa.SofaVideoHandoff.Stream(
            url = url,
            title = title ?: "",
            referer = referer,
            userAgent = settings.userAgentString,
            cookies = try { android.webkit.CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null },
            positionMs = 0L,
            subtitles = mediaLog.subtitles()
        )
        val deband = pageDeband || config.isDebandSite(currentOriginalUrl?.host)
        uiHandler.post { com.phlox.tvwebbrowser.sofa.SofaPlayerActivity.start(context, stream, deband) }
        return true
    }

    /** Readable page instead of a blank screen when a site can't be opened. */
    private fun showLoadErrorPage(failingUrl: String, errorCode: Int, description: String) {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val host = try { Uri.parse(failingUrl).host ?: failingUrl } catch (e: Exception) { failingUrl }
        val reason = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> "The address of <b>${esc(host)}</b> could not be found. Your internet provider may be blocking this site, or it doesn't exist."
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT -> "<b>${esc(host)}</b> did not respond. The site may be down, or your internet provider may be blocking it."
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "A secure connection to <b>${esc(host)}</b> could not be made. Your provider may be interfering with it."
            else -> "<b>${esc(host)}</b> could not be loaded."
        }
        if (Brand.phone) {
            //Wisp: phone wording, Wisp colours, no TV or Google DNS advice
            val vpnOn = com.phlox.tvwebbrowser.sofa.SofaVpn.status.value == com.phlox.tvwebbrowser.sofa.SofaVpn.Status.CONNECTED ||
                    com.phlox.tvwebbrowser.sofa.SofaVpn.status.value == com.phlox.tvwebbrowser.sofa.SofaVpn.Status.CONNECTING
            val tip = when {
                vpnOn -> "Wisp's VPN is on. If no site opens, the VPN server may be unreachable from this network: " +
                        "turn the VPN off in Settings → VPN, or try another server."
                errorCode == WebViewClient.ERROR_HOST_LOOKUP -> "Check that mobile data or Wi-Fi is on. If only this site fails, " +
                        "your provider may block it: Wisp's VPN (Settings → VPN) can get around that."
                else -> "Check your connection and try again."
            }
            val phoneHtml = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><style>
:root{--bg:#F4F4F5;--t:#1A1A1C;--m:#6E6E73;--a:#2C2C30;--on:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#121213;--t:#EDEDEF;--m:#A0A0A6;--a:#E4E4E7;--on:#18181A}}
body{font-family:system-ui,sans-serif;background:var(--bg);color:var(--t);margin:0;min-height:90vh;display:flex;align-items:center}
.c{padding:28px}h1{font-size:22px;margin:0 0 10px}p{font-size:15px;line-height:1.5;color:var(--m);margin:0 0 10px}b{color:var(--t)}
a.b{display:inline-block;margin-top:12px;padding:11px 22px;border-radius:22px;background:var(--a);color:var(--on);text-decoration:none;font-weight:600;font-size:15px}
small{display:block;margin-top:18px;font-size:13px;color:var(--m)}</style></head><body><div class="c"><h1>Can't open this site</h1>
<p>$reason</p><p>${esc(tip)}</p>
<a class="b" href="${esc(failingUrl)}">Try again</a><small>${esc(description)} ($errorCode)</small></div></body></html>"""
            uiHandler.post { loadDataWithBaseURL(failingUrl, phoneHtml, "text/html", "UTF-8", failingUrl) }
            return
        }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;background:#0f1d20;color:#e8f5f2;display:flex;align-items:center;justify-content:center;min-height:90vh;margin:0}
.c{max-width:640px;padding:24px}h1{font-size:28px;color:#20c997;margin:0 0 12px}p{font-size:18px;line-height:1.5}
a.b{display:inline-block;margin-top:12px;padding:12px 22px;border-radius:12px;background:#20c997;color:#062b2f;text-decoration:none;font-weight:bold;font-size:18px}
small{opacity:.6}</style></head><body><div class="c"><h1>Can't open this site</h1><p>$reason</p>
<p>If the site works on other networks, try one of these:<br>• Turn on the VPN: Settings → WireGuard VPN<br>
• Use Private DNS on your TV: Android Settings → Network → Private DNS → <i>dns.google</i></p>
<a class="b" href="${esc(failingUrl)}">Try again</a><p><small>${esc(description)} ($errorCode)</small></p></div></body></html>"""
        uiHandler.post {
            loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
        }
    }

    private fun getSofaScript(): String {
        var script = sofaScriptCache
        if (script == null) {
            script = context.assets.open("sofa_enhancements.js").bufferedReader().use { it.readText() }
            sofaScriptCache = script
        }
        return script
    }

    // ---------------- Lumen: redirects to ad sites ----------------

    /** The link the user clicked last (reported by the page script), to tell real links from ad redirects. */
    @Volatile var lastLinkHost: String? = null
    @Volatile var lastLinkTime = 0L
    /** The user pressed "Open" in the "Redirect blocked" bar: this address may load once. */
    @Volatile var allowedNav: String? = null

    private fun regSite(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        //example.co.uk, example.com.br
        val two = parts[parts.size - 2]
        val n = if (two.length <= 3 && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    /**
     * Ad scripts (also behind fake "press OK" boxes) send the tab to an ad address when the pop-up
     * they wanted was blocked. Such navigations to another site are stopped when the address is on
     * the ad lists or uses a throwaway domain ending that ad networks rotate through, unless it is the
     * link the user just clicked. The page shows a bar with "Open" in case it was wanted.
     */
    private fun blockAdRedirect(request: WebResourceRequest): Boolean {
        if (!callback.isAdBlockingEnabled() || request.isRedirect || cleanMode) return false
        val target = request.url
        val targetHost = target.host ?: return false
        if (target.scheme != "http" && target.scheme != "https") return false
        val current = try { Uri.parse(url ?: return false) } catch (e: Exception) { return false }
        val currentHost = current.host ?: return false
        if (current.scheme != "http" && current.scheme != "https") return false
        if (regSite(targetHost) == regSite(currentHost)) return false
        val full = target.toString()
        if (allowedNav == full) { allowedNav = null; return false }
        val clickedLink = lastLinkHost != null && System.currentTimeMillis() - lastLinkTime < 4000 &&
                regSite(lastLinkHost!!) == regSite(targetHost)
        if (clickedLink) return false
        val junk = JUNK_TLD.matches(targetHost.substringAfterLast('.').lowercase())
        val listed = try { callback.isAd(request, current) } catch (e: Exception) { false }
        if (!junk && !listed) return false
        Log.i(TAG, "Lumen blocked a redirect to an ad site: $full")
        evaluateJavascript("window.__lumenBlockedNav&&__lumenBlockedNav(" + org.json.JSONObject.quote(full) + ")", null)
        return true
    }

    private fun isStreamPiece(url: Uri): Boolean {
        val path = (url.path ?: "").lowercase()
        val last = path.substringAfterLast('/')
        return STREAM_EXT.any { path.endsWith(it) } ||
                //fMP4 init segments, numbered segments without an extension
                (last.contains("init") && (last.endsWith(".mp4") || last.endsWith(".m4v") || last.endsWith(".m4a"))) ||
                Regex("(seg|segment|chunk|frag|fragment)[-_]?\\d+").containsMatchIn(last)
    }

    private fun openExternallyIfWanted(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val host = try { url.toUri().host } catch (e: Exception) { null }
        if (!config.isExternalSite(host)) return false
        return com.phlox.tvwebbrowser.sofa.LumenExternal.open(context, url)
    }

    private fun wrapSofaScript(cfgJson: String): String =
        "(function(__sofaCfgEmbedded){\n" + getSofaScript() + "\n})(" + cfgJson + ");"

    // ---------------- human verification (Cloudflare) ----------------

    /** The page is a verification page: no bridge, no scripts, no ad blocking until it passes. */
    @Volatile
    var cleanMode = false
        private set

    private fun systemUserAgent(): String = WebSettings.getDefaultUserAgent(context)

    /** Called by the page script on a Cloudflare challenge page. */
    fun onVerificationPage(pageUrl: String) {
        val host = try { pageUrl.toUri().host } catch (e: Exception) { null } ?: return
        if (cleanMode || !com.phlox.tvwebbrowser.sofa.LumenVerify.startClean(host)) return
        Log.i(TAG, "Verification page on $host: reloading in clean mode")
        cleanMode = true
        removeJavascriptInterface("TVBro")
        settings.userAgentString = systemUserAgent()
        stopLoading()
        //a GET of the page; the challenge sets its pass cookie and returns to it
        loadPlain(pageUrl)
    }

    /**
     * Background thread: a verification widget loads inside the page shown. From the next load on,
     * the page script leaves out the changes such widgets can detect. The user agent stays as it is:
     * the WebView's own one ("; wv") makes Cloudflare challenge more often, not less.
     */
    private fun onVerificationWidget(pageUrl: Uri?) {
        val host = pageUrl?.host ?: return
        if (!pageUrl.toString().startsWith("http")) return
        com.phlox.tvwebbrowser.sofa.LumenVerify.rememberWidget(host)
        //Wisp: verification widgets (Cloudflare, hCaptcha, reCAPTCHA) need their third-party cookies.
        //This runs on the network thread: the request waits (up to 1.5 s) until the cookie setting
        //is really switched on. Before, the setting was only queued; on a busy phone the check's
        //first answer came back before it applied, its cookies were dropped and the check failed.
        if (com.phlox.tvwebbrowser.Brand.phone && !verificationOnPage) {
            verificationOnPage = true
            if (thirdPartyCookiesAllowed == true) return
            val applied = java.util.concurrent.CountDownLatch(1)
            uiHandler.post {
                try { applyThirdPartyCookiePolicy(host) } finally { applied.countDown() }
            }
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                try { applied.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
            }
        }
    }

    // ---------------- Wisp privacy: third-party cookies and HTTPS first ----------------

    /** A human-verification widget was seen on the current page. */
    @Volatile private var verificationOnPage = false
    /** What this view was last set to (null: not set yet). */
    @Volatile private var thirdPartyCookiesAllowed: Boolean? = null

    /**
     * Wisp: third-party cookies (set by frames from other sites, mostly trackers) are blocked, except
     * on sites the user allowed (menu → Privacy), sites with a verification widget, or with the
     * setting off. Lumen leaves cookies as they were (Cloudflare compatibility allows them).
     */
    fun applyThirdPartyCookiePolicy(host: String?) {
        if (!com.phlox.tvwebbrowser.Brand.phone) return
        val allow = !config.wispBlockThirdPartyCookies || verificationOnPage ||
                config.isThirdPartyCookieSite(host) || com.phlox.tvwebbrowser.sofa.LumenVerify.isVerifySafe(host)
        if (thirdPartyCookiesAllowed == allow) return
        thirdPartyCookiesAllowed = allow
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, allow)
        } catch (e: Exception) {
            Log.e(TAG, "third-party cookies", e)
        }
    }

    /** Host whose http:// link was just opened over https://, and that link (for the fallback). */
    private var pendingUpgradeHost: String? = null
    private var pendingUpgradeOriginal: String? = null

    private fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
                h.endsWith(".internal") || !h.contains('.') ||
                Regex("""^(127|10|192\.168|172\.(1[6-9]|2\d|3[01])|169\.254)\.[\d.]+$""").matches(h) || h.startsWith("[")
    }

    /**
     * Wisp, HTTPS first: a plain http:// address (link or redirect) is opened as https:// instead.
     * If that fails (no https version, broken certificate, or the site sends us back to http://),
     * [httpsFirstFallback] opens the original http:// address and the site is remembered for this session.
     */
    private fun httpsFirstUpgrade(uri: Uri): Boolean {
        if (!com.phlox.tvwebbrowser.Brand.phone || !config.wispHttpsFirst) return false
        if (!"http".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host ?: return false
        if (host in httpOnlyHosts || isLocalHost(host)) return false
        if (uri.port != -1 && uri.port != 80) return false
        if (host == pendingUpgradeHost) {
            //the https:// page sent us straight back to http://: this site only works over http
            httpOnlyHosts.add(host)
            pendingUpgradeHost = null
            pendingUpgradeOriginal = null
            return false
        }
        val https = uri.buildUpon().scheme("https").encodedAuthority(uri.encodedAuthority?.removeSuffix(":80")).build().toString()
        pendingUpgradeHost = host
        pendingUpgradeOriginal = uri.toString()
        loadUpgraded(https)
        return true
    }

    /** Opens the http:// address when its https:// version just failed. True when it did. */
    private fun httpsFirstFallback(failedUrl: String?): Boolean {
        val original = pendingUpgradeOriginal ?: return false
        val host = pendingUpgradeHost ?: return false
        val failed = try { failedUrl?.toUri() } catch (e: Exception) { null } ?: return false
        if (!"https".equals(failed.scheme, ignoreCase = true) || failed.host != host) return false
        pendingUpgradeHost = null
        pendingUpgradeOriginal = null
        httpOnlyHosts.add(host)
        uiHandler.post {
            android.widget.Toast.makeText(context, context.getString(R.string.wisp_http_only, host),
                android.widget.Toast.LENGTH_SHORT).show()
            loadUpgraded(original)
        }
        return true
    }

    /** Loads an address the browser chose itself (not typed by the user). */
    private fun loadUpgraded(url: String) {
        currentOriginalUrl = url.toUri()
        applyUserAgentFor(url)
        super.loadUrl(url)
    }

    /** Turned on by hand (ad-block menu): stays until another site is opened. */
    private var manualClean = false

    /**
     * "Verification failing? Reload clean": the current page again with nothing of the browser's
     * own (no bridge, scripts, ad blocking; the engine's own user agent). Password saving is off
     * on that page.
     */
    fun startManualCleanMode(): Boolean {
        val pageUrl = url ?: return false
        if (!pageUrl.startsWith("http")) return false
        val host = try { pageUrl.toUri().host } catch (e: Exception) { null } ?: return false
        com.phlox.tvwebbrowser.sofa.LumenVerify.startClean(host)
        if (!cleanMode) removeJavascriptInterface("TVBro")
        cleanMode = true
        manualClean = true
        settings.userAgentString = systemUserAgent()
        stopLoading()
        loadPlain(pageUrl)
        return true
    }

    /** Leaves clean mode before navigating to [url] when it isn't on the verifying site. */
    private fun leaveCleanModeIfNeeded(url: String?) {
        if (!cleanMode) return
        val host = try { url?.toUri()?.host } catch (e: Exception) { null }
        if (com.phlox.tvwebbrowser.sofa.LumenVerify.isClean(host)) return
        restoreFromCleanMode()
    }

    private fun restoreFromCleanMode() {
        cleanMode = false
        manualClean = false
        addJavascriptInterface(jsInterface, "TVBro")
    }

    /** In clean mode, after each page load: once the challenge is gone, turn everything back on. */
    private fun checkVerificationPassed(url: String?) {
        if (!cleanMode || manualClean || url == null || !url.startsWith("http")) return
        evaluateJavascript("(typeof window._cf_chl_opt==='undefined')") { result ->
            if (result != "true" || !cleanMode) return@evaluateJavascript
            if (url != this.url) return@evaluateJavascript
            val host = try { url.toUri().host } catch (e: Exception) { null }
            Log.i(TAG, "Verification passed on $host")
            com.phlox.tvwebbrowser.sofa.LumenVerify.endClean(host)
            //Cloudflare's pass belongs to this user agent: keep using it on this site
            if (host != null) com.phlox.tvwebbrowser.sofa.LumenVerify.rememberSystemUa(host)
            restoreFromCleanMode()
            //reload with the page enhancements (ad blocking, pop-up blocker) back on
            loadPlain(stripChallengeParams(url))
        }
    }

    private fun loadPlain(url: String) {
        super.loadUrl(url)
    }

    // ---------------- Wisp: retry a failed Cloudflare check ----------------
    // The full-page check ("Verify you are human") sometimes fails on the phone and passes on its
    // second run (rotating the phone made it run again). Wisp now does that by itself: when the
    // page shows Cloudflare's error text, or the check has been stuck for 25 s without the screen
    // being touched, the page is loaded once more - at most twice for the same page.

    private var challengeLoadStart = 0L
    private var challengeRetryFor: String? = null
    private var challengeRetries = 0
    private val challengeWatch = Runnable { checkChallenge() }

    private fun startChallengeWatch() {
        uiHandler.removeCallbacks(challengeWatch)
        challengeLoadStart = android.os.SystemClock.uptimeMillis()
        uiHandler.postDelayed(challengeWatch, 3000)
    }

    /** Reads the page without changing it: is the check still there, and is it showing an error? */
    private val challengeStateJs = "(function(){try{" +
            "if(typeof window._cf_chl_opt==='undefined')return 'gone';" +
            "var e=document.querySelector('#challenge-error-text,#challenge-error-title');" +
            "if(e&&e.offsetParent!==null&&(e.textContent||'').trim().length>0)return 'failed';" +
            "return 'wait';}catch(x){return 'wait';}})()"

    private fun checkChallenge() {
        val pageUrl = url ?: return
        if (!pageUrl.startsWith("http") || !isAttachedToWindow) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - challengeLoadStart > 90_000) return
        evaluateJavascript(challengeStateJs) { r ->
            if (url != pageUrl) return@evaluateJavascript
            val untouched = lastTouchTime < challengeLoadStart
            val stuck = untouched && android.os.SystemClock.uptimeMillis() - challengeLoadStart > 25_000
            when {
                r == "\"gone\"" -> { challengeRetries = 0; return@evaluateJavascript }
                r == "\"failed\"" || stuck -> retryChallenge(pageUrl, if (stuck) "stuck" else "failed")
                else -> uiHandler.postDelayed(challengeWatch, 3000)
            }
        }
    }

    private fun retryChallenge(pageUrl: String, why: String) {
        val clean = stripChallengeParams(pageUrl)
        if (challengeRetryFor != clean) {
            challengeRetryFor = clean
            challengeRetries = 0
        }
        if (challengeRetries >= 2) {
            Log.i(TAG, "Cloudflare check $why on $clean: no more retries")
            return
        }
        challengeRetries++
        Log.i(TAG, "Cloudflare check $why on $clean: running it again ($challengeRetries)")
        uiHandler.post { loadPlain(clean) }
    }

    private fun stripChallengeParams(url: String): String = try {
        val uri = url.toUri()
        val names = uri.queryParameterNames.filter { !it.startsWith("__cf_chl") }
        if (names.size == uri.queryParameterNames.size) url else {
            val b = uri.buildUpon().clearQuery()
            for (n in names) for (v in uri.getQueryParameters(n)) b.appendQueryParameter(n, v)
            b.build().toString()
        }
    } catch (e: Exception) {
        url
    }

    private fun installSofaEnhancements() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            //runs in every frame (also inside video embeds) before the page's own scripts
            WebViewCompat.addDocumentStartJavaScript(
                this,
                wrapSofaScript(sofaSettingsJson(config)),
                setOf("*")
            )
            sofaDocumentStartInstalled = true
        } catch (e: Exception) {
            Log.e(TAG, "Can not install document start script", e)
        }
    }

    /** Used when no custom user agent is chosen in settings. */
    fun applyDefaultUserAgentIfNeeded() {
        if (config.userAgentString.value != null) return
        val systemUa = WebSettings.getDefaultUserAgent(context)
        val target = if (config.sofaCloudflareCompat) cleanUserAgent(systemUa) else systemUa
        if (settings.userAgentString != target) settings.userAgentString = target
    }

    /** Desktop Chrome user agent with the same Chrome version as this WebView. */
    private fun desktopUserAgent(): String {
        val systemUa = WebSettings.getDefaultUserAgent(context)
        val major = Regex("Chrome/(\\d+)").find(systemUa)?.groupValues?.get(1) ?: "145"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Safari/537.36"
    }

    /**
     * Picks the user agent for the page about to load: desktop for sites switched to
     * "Desktop site", otherwise the user's choice from settings (or the default).
     */
    fun applyUserAgentFor(url: String?) {
        val host = try { url?.toUri()?.host } catch (e: Exception) { null }
        leaveCleanModeIfNeeded(url)
        //file access is only on while a page saved offline is shown
        @Suppress("DEPRECATION")
        if (settings.allowFileAccess) settings.allowFileAccess = false
        //Wisp: desktop pages are wide and get shrunk to the phone's width; text autosizing (what
        //Chrome does for such pages) makes their text readable again
        if (Brand.phone) {
            @Suppress("DEPRECATION")
            val layout = if (config.isDesktopSite(host)) WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                else WebSettings.LayoutAlgorithm.NORMAL
            @Suppress("DEPRECATION")
            if (settings.layoutAlgorithm != layout) settings.layoutAlgorithm = layout
        }
        val ua = userAgentFor(host)
        if (settings.userAgentString != ua) settings.userAgentString = ua
    }

    /** Wisp: opens a page saved offline (a web archive in the app's own files). */
    fun openSavedPage(path: String) {
        stopLoading()
        settings.allowFileAccess = true
        currentOriginalUrl = Uri.fromFile(java.io.File(path))
        super.loadUrl(currentOriginalUrl.toString())
    }

    /** Wisp: shows a reader page for [pageUrl] (Back or ✕ returns to the page itself). */
    fun showReaderPage(html: String, pageUrl: String) {
        loadDataWithBaseURL(pageUrl, html, "text/html", "UTF-8", pageUrl)
    }

    /** Sets the identity the current page should have (nothing else changes). */
    fun refreshUserAgent() {
        val host = try { url?.toUri()?.host } catch (e: Exception) { null }
        val ua = userAgentFor(host)
        if (settings.userAgentString != ua) settings.userAgentString = ua
    }

    /** The user agent a page on [host] should get (see [applyUserAgentFor]). */
    private fun userAgentFor(host: String?): String {
        if (!cleanMode && config.isFirefoxSite(host)) {
            //chosen by the user for this site (shield menu → Site fixes)
            val major = Regex("Android (\\d+)").find(systemUserAgent())?.groupValues?.get(1) ?: "13"
            return "Mozilla/5.0 (Android $major; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0"
        }
        if (cleanMode || com.phlox.tvwebbrowser.sofa.LumenVerify.isClean(host) ||
            com.phlox.tvwebbrowser.sofa.LumenVerify.keepsSystemUa(host)) {
            //Cloudflare ties its pass to the exact browser identity: use the engine's real one
            return systemUserAgent()
        }
        if (config.isDesktopSite(host)) return desktopUserAgent()
        val custom = config.userAgentString.value
        //Wisp: a desktop identity only for sites switched to "Desktop site" (an older setting that
        //made every site desktop is ignored)
        if (custom != null && !(Brand.phone && isDesktopUa(custom))) return custom
        val systemUa = WebSettings.getDefaultUserAgent(context)
        return if (config.sofaCloudflareCompat) cleanUserAgent(systemUa) else systemUa
    }

    fun isDesktopUa(ua: String) = ua.contains("Windows NT") || ua.contains("Macintosh") ||
            (ua.contains("X11") && !ua.contains("Android"))

    /**
     * Wisp: a link to a site that needs another user agent. Changing the user agent while a page
     * loads makes WebView start that load over - from its first address. A link going through a
     * redirect (Google's result links) then switched back and forth without end ("keeps
     * refreshing"), or the new address was dropped. So: stop, switch, and open the address itself.
     * True when it did that.
     */
    private val uaSwitches = ArrayList<Long>()

    /** Diagnostics: the page loaded with the identity its "Desktop site" switch asks for? */
    private fun checkIdentity(url: String) {
        val host = try { url.toUri().host } catch (e: Exception) { null } ?: return
        val want = isDesktopUa(userAgentFor(host))
        val got = isDesktopUa(settings.userAgentString ?: "")
        if (want != got) com.phlox.tvwebbrowser.sofa.WispLog.mismatch("Desktop site switch vs page",
            "${com.phlox.tvwebbrowser.sofa.WispLog.site(url)} should load as ${if (want) "desktop" else "mobile"}, " +
                    "loaded as ${if (got) "desktop" else "mobile"}")
    }

    private fun reloadWithUserAgentIfNeeded(url: String): Boolean {
        if (!Brand.phone || !url.startsWith("http")) return false
        val host = try { url.toUri().host } catch (e: Exception) { null }
        //only a switch between phone and desktop identity needs a fresh load; small differences
        //between phone identities are applied in place, as before
        if (isDesktopUa(userAgentFor(host)) == isDesktopUa(settings.userAgentString ?: "")) return false
        //never a loop: at most 3 such switches in 10 seconds
        val now = android.os.SystemClock.uptimeMillis()
        uaSwitches.removeAll { now - it > 10_000 }
        if (uaSwitches.size >= 3) {
            com.phlox.tvwebbrowser.sofa.WispLog.warn("loop", "desktop/mobile switch stopped on " +
                    "${com.phlox.tvwebbrowser.sofa.WispLog.site(url)}: 3 switches in 10 s")
            return false
        }
        uaSwitches.add(now)
        com.phlox.tvwebbrowser.sofa.WispLog.event("page", "reload as " +
                (if (isDesktopUa(userAgentFor(host))) "desktop" else "mobile") + " site: ${com.phlox.tvwebbrowser.sofa.WispLog.site(url)}")
        uiHandler.post {
            stopLoading()
            currentOriginalUrl = url.toUri()
            applyUserAgentFor(url)
            super.loadUrl(url)
        }
        return true
    }

    /** Wisp: Reload after "Desktop site" was switched (or the site's identity changed) loads with the right one. */
    override fun reload() {
        val u = url
        if (Brand.phone && u != null && u.startsWith("http")) {
            val host = try { u.toUri().host } catch (e: Exception) { null }
            if (isDesktopUa(userAgentFor(host)) != isDesktopUa(settings.userAgentString ?: "")) {
                stopLoading()
                applyUserAgentFor(u)
                super.loadUrl(u)
                return
            }
        }
        super.reload()
    }

    private fun getGenericJSInjects(): String {
        var injects = genericInjects
        if (injects == null) {
            injects =
                context.assets.open("generic_injects.js").bufferedReader().use { it.readText() }
            genericInjects = injects
        }
        return injects
    }

    fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        if (width == 0 || height == 0) return null
        var thumbnail = bitmap
        if (thumbnail == null) {
            try {
                thumbnail = createBitmap(width, height, Bitmap.Config.RGB_565)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        if (thumbnail == null) {
            return null
        }
        val canvas = Canvas(thumbnail)
        val scaleFactor = thumbnail.width / width.toFloat()
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(-scrollX.toFloat() * scaleFactor, -scrollY.toFloat() * scaleFactor)
        super.draw(canvas)
        return thumbnail
    }

    fun hideCustomView() {
        webChromeClient_.onHideCustomView()
    }

    fun onFilePicked(data: Intent) {
        pickFileCallback?.apply {
            if (data.data != null) {
                val uris = arrayOf(data.data!!)
                onReceiveValue(uris)
            }
        }
    }

    fun onPermissionsResult(permissions: Array<String>, grantResults: IntArray, typeGeo: Boolean) {
        if (typeGeo) geoPermissionsCallback?.apply {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                this.invoke(geoPermissionOrigin, true, true)
            } else {
                this.invoke(geoPermissionOrigin, false, false)
            }
            geoPermissionsCallback = null
            geoPermissionOrigin = null


        } else webPermissionsRequest?.apply {
            // If request is cancelled, the result arrays are empty.
            val resources = ArrayList<String>()
            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    if (Manifest.permission.CAMERA == permissions[i]) {
                        resources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    } else if (Manifest.permission.RECORD_AUDIO == permissions[i]) {
                        resources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    }
                }
            }
            requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions?.apply {
                resources.addAll(this)
                requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions = null
            }
            if (resources.isEmpty()) {
                this.deny()
            } else {
                this.grant(resources.toTypedArray())
            }
            webPermissionsRequest = null
        }
    }

    fun onUpdateAdblockSetting(adblockEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //Wisp: Google Safe Browsing stays off (it sent Google checks of the pages opened)
            settings.safeBrowsingEnabled = adblockEnabled && !Brand.phone
        }
    }

    fun setVirtualCursorMode(enabled: Boolean) {
        this.virtualCursorMode = enabled
    }
}
