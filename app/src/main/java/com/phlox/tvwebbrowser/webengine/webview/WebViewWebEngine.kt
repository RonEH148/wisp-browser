package com.phlox.tvwebbrowser.webengine.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.widgets.cursor.CursorLayout


class WebViewWebEngine(val tab: WebTabState) : WebEngine, CursorDrawerDelegate.Callback {
    private var webView: WebViewEx? = null
    internal var callback: WebEngineWindowProviderCallback? = null
    private var viewParent: CursorLayout? = null
    private var fullScreenView: View? = null
    private val permissionsRequests = HashMap<Int, Boolean>()//request code, isGeolocationPermissionRequest
    private val jsInterface = AndroidJSInterface(this)

    override fun getWebEngineName(): String = "WebView"

    override fun isSameSession(internalRepresentation: Any): Boolean {
        return internalRepresentation == webView
    }

    override val url: String?
        get() = webView?.url

    override var userAgentString: String? = null
        set(value) {
            field = value
            //Wisp: the identity is chosen per page (phone, or desktop for "Desktop site" sites)
            if (com.phlox.tvwebbrowser.Brand.phone) {
                webView?.refreshUserAgent()
                return
            }
            if (value != null) {
                webView?.settings?.userAgentString = value
            } else {
                webView?.applyDefaultUserAgentIfNeeded()
            }
        }

    override fun saveState(): Any {
        val bundle = Bundle()
        webView?.saveState(bundle)
        return bundle
    }

    override fun restoreState(savedInstanceState: Any) {
        if (savedInstanceState is Bundle) {
            webView?.restoreState(savedInstanceState)
        } else {
            throw IllegalArgumentException("savedInstanceState must be Bundle")
        }
    }

    override fun stateFromBytes(bytes: ByteArray): Any? =
        Utils.bytesToBundle(bytes)

    override fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    override fun canGoForward(): Boolean {
        return webView?.canGoForward() ?: false
    }

    override fun goForward() {
        webView?.goForward()
    }

    override fun canZoomIn(): Boolean {
        return webView?.canZoomIn() ?: false
    }

    override fun zoomIn() {
        webView?.zoomIn()
    }

    override fun canZoomOut(): Boolean {
        return webView?.canZoomOut() ?: false
    }

    override fun zoomOut() {
        webView?.zoomOut()
    }

    override fun zoomBy(zoomBy: Float) {
        webView?.zoomBy(zoomBy)
    }

