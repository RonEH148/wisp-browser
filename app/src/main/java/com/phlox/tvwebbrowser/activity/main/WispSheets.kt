package com.phlox.tvwebbrowser.activity.main

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.R

/**
 * Wisp (phone build): the ⋮ menu, the tab list and site info. Built in code so the TV build
 * carries no extra layouts.
 */
object WispSheets {

    /** A button in the menu. [active]: a switch that is on (shown in the accent colour). */
    class Action(
        val icon: Int,
        val label: String,
        val active: Boolean = false,
        val enabled: Boolean = true,
        val badge: String? = null,
        val onClick: () -> Unit
    )


    private class Palette(a: Activity) {
        val sheet = ContextCompat.getColor(a, R.color.wisp_sheet)
        val text = ContextCompat.getColor(a, R.color.wisp_text)
        val secondary = ContextCompat.getColor(a, R.color.wisp_text_secondary)
        val icon = ContextCompat.getColor(a, R.color.wisp_icon)
        val accent = ContextCompat.getColor(a, R.color.wisp_accent)
        val accentSoft = ContextCompat.getColor(a, R.color.wisp_accent_soft)
        val pill = ContextCompat.getColor(a, R.color.wisp_pill)
        val divider = ContextCompat.getColor(a, R.color.wisp_divider)
        val card = ContextCompat.getColor(a, R.color.wisp_card)
        val onAccent = ContextCompat.getColor(a, R.color.wisp_on_accent)
        val toggleOff = ContextCompat.getColor(a, R.color.wisp_toggle_off)
    }

    private fun dp(a: Activity, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.resources.displayMetrics).toInt()

    private fun rounded(color: Int, radius: Float, topOnly: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            if (topOnly) cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            else cornerRadius = radius
        }

