package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in password manager. Every site has its own encrypted entry ([SofaCrypto]) that can
 * hold several accounts. On a login page Lumen asks which account to fill in; the password only
 * goes into the page after that, and only into the top-level page of the site it was saved for.
 */
object SofaPasswords {
    private const val PREFS = "sofa_passwords"

    data class Login(val site: String, val username: String, val password: String, val time: Long = 0L)

    private fun prefs() = AppContext.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun siteOf(url: String?): String? {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        val host = try { Uri.parse(url).host } catch (e: Exception) { null } ?: return null
        return Config.siteKey(host).ifEmpty { null }
    }

    /** All accounts saved for the site, most recently used first. */
    fun get(site: String): List<Login> {
        val enc = prefs().getString(site, null) ?: return emptyList()
        val json = SofaCrypto.decrypt(enc) ?: return emptyList()
        return try {
            val t = json.trim()
            if (t.startsWith("{")) {
                //builds up to 112: one login per site
                val o = JSONObject(t)
                listOf(Login(site, o.optString("u"), o.optString("p")))
            } else {
                val a = JSONArray(t)
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Login(site, o.optString("u"), o.optString("p"), o.optLong("t"))
                }.sortedByDescending { it.time }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(site: String, logins: List<Login>): Boolean {
        if (logins.isEmpty()) {
            prefs().edit().remove(site).apply()
            return true
        }
        val a = JSONArray()
        for (l in logins) a.put(JSONObject().put("u", l.username).put("p", l.password).put("t", l.time))
        val enc = SofaCrypto.encrypt(a.toString()) ?: return false
        prefs().edit().putString(site, enc).apply()
        return true
    }

    /** Adds the login, or replaces the one with the same username. */
    fun save(login: Login): Boolean {
        val list = get(login.site).filter { it.username != login.username }
        return write(login.site, listOf(login.copy(time = System.currentTimeMillis())) + list)
    }

    fun delete(login: Login) = write(login.site, get(login.site).filter { it.username != login.username })

    fun all(): List<Login> = prefs().all.keys.sorted().flatMap { get(it) }

    private fun sites(): List<String> = prefs().all.keys.sorted()

    private fun touch(login: Login) {
        write(login.site, listOf(login.copy(time = System.currentTimeMillis())) +
                get(login.site).filter { it.username != login.username })
    }

    // ---------------- saving ----------------

    fun offerToSave(activity: Activity, webView: WebView, username: String, password: String) {
        val config = AppContext.provideConfig()
        //Wisp is always private but keeps saved passwords (they are in Settings → Saved passwords)
        if (!config.sofaSavePasswords || (config.incognitoMode && !com.phlox.tvwebbrowser.Brand.phone)) return
        val site = siteOf(webView.url) ?: return
        if (config.sofaPasswordNeverHosts.contains(site)) return
        val saved = get(site)
        if (saved.any { it.username == username && it.password == password }) return
        val sameUser = saved.firstOrNull { it.username == username }
        val title = when {
            sameUser != null -> activity.getString(R.string.sofa_update_password_q, site)
            saved.isNotEmpty() -> activity.getString(R.string.sofa_save_another_login_q, site)
            else -> activity.getString(R.string.sofa_save_password_q, site)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(if (username.isNotEmpty()) activity.getString(R.string.sofa_username_s, username) else null)
            .setPositiveButton(if (sameUser != null) R.string.sofa_update else R.string.sofa_save) { _, _ ->
                val ok = save(Login(site, username, password))
                Toast.makeText(activity, if (ok) R.string.sofa_password_saved else R.string.sofa_password_save_failed,
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.sofa_not_now, null)
            .setNeutralButton(R.string.sofa_never_for_site) { _, _ ->
                config.sofaPasswordNeverHosts = config.sofaPasswordNeverHosts + site
            }
            .show()
    }

    /**
     * Sign-up forms and the suggested strong password: saved without asking. An empty username
     * goes with the account used last (the address is often typed after the password is chosen).
     */
    fun autoSave(activity: Activity, webView: WebView, username: String, password: String) {
        val config = AppContext.provideConfig()
        if (!config.sofaSavePasswords || (config.incognitoMode && !com.phlox.tvwebbrowser.Brand.phone) || password.isEmpty()) return
        val site = siteOf(webView.url) ?: return
        if (config.sofaPasswordNeverHosts.contains(site)) return
        val saved = get(site)
        val user = username.ifEmpty { saved.firstOrNull()?.username ?: "" }
        if (saved.any { it.username == user && it.password == password }) return
        if (save(Login(site, user, password))) {
            Toast.makeText(activity, if (user.isNotEmpty()) "Password for $user saved for $site"
                else "Password saved for $site", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- filling ----------------

    /**
     * Called when a page has finished loading in the top frame. The page script waits for a
     * login form and then asks which account to fill in (no password is handed over yet).
     */
    fun fillIfSaved(webView: WebView, url: String?) {
        val config = AppContext.provideConfig()
        if (!config.sofaSavePasswords) return
        val site = siteOf(url) ?: return
        if (site != siteOf(webView.url)) return
        val logins = get(site)
        if (logins.isEmpty()) return
        val names = JSONArray()
        logins.forEach { names.put(it.username) }
        webView.evaluateJavascript(
            "window.__sofaOfferLogins && window.__sofaOfferLogins(" + JSONObject.quote(site) + "," + names + ");", null
        )
    }

    /** The user picked an account in the page's "Sign in as…" box. */
    fun fillChosen(webView: WebView, index: Int) {
        val site = siteOf(webView.url) ?: return
        val login = get(site).getOrNull(index) ?: return
        touch(login)
        webView.evaluateJavascript(
            "window.__sofaFill && window.__sofaFill(" + JSONObject.quote(login.username) + "," +
                    JSONObject.quote(login.password) + ");", null
        )
    }

    // ---------------- manager (Settings → Saved passwords) ----------------

    fun showManager(activity: Activity) {
        val sites = sites()
        val config = AppContext.provideConfig()
        val builder = AlertDialog.Builder(activity).setTitle(R.string.sofa_saved_passwords)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(activity.getString(R.string.sofa_add_login) to { editLogin(activity, null) })
        for (site in sites) {
            val logins = get(site)
            if (logins.isEmpty()) continue
            val label = if (logins.size == 1) "🔑  $site  ·  ${logins[0].username.ifEmpty { "(no username)" }}"
                else "🔑  $site  ·  ${logins.size} accounts"
            items.add(label to { if (logins.size == 1) showLogin(activity, logins[0]) else showSite(activity, site) })
        }
        if (items.size == 1) builder.setMessage(R.string.sofa_no_saved_passwords)
            .setPositiveButton(R.string.sofa_add_login_short) { _, _ -> editLogin(activity, null) }
        else builder.setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
        if (config.sofaPasswordNeverHosts.isNotEmpty()) {
            builder.setNeutralButton(R.string.sofa_reset_never_list) { _, _ ->
                config.sofaPasswordNeverHosts = emptySet()
            }
        }
        builder.setNegativeButton(R.string.close, null).show()
    }

    private fun showSite(activity: Activity, site: String) {
        val logins = get(site)
        if (logins.isEmpty()) { showManager(activity); return }
        AlertDialog.Builder(activity)
            .setTitle(site)
            .setItems(logins.map { "👤  " + it.username.ifEmpty { "(no username)" } }.toTypedArray()) { _, which ->
                showLogin(activity, logins[which])
            }
            .setNegativeButton(R.string.back) { _, _ -> showManager(activity) }
            .show()
    }

    private fun copy(activity: Activity, label: String, text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            //keeps the password out of the clipboard preview
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", label == "Password")
            }
        }
        cm.setPrimaryClip(clip)
        Toast.makeText(activity, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun showLogin(activity: Activity, login: Login, reveal: Boolean = false) {
        val masked = if (reveal) login.password else "•".repeat(login.password.length.coerceIn(6, 12))
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add("👤  Copy username" to { copy(activity, "Username", login.username); showLogin(activity, login, reveal) })
        actions.add("🔑  Copy password" to { copy(activity, "Password", login.password); showLogin(activity, login, reveal) })
        actions.add((if (reveal) "🙈  Hide password" else "👁  Show password") to { showLogin(activity, login, !reveal) })
        actions.add("✏  Edit" to { editLogin(activity, login) })
        actions.add("🗑  Delete" to {
            AlertDialog.Builder(activity)
                .setTitle(login.site)
                .setMessage(activity.getString(R.string.sofa_delete_login_q))
                .setPositiveButton(R.string.sofa_delete) { _, _ -> delete(login); showManager(activity) }
                .setNegativeButton(R.string.cancel) { _, _ -> showLogin(activity, login) }
                .show()
        })
        AlertDialog.Builder(activity)
            .setTitle("${login.site}\n${login.username.ifEmpty { "(no username)" }}  ·  $masked")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.back) { _, _ -> showManager(activity) }
            .show()
    }

    private fun editLogin(activity: Activity, old: Login?) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, type: Int) = EditText(activity).apply {
            this.hint = hint
            setSingleLine()
            inputType = type
            setText(value)
            box.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val site = field("Website (example.com)", old?.site ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field("Username or email", old?.username ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = field("Password", old?.password ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        if (old != null) site.isEnabled = false
        AlertDialog.Builder(activity)
            .setTitle(if (old == null) R.string.sofa_add_login_short else R.string.sofa_edit_login)
            .setView(box)
            .setPositiveButton(R.string.sofa_save) { _, _ ->
                var s = site.text.toString().trim()
                if (s.contains("://")) s = siteOf(s) ?: s
                s = Config.siteKey(s.substringBefore('/'))
                val p = pass.text.toString()
                if (s.isEmpty() || p.isEmpty()) {
                    Toast.makeText(activity, "Website and password are needed", Toast.LENGTH_SHORT).show()
                    editLogin(activity, old)
                    return@setPositiveButton
                }
                if (old != null) delete(old)
                val ok = save(Login(s, user.text.toString().trim(), p))
                Toast.makeText(activity, if (ok) R.string.sofa_password_saved else R.string.sofa_password_save_failed,
                    Toast.LENGTH_SHORT).show()
                showManager(activity)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> if (old != null) showLogin(activity, old) else showManager(activity) }
            .show()
    }
}
