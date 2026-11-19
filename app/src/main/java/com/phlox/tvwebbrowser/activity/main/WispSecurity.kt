package com.phlox.tvwebbrowser.activity.main

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Base64
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.sofa.SofaCrypto
import com.phlox.tvwebbrowser.sofa.SofaPasswords
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wisp: app lock (the phone's own fingerprint / face / PIN, nothing stored by Wisp) and
 * backup & restore of bookmarks, saved passwords, settings and the VPN in one file encrypted
 * with a password the user chooses (AES-256-GCM, key from PBKDF2-SHA256).
 */
object WispSecurity {

    // ---------------- lock ----------------

    /** When Wisp was last unlocked / last left (elapsed realtime). */
    @Volatile var unlockedAt = 0L
    @Volatile var leftAt = 0L
    private const val RELOCK_AFTER_MS = 60_000L

    /** The phone has a screen lock (needed: Wisp uses it, it keeps no PIN of its own). */
    fun deviceSecure(c: Context): Boolean =
        (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** Wisp must be unlocked now (fresh start, or away for more than a minute). */
    fun needsUnlock(): Boolean {
        val config = AppContext.provideConfig()
        if (!config.wispLockApp) return false
        if (unlockedAt == 0L) return true
        return leftAt > unlockedAt && SystemClock.elapsedRealtime() - leftAt > RELOCK_AFTER_MS
    }

    /**
     * Asks for the fingerprint, face or the phone's PIN / pattern / password.
     * [done] gets true when it was given. Android 10 and older without biometrics: the PIN screen.
     */
    /** A fingerprint / PIN request is on screen. */
    @Volatile var prompting = false

    fun authenticate(a: Activity, title: String, done0: (Boolean) -> Unit) {
        if (!deviceSecure(a)) { done0(true); return }
        if (prompting) return
        prompting = true
        val done: (Boolean) -> Unit = { ok -> prompting = false; done0(ok) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(a)
                .setTitle(title)
                .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(CancellationSignal(), a.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                        unlockedAt = SystemClock.elapsedRealtime()
                        done(true)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { done(false) }
                })
        } else {
            val km = a.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(title, null)
            if (intent == null) { done(true); return }
            pendingCredential = done
            @Suppress("DEPRECATION")
            a.startActivityForResult(intent, REQUEST_CONFIRM_CREDENTIAL)
        }
    }

    const val REQUEST_CONFIRM_CREDENTIAL = 10031
    private var pendingCredential: ((Boolean) -> Unit)? = null

    /** Result of the PIN screen (Android 8-10). */
    fun onCredentialResult(ok: Boolean) {
        if (ok) unlockedAt = SystemClock.elapsedRealtime()
        pendingCredential?.invoke(ok)
        pendingCredential = null
    }

    // ---------------- backup ----------------

    /** Settings that are tied to this phone's keystore: carried over in plain form inside the encrypted backup. */
    private val SKIP_KEYS = setOf(Config.SOFA_VPN_CONFIG_KEY, "lumen_update_token")

    suspend fun buildBackup(c: Context): JSONObject {
        val config = AppContext.provideConfig()
        val o = JSONObject()
        o.put("app", "Wisp").put("format", 1).put("created", System.currentTimeMillis())
        //settings
        val prefs = c.getSharedPreferences(com.phlox.tvwebbrowser.TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        val settings = JSONObject()
        for ((k, v) in prefs.all) {
            if (k in SKIP_KEYS || v == null) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            settings.put(k, e)
        }
        o.put("settings", settings)
        //VPN and update token (decrypted here, encrypted again with this phone's keystore on restore)
        config.sofaVpnConfigEncrypted.takeIf { it.isNotEmpty() }?.let { SofaCrypto.decrypt(it) }?.let { o.put("vpn", it) }
        config.lumenUpdateTokenEncrypted.takeIf { it.isNotEmpty() }?.let { SofaCrypto.decrypt(it) }?.let { o.put("updateToken", it) }
        //saved passwords
        val logins = JSONArray()
        for (l in SofaPasswords.all()) {
            logins.put(JSONObject().put("site", l.site).put("user", l.username).put("pass", l.password).put("time", l.time))
        }
        o.put("passwords", logins)
        //bookmarks (also the start page's)
        val marks = JSONArray()
        val dao = AppDatabase.db.favoritesDao()
        for (f in dao.getAll(false) + dao.getHomePageBookmarks()) {
            if (f.url == null) continue
            marks.put(JSONObject().put("title", f.title ?: "").put("url", f.url).put("home", f.homePageBookmark)
                .put("order", f.order).put("favicon", f.favicon ?: ""))
        }
        o.put("bookmarks", marks)
        //pages saved offline, newest first, up to [PAGES_LIMIT] in all (the backup file must stay readable)
        val pages = JSONArray()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var total = 0L
            for (p in WispPages.list(c)) {
                val f = java.io.File(p.file)
                val len = f.length()
                if (len <= 0 || total + len > PAGES_LIMIT) continue
                val data = try { f.readBytes() } catch (e: Exception) { continue }
                total += len
                pages.put(JSONObject().put("title", p.title).put("url", p.url).put("time", p.time)
                    .put("data", Base64.encodeToString(data, Base64.NO_WRAP)))
            }
        }
        o.put("pages", pages)
        return o
    }

    /** Saved pages in a backup: 12 MB at most (the encrypted file is about twice that). */
    private const val PAGES_LIMIT = 12L * 1024 * 1024

    class Restored(val settings: Int, val passwords: Int, val bookmarks: Int, val vpn: Boolean, val pages: Int = 0)

    suspend fun restore(c: Context, o: JSONObject): Restored {
        val config = AppContext.provideConfig()
        var nSettings = 0
        o.optJSONObject("settings")?.let { s ->
            val ed = c.getSharedPreferences(com.phlox.tvwebbrowser.TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE).edit()
            for (k in s.keys()) {
                if (k in SKIP_KEYS) continue
                val e = s.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "s" -> ed.putString(k, e.optString("v"))
                    "set" -> {
                        val arr = e.optJSONArray("v") ?: JSONArray()
                        ed.putStringSet(k, (0 until arr.length()).map { arr.optString(it) }.toHashSet())
                    }
                    else -> continue
                }
                nSettings++
            }
            ed.commit()
        }
        var vpn = false
        o.optString("vpn").takeIf { it.isNotEmpty() }?.let {
            vpn = com.phlox.tvwebbrowser.sofa.SofaVpn.importConfig(it) == null
        }
        o.optString("updateToken").takeIf { it.isNotEmpty() }?.let { t ->
            SofaCrypto.encrypt(t)?.let { config.lumenUpdateTokenEncrypted = it }
        }
        var nPass = 0
        o.optJSONArray("passwords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val site = p.optString("site")
                val pass = p.optString("pass")
                if (site.isEmpty() || pass.isEmpty()) continue
                if (SofaPasswords.save(SofaPasswords.Login(site, p.optString("user"), pass, p.optLong("time")))) nPass++
            }
        }
        var nMarks = 0
        o.optJSONArray("bookmarks")?.let { arr ->
            val dao = AppDatabase.db.favoritesDao()
            val existing = (dao.getAll(false) + dao.getHomePageBookmarks()).map { Pair(it.url, it.homePageBookmark) }.toHashSet()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val url = b.optString("url")
                val home = b.optBoolean("home")
                if (url.isEmpty() || Pair(url, home) in existing) continue
                val f = FavoriteItem()
                f.title = b.optString("title")
                f.url = url
                f.homePageBookmark = home
                f.order = b.optInt("order")
                f.favicon = b.optString("favicon").ifEmpty { null }
                dao.insert(f)
                nMarks++
            }
        }
        var nPages = 0
        o.optJSONArray("pages")?.let { arr ->
            val added = ArrayList<WispPages.Saved>()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val existing = WispPages.list(c).map { Pair(it.url, it.time) }.toHashSet()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val url = p.optString("url")
                    val time = p.optLong("time")
                    if (Pair(url, time) in existing) continue
                    val bytes = try { Base64.decode(p.optString("data"), Base64.NO_WRAP) } catch (e: Exception) { continue }
                    if (bytes.isEmpty()) continue
                    val f = java.io.File(WispPages.dir(c), "page_" + time + "_" + i + ".mht")
                    try { f.writeBytes(bytes) } catch (e: Exception) { continue }
                    added.add(WispPages.Saved(f.absolutePath, p.optString("title").ifBlank { url }, url, time))
                }
                if (added.isNotEmpty()) WispPages.addRestored(c, added)
            }
            nPages = added.size
        }
        return Restored(nSettings, nPass, nMarks, vpn, nPages)
    }

    // ---------------- file encryption ----------------

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return JSONObject().put("wispBackup", 1).put("kdf", "PBKDF2-SHA256-210000").put("salt", b64(salt))
            .put("iv", b64(iv)).put("data", b64(data)).toString()
    }

    /** null: wrong password or not a Wisp backup. */
    fun decrypt(file: String, password: String): String? = try {
        val o = JSONObject(file)
        if (o.optInt("wispBackup") != 1) null else {
            fun d(k: String) = Base64.decode(o.getString(k), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, d("salt")), GCMParameterSpec(128, d("iv")))
            String(cipher.doFinal(d("data")), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}