    private fun ripple(a: Activity): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getDrawable(a, tv.resourceId) else null
    }

    /**
     * Holds a sheet's content and lets a downward swipe close it: the sheet follows the finger and
     * closes when pulled far or fast enough, otherwise it springs back. A swipe only starts when
     * the list inside is scrolled to its top.
     */
    private class SwipeDownLayout(a: Activity, val onClose: () -> Unit) : FrameLayout(a) {
        private val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var velocity: android.view.VelocityTracker? = null

        private fun firstScroller(v: View): View? {
            if (v is ScrollView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) firstScroller(v.getChildAt(i))?.let { return it }
            return null
        }

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; dragging = false
                    velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
                    velocity?.addMovement(ev)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(ev)
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    val atTop = firstScroller(this)?.canScrollVertically(-1) != true
                    if (dy > slop && dy > Math.abs(dx) * 1.5f && atTop) {
                        dragging = true
                        downY = ev.rawY
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            val child = getChildAt(0) ?: return false
            velocity?.addMovement(ev)
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> return true
                android.view.MotionEvent.ACTION_MOVE -> if (dragging) {
                    child.translationY = (ev.rawY - downY).coerceAtLeast(0f)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        val v = velocity?.yVelocity ?: 0f
                        if (child.translationY > child.height * 0.25f || v > 1800f) {
                            child.animate().translationY(child.height.toFloat()).setDuration(150)
                                .withEndAction { onClose() }.start()
                        } else {
                            child.animate().translationY(0f).setDuration(150).start()
                        }
                    }
                    dragging = false
                    velocity?.recycle(); velocity = null
                }
            }
            return true
        }
    }

    /** A dialog that slides up from the bottom of the screen, with rounded top corners. */
    private fun sheet(a: Activity, content: View): Dialog {
        val p = Palette(a)
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(p.sheet, dp(a, 22f).toFloat(), topOnly = true)
        root.setPadding(0, dp(a, 10f), 0, dp(a, 14f))
        val handle = View(a)
        handle.background = rounded(p.divider, dp(a, 2f).toFloat())
        val hlp = LinearLayout.LayoutParams(dp(a, 36f), dp(a, 4f))
        hlp.gravity = Gravity.CENTER_HORIZONTAL
        hlp.bottomMargin = dp(a, 10f)
        root.addView(handle, hlp)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val holder = SwipeDownLayout(a) { d.dismiss() }
        holder.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        //Android 15+: the sheet's last rows stay above the navigation bar
        val basePad = root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(holder) { _, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, basePad + nav.bottom)
            insets
        }
        d.setContentView(holder)
        d.setCanceledOnTouchOutside(true)
        //landscape / tablets: not wider than a phone held upright, centred at the bottom
        val metrics = a.resources.displayMetrics
        val maxW = dp(a, 520f)
        val width = if (metrics.widthPixels > maxW) maxW else ViewGroup.LayoutParams.MATCH_PARENT
        //diagnostics: a sheet must fit the screen (and not be wider than a phone) with its last row above
        //the navigation bar
        holder.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                val log = com.phlox.tvwebbrowser.sofa.WispLog
                val w = r - l
                val screenW = a.resources.displayMetrics.widthPixels
                if (w > screenW + 2 || w > maxW + 2) log.mismatch("UI: sheet too wide", "sheet $w px, screen $screenW px, limit $maxW px")
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val screenH = v.rootView.resources.displayMetrics.heightPixels
                if (loc[1] < 0) log.mismatch("UI: sheet too tall", "sheet starts ${loc[1]} px above the screen")
                if (b - t <= 0) log.mismatch("UI: empty sheet", "sheet has no height")
                if (screenH in 1 until loc[1]) log.mismatch("UI: sheet off screen", "sheet at y=${loc[1]}")
            }
        })
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.35f)
            w.attributes = w.attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }
        return d
    }

    private fun tinted(a: Activity, res: Int, color: Int): ImageView {
        val iv = ImageView(a)
        iv.setImageResource(res)
        iv.setColorFilter(color)
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        return iv
    }

    /** A full-width row: icon, title (and a second line), and on the right a switch, a badge or nothing. */
    class Row(
        val icon: Int,
        val title: String,
        val subtitle: String? = null,
        /** non-null: the row is a switch in this state; tapping it calls [onClick] with the new state */
        val switchOn: Boolean? = null,
        val badge: String? = null,
        val enabled: Boolean = true,
        val danger: Boolean = false,
        val onClick: (Boolean) -> Unit
    )

    private fun rowView(a: Activity, p: Palette, r: Row, dismiss: () -> Unit): View {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(a, 50f)
        row.setPadding(dp(a, 18f), dp(a, 6f), dp(a, 16f), dp(a, 6f))
        row.background = ripple(a)
        row.isEnabled = r.enabled
        row.alpha = if (r.enabled) 1f else 0.38f
        val color = if (r.danger) DANGER else p.icon
        row.addView(tinted(a, r.icon, color), LinearLayout.LayoutParams(dp(a, 22f), dp(a, 22f)))
        val texts = LinearLayout(a)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(a, 18f), 0, dp(a, 10f), 0)
        val t = TextView(a)
        t.text = r.title
        t.textSize = 15f
        t.setTextColor(if (r.danger) color else p.text)
        texts.addView(t)
        if (!r.subtitle.isNullOrEmpty()) {
            val st = TextView(a)
            st.text = r.subtitle
            st.textSize = 12.5f
            st.setTextColor(p.secondary)
            texts.addView(st)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var sw: android.widget.Switch? = null
        if (r.switchOn != null) {
            val s = android.widget.Switch(a)
            s.isChecked = r.switchOn
            s.isClickable = false
            s.isFocusable = false
            tintSwitch(s, p)
            row.addView(s)
            sw = s
        } else if (!r.badge.isNullOrEmpty()) {
            val b = TextView(a)
            b.text = r.badge
            b.textSize = 11f
            b.setTypeface(b.typeface, Typeface.BOLD)
            b.setTextColor(p.accent)
            b.background = rounded(p.accentSoft, dp(a, 10f).toFloat())
            b.setPadding(dp(a, 8f), dp(a, 2f), dp(a, 8f), dp(a, 2f))
            row.addView(b)
        }
        val theSwitch = sw
        row.setOnClickListener {
            if (theSwitch != null) {
                theSwitch.isChecked = !theSwitch.isChecked
                r.onClick(theSwitch.isChecked)
            } else {
                dismiss()
                r.onClick(true)
            }
        }
        return row
    }

    private const val DANGER = 0xFFD84343.toInt()

    /** Accent-coloured switch (the platform one has the system's colours otherwise). */
    fun tintSwitch(sw: android.widget.Switch, a: Activity) = tintSwitch(sw, Palette(a))

    private fun tintSwitch(sw: android.widget.Switch, p: Palette) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        //neutral: the "on" switch is simply darker (light theme) / lighter (dark theme)
        sw.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(p.accent, p.secondary))
        sw.trackTintList = android.content.res.ColorStateList(states,
            intArrayOf((p.accent and 0x00FFFFFF) or 0x66000000, p.divider))
    }

    /**
     * Site info, like Chrome's page info: the site's name, whether the connection is secure, its
     * settings as switches and buttons to delete its data.
     */
    fun showSiteInfo(a: Activity, site: String, secure: Boolean, rows: List<Row>): Dialog {
        val p = Palette(a)
        lateinit var dialog: Dialog
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(a)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(a, 22f), 0, dp(a, 22f), dp(a, 10f))
        val t = TextView(a)
        t.text = site
        t.textSize = 19f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextColor(p.text)
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.MIDDLE
        head.addView(t)
        val sec = TextView(a)
        sec.text = a.getString(if (secure) R.string.wisp_site_secure else R.string.wisp_site_not_secure)
        sec.textSize = 13f
        sec.setTextColor(if (secure) p.secondary else DANGER)
        sec.setPadding(0, dp(a, 2f), 0, 0)
        head.addView(sec)
        box.addView(head)
        for (r in rows) box.addView(rowView(a, p, r) { dialog.dismiss() })
        dialog = sheet(a, box)
        dialog.show()
        return dialog
    }

    // ---------------- the ⋮ menu ----------------

    /** A row in the menu's list. [toggle]: shows a small switch in this state. */
    class MenuItem(
        val icon: Int,
        val title: String,
        val subtitle: String? = null,
        val toggle: Boolean? = null,
        val badge: String? = null,
        val enabled: Boolean = true,
        val danger: Boolean = false,
        /** shown instead of [icon], untinted (e.g. a search engine's logo) */
        val iconBitmap: Bitmap? = null,
        val onClick: () -> Unit
    )

    private val FONT: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val FONT_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun divider(a: Activity, p: Palette): View {
        val v = View(a)
        v.setBackgroundColor(p.divider)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(a, 0.7f)))
        lp.setMargins(dp(a, 20f), dp(a, 6f), dp(a, 20f), dp(a, 6f))
        v.layoutParams = lp
        return v
    }

    /** One plain row: icon, title (and a second line), and on the right a switch or a count. */
    private fun menuRow(a: Activity, p: Palette, item: MenuItem, dismiss: () -> Unit, extra: View? = null): LinearLayout {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(a, 48f)
        row.setPadding(dp(a, 22f), dp(a, 4f), dp(a, 18f), dp(a, 4f))
        row.background = ripple(a)
        row.isEnabled = item.enabled
        row.alpha = if (item.enabled) 1f else 0.38f
        val iconView = if (item.iconBitmap != null) ImageView(a).also {
            it.setImageBitmap(item.iconBitmap)
            it.scaleType = ImageView.ScaleType.FIT_CENTER
        } else tinted(a, item.icon, p.icon)
        row.addView(iconView, LinearLayout.LayoutParams(dp(a, 22f), dp(a, 22f)))
        val texts = LinearLayout(a)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(a, 20f), 0, dp(a, 8f), 0)
        val t = TextView(a)
        t.text = item.title
        t.typeface = FONT
        t.textSize = 15f
        t.setTextColor(p.text)
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        texts.addView(t)
        if (!item.subtitle.isNullOrEmpty()) {
            val s = TextView(a)
            s.text = item.subtitle
            s.typeface = FONT
            s.textSize = 12f
            s.setTextColor(p.secondary)
            s.isSingleLine = true
            s.ellipsize = TextUtils.TruncateAt.END
            texts.addView(s)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (item.toggle != null) {
            row.addView(toggleView(a, p, item.toggle))
        } else if (!item.badge.isNullOrEmpty()) {
            val b = TextView(a)
            b.text = item.badge
            b.typeface = FONT
            b.textSize = 13f
            b.setTextColor(p.secondary)
            row.addView(b)
        }
        if (extra != null) row.addView(extra)
        row.setOnClickListener { dismiss(); item.onClick() }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return row
    }

    /**
     * A small on/off switch drawn by Wisp itself (the system one came out with a faint, nearly
     * invisible track): a pill with a white knob, dark when on and light grey when off.
     */
    private fun toggleView(a: Activity, p: Palette, on: Boolean): View {
        val w = dp(a, 38f)
        val h = dp(a, 22f)
        val box = FrameLayout(a)
        box.background = rounded(if (on) p.accent else p.toggleOff, h / 2f)
        val knob = View(a)
        knob.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) p.onAccent else Color.WHITE)
        }
        knob.elevation = dp(a, 1f).toFloat()
        val k = h - dp(a, 6f)
        val lp = FrameLayout.LayoutParams(k, k)
        lp.gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
        lp.marginStart = dp(a, 3f)
        lp.marginEnd = dp(a, 3f)
        box.addView(knob, lp)
        box.layoutParams = LinearLayout.LayoutParams(w, h)
        return box
    }

    /** A row of equal cells, icon above a small label (back / forward / ..., history / bookmarks / ...). */
    private fun cellRow(a: Activity, p: Palette, actions: List<Action>, dismiss: () -> Unit): LinearLayout {
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(a, 8f), 0, dp(a, 8f), 0)
        for (act in actions) {
            val cell = LinearLayout(a)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.setPadding(0, dp(a, 10f), 0, dp(a, 10f))
            cell.background = ripple(a)
            cell.isEnabled = act.enabled
            cell.alpha = if (act.enabled) 1f else 0.32f
            cell.contentDescription = act.label
            cell.addView(tinted(a, act.icon, p.icon), LinearLayout.LayoutParams(dp(a, 22f), dp(a, 22f)))
            val l = TextView(a)
            l.text = act.label
            l.typeface = FONT
            l.textSize = 11.5f
            l.setTextColor(p.secondary)
            l.isSingleLine = true
            l.ellipsize = TextUtils.TruncateAt.END
            l.setPadding(dp(a, 2f), dp(a, 6f), dp(a, 2f), 0)
            l.gravity = Gravity.CENTER_HORIZONTAL
            //centred under the icon (the label used to take the full width and sit on the left)
            cell.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cell.setOnClickListener { dismiss(); act.onClick() }
            bar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    /**
     * The ⋮ menu: a plain sheet. Back / forward / share / reload, then history / bookmarks /
     * downloads / passwords, then the page actions as a simple list ("More" opens the rest in
     * place), then settings and quit. One font, one icon colour, thin lines between the parts.
     */
    fun showMenu(a: Activity, title: String?, top: List<Action>, main: List<MenuItem>, more: List<MenuItem>,
                 tiles: List<Action>, settings: MenuItem, quit: MenuItem): Dialog {
        val p = Palette(a)
        lateinit var dialog: Dialog
        val dismiss = { dialog.dismiss() }
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL

        if (!title.isNullOrBlank()) {
            val t = TextView(a)
            t.text = title
            t.typeface = FONT_MEDIUM
            t.setTextColor(p.secondary)
            t.textSize = 12.5f
            t.gravity = Gravity.CENTER_HORIZONTAL
            t.isSingleLine = true
            t.ellipsize = TextUtils.TruncateAt.MIDDLE
            t.setPadding(dp(a, 22f), 0, dp(a, 22f), dp(a, 4f))
            box.addView(t)
        }
        box.addView(cellRow(a, p, top, dismiss))
        box.addView(divider(a, p))
        box.addView(cellRow(a, p, tiles, dismiss))
        box.addView(divider(a, p))

        val group = LinearLayout(a)
        group.orientation = LinearLayout.VERTICAL
        main.forEach { item -> group.addView(menuRow(a, p, item, dismiss)) }
        if (more.isNotEmpty()) {
            val moreRows = more.map { item -> menuRow(a, p, item, dismiss) }
            val chevron = tinted(a, R.drawable.ic_wisp_down, p.secondary)
            val moreRow = menuRow(a, p, MenuItem(R.drawable.ic_wisp_more, a.getString(R.string.wisp_more)) {}, {},
                extra = chevron)
            (chevron.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = dp(a, 20f); it.height = dp(a, 20f); chevron.layoutParams = it
            }
            moreRow.setOnClickListener {
                //"More" goes away and its items take its place
                val at = group.indexOfChild(moreRow)
                group.removeView(moreRow)
                moreRows.forEachIndexed { i, r -> group.addView(r, at + i) }
            }
            group.addView(moreRow)
        }
        box.addView(group)
        box.addView(divider(a, p))
        box.addView(menuRow(a, p, settings, dismiss))
        box.addView(menuRow(a, p, quit, dismiss))

        val maxH = (a.resources.displayMetrics.heightPixels * 0.85f).toInt()
        val scroll = object : ScrollView(a) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(box)
        dialog = sheet(a, scroll)
        dialog.show()
        return dialog
    }

    /**
     * A plain list sheet (e.g. long press on a link or an image): an optional heading with the
     * address, then groups of rows with a thin line between them.
     */
    fun showList(a: Activity, heading: String?, address: String?, groups: List<List<MenuItem>>): Dialog {
        val p = Palette(a)
        lateinit var dialog: Dialog
        val dismiss = { dialog.dismiss() }
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        if (!heading.isNullOrBlank() || !address.isNullOrBlank()) {
            val head = LinearLayout(a)
            head.orientation = LinearLayout.VERTICAL
            head.setPadding(dp(a, 22f), 0, dp(a, 22f), dp(a, 6f))
            if (!heading.isNullOrBlank()) {
                val t = TextView(a)
                t.text = heading
                t.typeface = FONT_MEDIUM
                t.textSize = 15f
                t.setTextColor(p.text)
                t.isSingleLine = true
                t.ellipsize = TextUtils.TruncateAt.END
                head.addView(t)
            }
            if (!address.isNullOrBlank()) {
                val u = TextView(a)
                u.text = address
                u.typeface = FONT
                u.textSize = 12.5f
                u.setTextColor(p.secondary)
                u.maxLines = 2
                u.ellipsize = TextUtils.TruncateAt.END
                u.setPadding(0, dp(a, 2f), 0, 0)
                head.addView(u)
            }
            box.addView(head)
            box.addView(divider(a, p))
        }
        groups.filter { it.isNotEmpty() }.forEachIndexed { i, g ->
            if (i > 0) box.addView(divider(a, p))
            g.forEach { box.addView(menuRow(a, p, it, dismiss)) }
        }
        val maxH = (a.resources.displayMetrics.heightPixels * 0.8f).toInt()
        val scroll = object : ScrollView(a) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(box)
        dialog = sheet(a, scroll)
        dialog.show()
        return dialog
    }

    // ---------------- tab grid ----------------

    class TabCard(val title: String, val address: String, val current: Boolean, val thumbnail: Bitmap?, val home: Boolean)

    /**
     * Tabs as a two-column grid of cards (page preview, site icon, title, ✕), like Fennec's tab
     * tray: bigger targets than a list, so the wrong tab isn't closed by accident.
     * Returns the dialog and a setter that fills in a tab's site icon (index, bitmap) once loaded.
     */
    fun showTabs(
        a: Activity,
        privateMode: Boolean,
        tabs: List<TabCard>,
        onPick: (Int) -> Unit,
        onClose: (Int) -> Unit,
        onNew: () -> Unit,
        onCloseAll: () -> Unit,
        onRecent: (() -> Unit)? = null
    ): Pair<Dialog, (Int, Bitmap?) -> Unit> {
        val p = Palette(a)
        lateinit var dialog: Dialog
        val root = FrameLayout(a)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL

        val header = LinearLayout(a)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(a, 20f), 0, dp(a, 8f), dp(a, 8f))
        val title = TextView(a)
        title.text = a.getString(if (privateMode) R.string.wisp_tabs_private_title else R.string.wisp_tabs_title,
            tabs.count { !it.home }) //same count as the tab button: start-page tabs don't count
        title.textSize = 19f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(p.text)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onRecent != null) {
            val recent = tinted(a, R.drawable.ic_history_grey_900_36dp, p.icon)
            recent.contentDescription = a.getString(R.string.wisp_recently_closed)
            recent.background = ripple(a)
            recent.setPadding(dp(a, 9f), dp(a, 9f), dp(a, 9f), dp(a, 9f))
            recent.setOnClickListener { dialog.dismiss(); onRecent() }
            header.addView(recent, LinearLayout.LayoutParams(dp(a, 40f), dp(a, 40f)))
        }
        if (tabs.size > 1) {
            val closeAll = TextView(a)
            closeAll.text = a.getString(R.string.wisp_close_all_tabs)
            closeAll.textSize = 14f
            closeAll.setTextColor(p.accent)
            closeAll.background = ripple(a)
            closeAll.setPadding(dp(a, 12f), dp(a, 8f), dp(a, 12f), dp(a, 8f))
            closeAll.setOnClickListener { dialog.dismiss(); onCloseAll() }
            header.addView(closeAll)
        }
        box.addView(header)

        val grid = LinearLayout(a)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(a, 10f), 0, dp(a, 10f), dp(a, 84f))
        val icons = ArrayList<ImageView>()
        var currentCard: View? = null
        var row: LinearLayout? = null
        val thumbH = dp(a, 168f)
        tabs.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(a).also {
                    it.orientation = LinearLayout.HORIZONTAL
                    grid.addView(it)
                }
            }
            val card = LinearLayout(a)
            card.orientation = LinearLayout.VERTICAL
            card.background = GradientDrawable().apply {
                setColor(p.card)
                cornerRadius = dp(a, 16f).toFloat()
                if (t.current) setStroke(dp(a, 2.5f), p.accent)
            }
            card.clipToOutline = true
            card.setPadding(dp(a, 3f), dp(a, 3f), dp(a, 3f), dp(a, 3f))
            card.setOnClickListener { dialog.dismiss(); onPick(i) }

            val head = LinearLayout(a)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(dp(a, 9f), dp(a, 2f), 0, dp(a, 2f))
            val icon = tinted(a, R.drawable.ic_wisp_globe, p.secondary)
            icons.add(icon)
            head.addView(icon, LinearLayout.LayoutParams(dp(a, 16f), dp(a, 16f)))
            val tt = TextView(a)
            tt.text = t.title
            tt.textSize = 13f
            tt.setTextColor(if (t.current) p.accent else p.text)
            tt.isSingleLine = true
            tt.ellipsize = TextUtils.TruncateAt.END
            tt.setPadding(dp(a, 8f), 0, dp(a, 4f), 0)
            head.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val close = tinted(a, R.drawable.ic_close_grey_900_24dp, p.icon)
            close.contentDescription = a.getString(R.string.wisp_close_tab)
            close.background = ripple(a)
            close.setPadding(dp(a, 9f), dp(a, 9f), dp(a, 9f), dp(a, 9f))
            close.setOnClickListener { dialog.dismiss(); onClose(i) }
            head.addView(close, LinearLayout.LayoutParams(dp(a, 38f), dp(a, 38f)))
            card.addView(head)

            val preview = ImageView(a)
            preview.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.background = rounded(p.sheet, dp(a, 13f).toFloat())
            preview.clipToOutline = true
            if (t.thumbnail != null) {
                preview.setImageBitmap(t.thumbnail)
            } else {
                //no preview yet: the site's address (or Wisp's start page) in the middle
                preview.scaleType = ImageView.ScaleType.CENTER
                preview.setImageResource(if (t.home) R.drawable.ic_wisp_find else R.drawable.ic_wisp_globe)
                preview.setColorFilter(p.divider)
            }
            card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH))

            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(a, 5f), dp(a, 5f), dp(a, 5f), dp(a, 5f))
            row?.addView(card, lp)
            if (t.current) currentCard = card
        }
        if (tabs.size % 2 == 1) row?.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).also {
            it.setMargins(dp(a, 5f), 0, dp(a, 5f), 0)
        })

        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(grid)
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        //"+ New tab" floating at the bottom right
        val fab = TextView(a)
        fab.text = "+  " + a.getString(R.string.wisp_new_tab)
        fab.textSize = 15f
        fab.setTypeface(fab.typeface, Typeface.BOLD)
        fab.setTextColor(p.onAccent)
        fab.background = rounded(p.accent, dp(a, 18f).toFloat())
        fab.elevation = dp(a, 6f).toFloat()
        fab.setPadding(dp(a, 20f), dp(a, 14f), dp(a, 22f), dp(a, 14f))
        fab.setOnClickListener { dialog.dismiss(); onNew() }
        val flp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        flp.gravity = Gravity.BOTTOM or Gravity.END
        flp.setMargins(0, 0, dp(a, 18f), dp(a, 18f))
        root.addView(fab, flp)

        val height = (a.resources.displayMetrics.heightPixels * 0.86f).toInt()
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        val holder = FrameLayout(a)
        holder.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        dialog = sheet(a, holder)
        dialog.show()
        currentCard?.let { c -> scroll.post { scroll.scrollTo(0, ((c.parent as View).top - dp(a, 12f)).coerceAtLeast(0)) } }
        val setIcon: (Int, Bitmap?) -> Unit = { i, bmp ->
            if (bmp != null && i in icons.indices) {
                icons[i].clearColorFilter()
                icons[i].setImageBitmap(bmp)
            }
        }
        return Pair(dialog, setIcon)
    }
}
