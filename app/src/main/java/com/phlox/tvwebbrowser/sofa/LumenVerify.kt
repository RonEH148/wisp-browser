package com.phlox.tvwebbrowser.sofa

import android.content.Context
import com.phlox.tvwebbrowser.AppContext

/**
 * Human-verification pages (Cloudflare "Verify you are human", Turnstile) fail when they see
 * a modified browser: injected scripts, patched functions, the app's JS bridge, blocked
 * requests or a user agent that doesn't match the engine.
 *
 * When the page script spots such a challenge, the tab is reloaded in "clean mode" for that
 * site: no JS bridge, no injected scripts, no ad blocking, the WebView's own user agent.
 * Once the check passes everything comes back on. Sites whose check passed that way keep the
 * WebView's own user agent, because Cloudflare ties its pass cookie to the user agent.
 * Sites with a verification widget only get the page script's detectable changes left out.
 */
object LumenVerify {
    private const val PREFS = "lumen_verify"
    private const val KEY_HOSTS = "ua_hosts2"
    private const val KEY_SAFE = "safe_hosts"
    private const val CLEAN_MS = 5 * 60_000L
    private const val MAX_ATTEMPTS = 3

    private val cleanUntil = HashMap<String, Long>()
    private val attempts = HashMap<String, MutableList<Long>>()

    private fun prefs() = AppContext.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
        //builds up to 101 switched every site with a Cloudflare widget to the WebView's own user
        //agent, which made some sites challenge every time: start again from a clean list
        if (it.contains("ua_hosts")) it.edit().remove("ua_hosts").apply()
    }

    fun isClean(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val until = cleanUntil[host] ?: return false
        if (System.currentTimeMillis() > until) {
            cleanUntil.remove(host)
            return false
        }
        return true
    }

    /** Starts clean mode for the host. Returns false when it was tried too often just now. */
    fun startClean(host: String): Boolean {
        val now = System.currentTimeMillis()
        val list = attempts.getOrPut(host) { ArrayList() }
        list.removeAll { now - it > 10 * 60_000L }
        if (list.size >= MAX_ATTEMPTS) return false
        list.add(now)
        cleanUntil[host] = now + CLEAN_MS
        return true
    }

    /** From now on the site gets the WebView's own user agent and no detectable page changes. */
    @Synchronized
    fun rememberSystemUa(host: String) {
        val hosts = usesSystemUa()
        if (host !in hosts) {
            prefs().edit().putStringSet(KEY_HOSTS, HashSet(hosts).apply { add(host) }).apply()
        }
    }

    /** Sites with a verification widget: the page script leaves out its detectable changes. */
    @Synchronized
    fun rememberWidget(host: String) {
        val set = prefs().getStringSet(KEY_SAFE, emptySet()) ?: emptySet()
        if (host !in set) prefs().edit().putStringSet(KEY_SAFE, HashSet(set).apply { add(host) }).apply()
    }

    fun isVerifySafe(host: String?): Boolean = !host.isNullOrEmpty() &&
            (host in (prefs().getStringSet(KEY_SAFE, emptySet()) ?: emptySet()) || keepsSystemUa(host))

    /** Site fixes → reset: forget everything remembered about the site's verification. */
    @Synchronized
    fun forget(host: String) {
        cleanUntil.remove(host)
        attempts.remove(host)
        val p = prefs()
        val ua = HashSet(p.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet())
        val safe = HashSet(p.getStringSet(KEY_SAFE, emptySet()) ?: emptySet())
        ua.removeAll { it == host || it.endsWith(".$host") }
        safe.removeAll { it == host || it.endsWith(".$host") }
        p.edit().putStringSet(KEY_HOSTS, ua).putStringSet(KEY_SAFE, safe).apply()
    }

    fun endClean(host: String?) {
        if (host != null) cleanUntil.remove(host)
    }

    private fun usesSystemUa(): Set<String> = prefs().getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()

    /** Sites that showed a verification page: always sent the WebView's own user agent. */
    fun keepsSystemUa(host: String?): Boolean = !host.isNullOrEmpty() && host in usesSystemUa()

    /** Never blocked by the ad blocker: verification widgets themselves. */
    fun isVerificationResource(host: String?): Boolean {
        if (host == null) return false
        return host == "challenges.cloudflare.com" || host.endsWith(".hcaptcha.com") || host == "hcaptcha.com" ||
                host == "www.recaptcha.net" || host == "recaptcha.net"
    }

    fun clearAll() {
        cleanUntil.clear()
        attempts.clear()
        prefs().edit().clear().apply()
    }
}