    override fun evaluateJavascript(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    override fun setNetworkAvailable(connected: Boolean) {
        webView?.setNetworkAvailable(connected)
    }

    override fun getView(): View? {
        return webView
    }

    @Throws(Exception::class)
    override fun getOrCreateView(activityContext: Context): View {
        if (webView == null) {
            webView = WebViewEx(activityContext, webViewCallback, jsInterface).also {
                if (com.phlox.tvwebbrowser.Brand.phone) installTouchLinkMenu(it)
            }
        }
        return webView!!
    }

    /**
     * Wisp: holding a finger on a link or image opens the link menu (new tab, copy, share,
     * download...); anywhere else it selects text as usual.
     */
    private fun installTouchLinkMenu(wv: WebViewEx) {
        wv.setOnLongClickListener {
            //the remote's cursor has its own long press (see WebViewEx)
            if (!wv.lastTouchFromFinger) return@setOnLongClickListener true
            val hit = wv.hitTestResult
            val x = wv.lastTouchX.toInt()
            val y = wv.lastTouchY.toInt()
            val page = wv.url
            when (hit.type) {
                android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    callback?.suggestActionsForLink(page, hit.extra, null, null, null, null, x, y)
                    true
                }
                android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    //an image inside a link: ask the page for the link's address
                    val image = hit.extra
                    val handler = android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
                        val link = msg.data?.getString("url")?.takeIf { it.isNotBlank() } ?: image
                        callback?.suggestActionsForLink(page, link, image, null, null, null, x, y)
                        true
                    }
                    wv.requestFocusNodeHref(handler.obtainMessage())
                    true
                }
                android.webkit.WebView.HitTestResult.IMAGE_TYPE -> {
                    callback?.suggestActionsForLink(page, null, hit.extra, null, null, null, x, y)
                    true
                }
                else -> false
            }
        }
    }

    override fun canGoBack(): Boolean {
        return webView?.canGoBack() ?: false
    }

    override fun goBack() {
        webView?.goBack()
    }

    override fun reload() {
        webView?.reload()
    }

    override fun onFilePicked(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return
        }
        webView?.onFilePicked(data)
    }

    override fun onResume() {
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
    }

    override fun onUpdateAdblockSetting(newState: Boolean) {
        webView?.onUpdateAdblockSetting(newState)
    }

    override fun hideFullscreenView() {
        webView?.hideCustomView()
    }

    override fun togglePlayback() {
        webView?.evaluateJavascript("tvBroTogglePlayback()", null)
    }

    override fun stopPlayback() {
        webView?.evaluateJavascript("tvBroStopPlayback()", null)
    }

    override fun rewind() {
        webView?.evaluateJavascript("tvBroRewind()", null)
    }

    override fun fastForward() {
        webView?.evaluateJavascript("tvBroFastForward()", null)
    }

    override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        return webView?.renderThumbnail(bitmap)
    }

    override fun onAttachToWindow(callback: WebEngineWindowProviderCallback, parent: ViewGroup) {
        this.callback = callback
        if (webView == null) {
            throw IllegalStateException("WebView is null")
        }
        this.viewParent = parent as CursorLayout
        parent.removeAllViews()
        parent.addView(webView)
        viewParent?.cursorDrawerDelegate?.callback = this
        viewParent?.cursorDrawerDelegate?.innerScroller = null
        viewParent?.cursorDrawerDelegate?.innerScrollCallback = innerScrollCallback
        onResume()
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        onPause()
        (webView?.parent as? ViewGroup)?.removeView(webView)
        callback = null
        if (completely) {
            webView = null
        }
    }

    override fun trimMemory() {
        val webView = webView
        if (webView != null && !webView.isAttachedToWindow) {
            this.webView = null
            //free the page's memory now instead of waiting for the garbage collector
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (e: Exception) {
                Log.e("WebViewWebEngine", "trimMemory", e)
            }
        }
    }

    override fun onPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        val isGeolocationPermissionRequest = permissionsRequests[requestCode] ?: return false
        permissionsRequests.remove(requestCode)
        if (grantResults.isEmpty()) return true
        webView?.onPermissionsResult(permissions, grantResults, isGeolocationPermissionRequest)
        return true
    }

    override fun onLongPress(x: Int, y: Int) {
        val wv = webView ?: return
        //Lumen: select & copy text, paste into fields (the page script finds what is under the pointer)
        wv.evaluateJavascript("window.__lumenLongPress ? window.__lumenLongPress($x, $y, ${wv.width}) : null") { res ->
            val json = try {
                if (res == null || res == "null") null else org.json.JSONArray("[$res]").getString(0)
            } catch (e: Exception) { null }
            if (json != null && callback?.onPageLongPress(this, json, x, y) == true) return@evaluateJavascript
            oldLongPress(x, y)
        }
    }

    private fun oldLongPress(x: Int, y: Int) {
        webView?.let {
            it.evaluateJavascript(Scripts.LONG_PRESS_SCRIPT) { href ->
                val linkUrl = if (href == "null") null else href
                webViewCallback.onContextMenu(
                    it.currentOriginalUrl.toString(),
                    linkUrl, x, y
                )
            }
        }
    }

    override fun isVirtualCursorMode(): Boolean {
        return viewParent?.cursorEnabled ?: true
    }

    override fun setVirtualCursorMode(enabled: Boolean) {
        viewParent?.cursorEnabled = enabled
        if (enabled) {
            viewParent?.cursorDrawerDelegate?.animateAppearing()
        }
        webView?.setVirtualCursorMode(enabled)
    }

    override fun getCursorDrawerDelegate(): CursorDrawerDelegate? {
        return viewParent?.cursorDrawerDelegate
    }

    /** Lumen: scrolls boxes inside the page (submenus, lists) with the remote's cursor. */
    private val innerScrollCallback = object : CursorDrawerDelegate.InnerScrollCallback {
        //a busy page answers late: don't queue up more probes behind it
        private var probeSent = 0L

        override fun probeInnerScroller(fx: Float, fy: Float) {
            val wv = webView ?: return
            val now = android.os.SystemClock.uptimeMillis()
            if (probeSent != 0L && now - probeSent < 1500) return
            probeSent = now
            wv.evaluateJavascript("window.__lumenScrollProbe?__lumenScrollProbe($fx,$fy):''") {
                probeSent = 0L
                applyInnerScroller(it)
            }
        }

        override fun scrollInnerScroller(fdx: Float, fdy: Float) {
            val wv = webView ?: return
            wv.evaluateJavascript("window.__lumenInnerScroll?__lumenInnerScroll($fdx,$fdy):''") { applyInnerScroller(it) }
        }
    }

    private fun applyInnerScroller(result: String?) {
        val delegate = viewParent?.cursorDrawerDelegate ?: return
        if (delegate.innerScrollCallback !== innerScrollCallback) return //another tab is shown now
        val surface = delegate.surface
        val json = try {
            //evaluateJavascript returns the string JSON-encoded
            val str = if (result == null || result == "null") "" else org.json.JSONTokener(result).nextValue() as? String ?: ""
            if (str.isEmpty()) null else org.json.JSONObject(str)
        } catch (e: Exception) {
            null
        }
        if (json == null || surface.width <= 0) {
            delegate.innerScroller = null
            return
        }
        val w = surface.width.toFloat()
        val h = surface.height.toFloat()
        val rect = android.graphics.RectF(
            (json.optDouble("l") * w).toFloat(), (json.optDouble("t") * h).toFloat(),
            (json.optDouble("r") * w).toFloat(), (json.optDouble("b") * h).toFloat())
        val old = delegate.innerScroller
        if (old != null && old.rect == rect) {
            old.canUp = json.optBoolean("up"); old.canDown = json.optBoolean("down")
            old.canLeft = json.optBoolean("left"); old.canRight = json.optBoolean("right")
        } else {
            delegate.innerScroller = CursorDrawerDelegate.InnerScroller(rect,
                json.optBoolean("up"), json.optBoolean("down"), json.optBoolean("left"), json.optBoolean("right"))
        }
    }

    private val webViewCallback = object : WebViewEx.Callback {
        override fun onPageScrolled(y: Int, dy: Int) {
            callback?.onPageScrolled(y, dy)
        }

        override fun onPullToRefresh(distance: Float, released: Boolean) {
            callback?.onPullToRefresh(distance, released)
        }

        override fun onUrlChanged(url: String) {
            callback?.onUrlChanged(url)
        }

        override fun onRenderProcessGone(url: String?) {
            //the dead WebView must not be used again: drop it, a new one is made when needed
            val dead = webView ?: return
            val cb = callback
            (dead.parent as? ViewGroup)?.removeView(dead)
            webView = null
            try {
                dead.destroy()
            } catch (e: Exception) {
                Log.e("WebViewWebEngine", "destroy after crash", e)
            }
            cb?.onRenderProcessGone(url)
        }

        override fun getActivity(): Activity? {
            return callback?.getActivity()
        }

        override fun onOpenInNewTabRequested(url: String) {
            callback?.onOpenInNewTabRequested(url, true)
        }

        override fun onDownloadRequested(url: String) {
            callback?.onDownloadRequested(url)
        }

        override fun onThumbnailError() {
            //nop for now
        }

        override fun onShowCustomView(view: View) {
            callback?.onPrepareForFullscreen()
            webView?.visibility = View.GONE
            viewParent?.apply {
                addView(view)
            }
            fullScreenView = view
        }

        override fun onHideCustomView() {
            if (fullScreenView != null) {
                viewParent?.removeView(fullScreenView)
                fullScreenView = null
            }
            webView?.visibility = View.VISIBLE
            callback?.onExitFullscreen()
        }

        override fun onProgressChanged(newProgress: Int) {
            callback?.onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(title: String) {
            callback?.onReceivedTitle(title)
        }

        override fun requestPermissions(array: Array<String>, geo: Boolean) {
            val requestCode = callback?.requestPermissions(array) ?: return
            permissionsRequests[requestCode] = geo
        }

        override fun onShowFileChooser(intent: Intent): Boolean {
            return callback?.onShowFileChooser(intent) ?: false
        }

        override fun onReceivedIcon(icon: Bitmap) {
            callback?.onReceivedIcon(icon)
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            return callback?.shouldOverrideUrlLoading(url) ?: false
        }

        override fun onPageStarted(url: String?) {
            viewParent?.cursorDrawerDelegate?.let { if (it.innerScrollCallback === innerScrollCallback) it.innerScroller = null }
            callback?.onPageStarted(url)
        }

        override fun onPageFinished(url: String?) {
            callback?.onPageFinished(url)
        }

        override fun onPageCertificateError(url: String?) {
            callback?.onPageCertificateError(url)
        }

        override fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean {
            return callback?.isAd(request.url, request.requestHeaders["Accept"], baseUri) ?: false
        }

        override fun isAdBlockingEnabled(): Boolean {
            return callback?.isAdBlockingEnabled() ?: false
        }

        override fun isDialogsBlockingEnabled(): Boolean {
            return callback?.isDialogsBlockingEnabled() ?: false
        }

        override fun onBlockedAd(url: Uri) {
            callback?.onBlockedAd(url.toString())
        }

        override fun onBlockedDialog(newTab: Boolean) {
            callback?.onBlockedDialog(newTab)
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): WebViewEx? {
            return callback?.onCreateWindow(dialog, userGesture) as? WebViewEx
        }

        override fun closeWindow(window: WebView) {
            callback?.closeWindow(window)
        }

        override fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String?, contentLength: Long) {
            callback?.onDownloadRequested(url, userAgent, contentDisposition, mimetype, contentLength)
        }

        override fun onScaleChanged(oldScale: Float, newScale: Float) {
            callback?.onScaleChanged(oldScale, newScale)
        }

        override fun onCopyTextToClipboardRequested(url: String) {
            callback?.onCopyTextToClipboardRequested(url)
        }

        override fun onShareUrlRequested(url: String) {
            callback?.onShareUrlRequested(url)
        }

        override fun onOpenInExternalAppRequested(url: String) {
            callback?.onOpenInExternalAppRequested(url)
        }

        override fun onVisited(url: String) {
            callback?.onVisited(url)
        }

        override fun onContextMenu(baseUrl:String?, href: String?, x: Int, y: Int) {
            callback?.onContextMenu(
                viewParent!!.cursorDrawerDelegate,
                baseUri = baseUrl,
                linkUri = href,
                srcUri = null,
                title = null,
                altText = null,
                textContent = null,
                x = x,
                y = y
            )
        }
    }

    companion object {
        init {
            WebEngineFactory.registerProvider(WebEngineProvider("WebView", object : WebEngineProviderCallback {
                override suspend fun initialize(context: Context, webViewContainer: CursorLayout) {

                }

                override fun createWebEngine(tab: WebTabState): WebEngine {
                    return WebViewWebEngine(tab)
                }

                override suspend fun clearCache(ctx: Context) {
                    WebView(ctx).clearCache(true)
                }

                override fun onThemeSettingUpdated(value: Config.Theme) {
                    //nop
                }

                override fun getWebEngineVersionString(): String {
                    val webViewPackage = WebViewCompat.getCurrentWebViewPackage(AppContext.get())
                    return (webViewPackage?.packageName ?: "unknown") + ":" + (webViewPackage?.versionName ?: "unknown")
                }
            }))
        }
    }
}