package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Lumen updates from the GitHub Releases of the repository the builds come from (Settings → Version).
 * The build workflow names the APK "Lumen-<versionCode>.apk"; older builds named "…webview.apk" are
 * compared by date instead. Private repositories need a read-only access token.
 */
object LumenUpdater {
    class Release(val title: String, val notes: String, val versionCode: Int?, val publishedAt: Long,
                  val assetName: String, val apiUrl: String, val downloadUrl: String, val size: Long)

    private val io = Executors.newSingleThreadExecutor()
    /** How this app's APKs and release tags start: "lumen" or "wisp". */
    private val appKey: String get() = if (com.phlox.tvwebbrowser.Brand.phone) "wisp" else "lumen"
    private const val DAY = 24L * 60 * 60 * 1000

    private fun token(): String? {
        val enc = AppContext.provideConfig().lumenUpdateTokenEncrypted
        if (enc.isEmpty()) return null
        return SofaCrypto.decrypt(enc)?.takeIf { it.isNotBlank() }
    }

    fun setToken(token: String?): Boolean {
        val config = AppContext.provideConfig()
        if (token.isNullOrBlank()) {
            config.lumenUpdateTokenEncrypted = ""
            return true
        }
        val enc = SofaCrypto.encrypt(token.trim()) ?: return false
        config.lumenUpdateTokenEncrypted = enc
        return true
    }

    /** For [LumenTokenShareActivity] only (after the user agreed). */
    fun tokenForSharing(): String? = token()

    fun hasToken(): Boolean = AppContext.provideConfig().lumenUpdateTokenEncrypted.isNotEmpty()

