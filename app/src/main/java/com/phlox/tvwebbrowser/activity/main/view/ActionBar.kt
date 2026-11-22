package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View.OnFocusChangeListener
import android.view.View.OnKeyListener
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.downloads.ActiveDownloadsModel
import com.phlox.tvwebbrowser.databinding.ViewActionbarBinding
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository

class ActionBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val vb = ViewActionbarBinding.inflate( LayoutInflater.from(context),this)
    var callback: Callback? = null
    private var downloadAnimation: Animation? = null
    private var downloadsModel = ActiveModelsRepository.get(ActiveDownloadsModel::class, context)
    private var extendedAddressBarMode = false

    interface Callback {
        fun closeWindow()
        fun showDownloads()
        fun showFavorites()
        fun showHistory()
        fun showSettings()
        fun initiateVoiceSearch()
        fun search(text: String)
        fun onExtendedAddressBarMode()
        fun onUrlInputDone()
        fun toggleIncognitoMode()
        fun goHome()
        /** Lumen: the site-data button before the address. */
        fun showSiteData() {}
        /** Wisp: the ⋮ button on the bottom bar. */
        fun showPhoneMenu() {}
        /** Wisp: the tab counter on the bar. */
        fun showPhoneTabs() {}
        /** Wisp: the + next to the address. */
        fun showPhoneNewTab() {}
        /** Wisp: the search engine button (left of the address on the start page and while typing). */
        fun showPhoneEngines() {}
        /** Wisp: icon of the search engine in use. */
        fun phoneEngineIcon(): android.graphics.Bitmap? = null
        /** Wisp: reload button on the bar ([stop]: the page is still loading, so stop it instead). */
        fun phoneReload(stop: Boolean) {}
    }

    /** Wisp (phone): one bottom bar - site button, address, tab counter, menu. */
    private val phone = com.phlox.tvwebbrowser.Brand.phone
    /** The full address; on the phone the field shows only the site's name until it is tapped. */
    private var fullAddress = ""

    /** Wisp: number on the tab counter. */
    fun setTabCount(count: Int) {
        vb.tvTabCount.text = if (count > 99) ":D" else count.toString()
    }

    /** Wisp: the address as shown while not editing: "example.com" (http:// addresses keep their scheme). */
    private fun shortAddress(url: String): String {
        if (url.isEmpty()) return ""
        //pages the browser makes itself (crash or error pages, about:blank) have no address to show
        if (url.startsWith("data:") || url.startsWith("about:") || url.startsWith("internal:")) return ""
        //Wisp: a page saved offline
        if (url.startsWith("file://")) return context.getString(R.string.wisp_saved_page)
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: return url
            val name = host.removePrefix("www.")
            //host plus where you are on the site (path and query), readable: no https://, no %20
            val path = (uri.path ?: "").let { if (it == "/") "" else it }
            val query = uri.query?.let { "?$it" } ?: ""
            val shown = name + path + query
            if ("http".equals(uri.scheme, ignoreCase = true)) "http://$shown" else shown
        } catch (e: Exception) {
            url
        }
    }

    private fun applyPhoneLayout() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        setPadding(px(6), px(6), px(4), px(6))
        val iconColor = androidx.core.content.ContextCompat.getColor(context, R.color.wisp_icon)
        for (b in listOf<android.view.View>(vb.ibHome, vb.ibHistory, vb.ibFavorites, vb.ibDownloads, vb.ibIncognito, vb.ibSettings)) {
            b.visibility = GONE
        }
        vb.tvTabCount.visibility = VISIBLE
        vb.ibNewTab.visibility = VISIBLE
        vb.ibReload.visibility = VISIBLE
        vb.ibReload.setColorFilter(iconColor)
        vb.ibReload.setOnClickListener { callback?.phoneReload(pageLoading) }
        vb.ibNewTab.setColorFilter(iconColor)
        vb.ibNewTab.setOnClickListener { callback?.showPhoneNewTab() }
        vb.tvTabCount.setTextColor(iconColor)
        vb.tvTabCount.setOnClickListener { callback?.showPhoneTabs() }
        vb.ibMenu.setImageResource(R.drawable.ic_wisp_more)
        vb.ibMenu.setColorFilter(iconColor)
        vb.ibMenu.contentDescription = context.getString(R.string.wisp_menu)
        vb.ibMenu.setOnClickListener { callback?.showPhoneMenu() }
        //touch screens: one tap clicks (the TV layout lets the menu button take the focus first,
        //which made the first tap do nothing)
        for (b in listOf<android.view.View>(vb.ibMenu, vb.ibSiteData, vb.tvTabCount, vb.ibNewTab, vb.ibReload)) {
            b.isFocusableInTouchMode = false
        }
        vb.ibSiteData.setImageResource(R.drawable.ic_wisp_shield)
        vb.ibSiteData.setColorFilter(iconColor)
        (vb.flUrl.layoutParams as android.view.ViewGroup.MarginLayoutParams).let {
            it.marginStart = px(4)
            it.marginEnd = px(4)
            vb.flUrl.layoutParams = it
        }
        vb.flUrl.setBackgroundResource(R.drawable.wisp_url_pill)
        vb.etUrl.background = null
        vb.etUrl.setPadding(px(16), px(10), px(16), px(10))
        vb.etUrl.textSize = 15f
        vb.etUrl.isSingleLine = true
        vb.etUrl.setHint(R.string.wisp_url_hint)
        vb.etUrl.setHintTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.wisp_text_secondary))
        vb.etUrl.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    /** The site's icon last given (Wisp shows it again when it leaves search-engine mode). */
    private var siteIcon: android.graphics.Bitmap? = null

    /**
     * Wisp: the button left of the address is the search engine (tap: choose another) on the start
     * page and while typing, and the site's icon (tap: site info) on a web page.
     */
    fun refreshSiteButton() {
        if (!phone) return
        val engineMode = fullAddress.isEmpty() || vb.etUrl.hasFocus()
        if (engineMode) {
            val icon = callback?.phoneEngineIcon()
            if (icon != null) {
                vb.ibSiteData.clearColorFilter()
                vb.ibSiteData.setImageBitmap(icon)
            } else {
                vb.ibSiteData.setImageResource(R.drawable.ic_wisp_find)
                vb.ibSiteData.setColorFilter(androidx.core.content.ContextCompat.getColor(context, R.color.wisp_icon))
            }
            vb.ibSiteData.setPadding(dp(10), dp(10), dp(10), dp(10))
            vb.ibSiteData.contentDescription = context.getString(R.string.wisp_search_engine)
            vb.ibSiteData.setOnClickListener { callback?.showPhoneEngines() }
        } else {
            vb.ibSiteData.contentDescription = context.getString(R.string.lumen_site_data)
            vb.ibSiteData.setOnClickListener { callback?.showSiteData() }
            showSiteIcon(siteIcon)
        }
    }

    /** Wisp: the page is loading (the reload button stops it) or not. */
    fun setLoading(loading: Boolean) {
        if (!phone || pageLoading == loading) return
        pageLoading = loading
        vb.ibReload.setImageResource(if (loading) R.drawable.ic_close_grey_900_24dp else R.drawable.ic_refresh_grey_900_24dp)
        vb.ibReload.contentDescription = context.getString(if (loading) R.string.wisp_stop else R.string.wisp_reload)
    }
    private var pageLoading = false

    /** The current site's icon on the site-data button (null: the generic icon). */
    fun setSiteIcon(icon: android.graphics.Bitmap?) {
        siteIcon = icon
        if (phone) refreshSiteButton() else showSiteIcon(icon)
    }

    private fun showSiteIcon(icon: android.graphics.Bitmap?) {
        if (icon != null) {
            vb.ibSiteData.clearColorFilter()
            vb.ibSiteData.setImageBitmap(icon)
            vb.ibSiteData.setPadding(dp(9), dp(9), dp(9), dp(9))
        } else {
            vb.ibSiteData.setImageResource(if (phone) R.drawable.ic_wisp_shield else R.drawable.ic_site_data)
            if (phone) vb.ibSiteData.setColorFilter(androidx.core.content.ContextCompat.getColor(context, R.color.wisp_icon))
            vb.ibSiteData.setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val etUrlFocusChangeListener = OnFocusChangeListener { _, focused ->
        if (!focused) {
            dismissExtendedAddressBarMode()
            if (phone) vb.etUrl.setText(shortAddress(fullAddress))
        }
        if (phone) refreshSiteButton()
        if (focused) {
            if (phone) vb.etUrl.setText(fullAddress)
            enterExtendedAddressBarMode()

            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_IMPLICIT)
            postDelayed(//workaround an android TV bug
                {
                    vb.etUrl.selectAll()
                }, 500)
        }
    }

    private val etUrlKeyListener = OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
                    keepTypedAddress()
                    callback?.search(vb.etUrl.text.toString())
                    dismissExtendedAddressBarMode()
                    callback?.onUrlInputDone()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    init {
        init()
    }

    fun init() {
        orientation = HORIZONTAL
        //Lumen: address bar left, evenly spaced same-size buttons right, all centred vertically
        gravity = android.view.Gravity.CENTER_VERTICAL
        val dp = resources.displayMetrics.density
        setPadding(0, (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())

        if (isInEditMode) return

        val incognitoMode = AppContext.provideConfig().incognitoMode

        vb.ibMenu.setOnClickListener { callback?.closeWindow() }
        vb.ibDownloads.setOnClickListener { callback?.showDownloads() }
        vb.ibFavorites.setOnClickListener { callback?.showFavorites() }
        vb.ibHistory.setOnClickListener { callback?.showHistory() }
        vb.ibHome.setOnClickListener { callback?.goHome() }
        vb.ibSiteData.setOnClickListener { callback?.showSiteData() }
        vb.ibIncognito.setOnClickListener { callback?.toggleIncognitoMode() }
        vb.ibSettings.setOnClickListener { callback?.showSettings() }

        //Lumen: no voice search / microphone

        vb.ibIncognito.isChecked = incognitoMode

        vb.etUrl.onFocusChangeListener = etUrlFocusChangeListener

        if (phone) applyPhoneLayout()

        vb.etUrl.setOnKeyListener(etUrlKeyListener)
        //on-screen keyboards (tablets) send an editor action instead of an Enter key
        vb.etUrl.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || isEnterKey) {
                if (isEnterKey) return@setOnEditorActionListener false //handled by the key listener
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
                keepTypedAddress()
                callback?.search(vb.etUrl.text.toString())
                dismissExtendedAddressBarMode()
                callback?.onUrlInputDone()
                true
            } else false
        }


        downloadsModel.activeDownloads.subscribe(context as AppCompatActivity) {
            if (it.isNotEmpty()) {
                if (downloadAnimation == null) {
                    downloadAnimation = AnimationUtils.loadAnimation(context, R.anim.infinite_fadeinout_anim)
                    vb.ibDownloads.startAnimation(downloadAnimation)
                }
            } else {
                downloadAnimation?.apply {
                    this.reset()
                    vb.ibDownloads.clearAnimation()
                    downloadAnimation = null
                }
            }
        }
    }

    fun setAddressBoxText(text: String) {
        val address = if (text == Config.HOME_PAGE_URL || text.startsWith(Config.HOME_PAGE_URL)) "" else text
        //Wisp: what was just typed stays in the field until the new page starts (the old page -
        //often the start page, which has no address - would otherwise blank it for a few seconds)
        if (phone && typedAddress != null) {
            val recent = android.os.SystemClock.uptimeMillis() - typedAt < 15_000
            if (recent && (address.isEmpty() || address == addressBeforeTyping)) return
            typedAddress = null
        }
        fullAddress = address
        vb.etUrl.setText(if (phone && !vb.etUrl.hasFocus()) shortAddress(fullAddress) else fullAddress)
        if (phone) refreshSiteButton()
    }

    private var typedAddress: String? = null
    private var addressBeforeTyping: String? = null
    private var typedAt = 0L

    /** The typed address didn't open a page (a download): the field may show the page's address again. */
    fun dropTypedAddress() {
        typedAddress = null
        addressBeforeTyping = null
    }

    private fun keepTypedAddress() {
        if (!phone) return
        val t = vb.etUrl.text.toString().trim()
        if (t.isEmpty()) return
        addressBeforeTyping = fullAddress
        typedAddress = t
        typedAt = android.os.SystemClock.uptimeMillis()
        fullAddress = t
    }

    fun setAddressBoxTextColor(color: Int) {
        vb.etUrl.setTextColor(color)
    }

    /** Buttons hidden while the address is edited, with how they were shown before. */
    private val hiddenWhileEditing = HashMap<android.view.View, Int>()

    private fun enterExtendedAddressBarMode() {
        if (extendedAddressBarMode) return
        extendedAddressBarMode = true
        hiddenWhileEditing.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if ((child is ImageButton && !(phone && child == vb.ibSiteData)) || child == vb.tvTabCount) {
                hiddenWhileEditing[child] = child.visibility
                child.visibility = GONE
            }
        }
        TransitionManager.beginDelayedTransition(this)
        callback?.onExtendedAddressBarMode()
    }

    fun dismissExtendedAddressBarMode() {
        if (!extendedAddressBarMode) return
        extendedAddressBarMode = false
        //back as they were (Wisp keeps most buttons hidden: they are in its menu)
        for ((child, visibility) in hiddenWhileEditing) {
            child.visibility = visibility
        }
        hiddenWhileEditing.clear()
    }

    fun catchFocus() {
        vb.ibHistory.requestFocus()
    }

    /** Diagnostics: what the bar shows (the tab button and the address the bar holds). */
    fun shownTabCount(): String = vb.tvTabCount.text?.toString() ?: ""
    fun heldAddress(): String = fullAddress

    fun isAddressEditing(): Boolean = vb.etUrl.hasFocus() || hiddenWhileEditing.isNotEmpty()

    /** Wisp: typing an address ended without Go: the field shows the page's address again, buttons come back. */
    fun endAddressEditing() {
        if (!phone) return
        if (vb.etUrl.hasFocus()) {
            //focus goes to the bar itself (clearFocus alone can hand it straight back to the
            //address field, the first thing on screen that takes focus)
            isFocusableInTouchMode = true
            requestFocus()
            if (vb.etUrl.hasFocus()) vb.etUrl.clearFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
        }
        dismissExtendedAddressBarMode()
        vb.etUrl.setText(shortAddress(fullAddress))
        refreshSiteButton()
    }

    fun focusAddressBar() {
        vb.etUrl.requestFocus()
    }
}