    private fun parseTime(iso: String?): Long = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        f.parse(iso ?: "")?.time ?: 0L
    } catch (e: Exception) { 0L }

    /** Latest release, or throws with a readable message. Runs network I/O: call off the main thread. */
    fun fetchLatest(): Release {
        val repo = AppContext.provideConfig().lumenUpdateRepo
        //the repository also holds other apps' releases (LeanKeyboard Plus): take the newest one with a Lumen APK
        val c = URL("https://api.github.com/repos/$repo/releases?per_page=100").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Lumen-browser")
        //always ask GitHub, never an answer remembered from an earlier check
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        token()?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        try {
            when (c.responseCode) {
                200 -> {}
                401, 403 -> throw Exception("GitHub refused the access token ($repo). Check it in Settings → Version → Update source.")
                404 -> throw Exception(if (hasToken()) "No release found in $repo (or the token can't read it)."
                    else "$repo wasn't found. If it's a private repository, add an access token in Settings → Version → Update source.")
                else -> throw Exception("GitHub answered ${c.responseCode}")
            }
            val releases = org.json.JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            //GitHub doesn't always list the newest release first: take the highest build number
            var foundRel: JSONObject? = null
            var foundAsset: JSONObject? = null
            var bestVc = -1
            var bestTime = 0L
            for (r in 0 until releases.length()) {
                val rel = releases.getJSONObject(r)
                if (rel.optBoolean("draft")) continue
                val assets = rel.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name").lowercase(Locale.ROOT)
                    if (!name.endsWith(".apk") || name.contains("firefox") || name.contains("leankeyboard") ||
                        name.contains("justplayer")) continue
                    //Lumen (TV) and Wisp (phone) share the repository: each takes only its own APKs
                    val wispAsset = name.startsWith("wisp") || rel.optString("tag_name").startsWith("wisp", ignoreCase = true)
                    if (wispAsset != com.phlox.tvwebbrowser.Brand.phone) continue
                    val vc = Regex("""$appKey-(\d+)\.apk""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""$appKey-v(\d+)""", RegexOption.IGNORE_CASE).find(rel.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0
                    val time = parseTime(rel.optString("published_at"))
                    if (vc > bestVc || (vc == bestVc && time > bestTime)) {
                        bestVc = vc
                        bestTime = time
                        foundRel = rel
                        foundAsset = a
                    }
                }
            }
            val json: JSONObject = foundRel ?: throw Exception("No release with a ${com.phlox.tvwebbrowser.Brand.NAME} APK found in $repo")
            val pick: JSONObject = foundAsset ?: throw Exception("No release with a ${com.phlox.tvwebbrowser.Brand.NAME} APK found in $repo")
            val tag = json.optString("tag_name")
            val assetName = pick.optString("name")
            //"Lumen-83.apk" or tag "lumen-v83-b12" (Wisp: "Wisp-3.apk", "wisp-v3-b12")
            val version = Regex("""$appKey-(\d+)\.apk""", RegexOption.IGNORE_CASE).find(assetName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""$appKey-v(\d+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull()
            return Release(
                title = json.optString("name").ifBlank { tag },
                notes = json.optString("body"),
                versionCode = version,
                publishedAt = parseTime(pick.optString("updated_at").ifBlank { json.optString("published_at") }),
                assetName = assetName,
                apiUrl = pick.optString("url"),
                downloadUrl = pick.optString("browser_download_url"),
                size = pick.optLong("size")
            )
        } finally {
            c.disconnect()
        }
    }

    fun isNewer(activity: Activity, r: Release): Boolean {
        r.versionCode?.let { return it > BuildConfig.VERSION_CODE }
        //older workflow without the version in the name: newer than this installation
        val installed = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).lastUpdateTime
        } catch (e: Exception) { System.currentTimeMillis() }
        return r.publishedAt > installed + 10 * 60 * 1000
    }

    /** Once a day on start (when switched on); only says something when there is an update. */
    fun checkOnStart(activity: Activity) {
        val config = AppContext.provideConfig()
        if (!config.lumenAutoCheckUpdates) return
        val now = System.currentTimeMillis()
        if (now - config.lumenLastUpdateCheck < DAY) return
        config.lumenLastUpdateCheck = now
        check(activity, quiet = true)
    }

    /** Checks now; [quiet] = no message when there's nothing new or the check fails. */
    fun check(activity: Activity, quiet: Boolean) {
        if (!quiet) Toast.makeText(activity, "Checking for ${com.phlox.tvwebbrowser.Brand.NAME} updates…", Toast.LENGTH_SHORT).show()
        io.execute {
            val result = try { Result.success(fetchLatest()) } catch (e: Exception) { Result.failure(e) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onFailure { if (!quiet) message(activity, "Update check failed", it.message ?: it.toString()) }
                result.onSuccess { r ->
                    if (isNewer(activity, r)) offer(activity, r)
                    else if (!quiet) Toast.makeText(activity, "${com.phlox.tvwebbrowser.Brand.NAME} is up to date (build ${BuildConfig.VERSION_CODE})", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun message(activity: Activity, title: String, text: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun offer(activity: Activity, r: Release) {
        val what = r.versionCode?.let { "build $it (you have ${BuildConfig.VERSION_CODE})" } ?: r.title
        val size = if (r.size > 0) " · %.1f MB".format(Locale.US, r.size / 1048576.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("${com.phlox.tvwebbrowser.Brand.NAME} update available")
            .setMessage("New version: $what$size\n\n" + r.notes.take(600))
            .setPositiveButton("Install") { _, _ -> download(activity, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun download(activity: Activity, r: Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow ${com.phlox.tvwebbrowser.Brand.NAME} to install updates")
                .setMessage("Android needs your permission once: switch on \"Allow from this source\" for ${com.phlox.tvwebbrowser.Brand.NAME}, then come back and choose Install again.")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.packageName)))
                    } catch (e: Exception) {
                        Toast.makeText(activity, e.toString(), Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val dialog = ProgressDialog(activity)
        dialog.setMessage("Downloading ${r.assetName}…")
        dialog.isIndeterminate = r.size <= 0
        dialog.setProgressStyle(if (r.size > 0) ProgressDialog.STYLE_HORIZONTAL else ProgressDialog.STYLE_SPINNER)
        dialog.max = 100
        dialog.setCancelable(true)
        var cancelled = false
        dialog.setOnCancelListener { cancelled = true }
        dialog.show()
        val dir = activity.externalCacheDir ?: activity.cacheDir
        val file = File(dir, "lumen-update.apk")
        io.execute {
            val error = try {
                file.delete()
                downloadTo(r, file) { done ->
                    if (r.size > 0) activity.runOnUiThread { dialog.progress = (done * 100 / r.size).toInt() }
                    !cancelled
                }
                if (cancelled) "cancelled" else null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            activity.runOnUiThread {
                try { dialog.dismiss() } catch (ignored: Exception) {}
                when (error) {
                    null -> install(activity, file)
                    "cancelled" -> file.delete()
                    else -> message(activity, "Update download failed", error)
                }
            }
        }
    }

    /** GitHub sends assets through a redirect to its file storage, which must not get the token. */
    private fun downloadTo(r: Release, file: File, progress: (Long) -> Boolean) {
        val tok = token()
        var url = if (tok != null) r.apiUrl else r.downloadUrl
        var auth = tok
        for (hop in 0 until 6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Lumen-browser")
            if (auth != null) {
                c.setRequestProperty("Authorization", "Bearer $auth")
                c.setRequestProperty("Accept", "application/octet-stream")
            }
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location") ?: throw Exception("Bad redirect")
                auth = null
                c.disconnect()
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw Exception("Download failed (HTTP $code)")
            }
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (!progress(total)) return
                    }
                }
            }
            c.disconnect()
            return
        }
        throw Exception("Too many redirects")
    }

    private fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".provider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            message(activity, "Couldn't start the installer", e.toString())
        }
    }
}
