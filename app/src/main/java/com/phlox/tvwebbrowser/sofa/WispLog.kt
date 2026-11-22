package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Brand
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Wisp's diagnostics log: crashes, the reasons Android closed Wisp, memory, page process deaths,
 * failed loads, reload loops, download and VPN errors, page script errors and consistency checks.
 *
 * Kept on the phone only (files/diag), 7 days, about 600 kB at most; it survives Quit. Site names
 * only, unless "full addresses" is switched on. Never typed text, cookies or passwords.
 * Settings > About Wisp > Diagnostics shows it and copies or shares it.
 */
object WispLog {
    private const val TAG = "WispLog"
    private const val DIR = "diag"
    private const val FILE = "events.log"
    private const val LAST_PROBLEM = "last_problem.txt"
    private const val PREFS = "wisp_diag"
    private const val MAX_BYTES = 600_000L
    private const val KEEP_MS = 7L * 24 * 3600 * 1000

    @Volatile private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wisp-log").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile var screen: String = "-"
        private set
    @Volatile private var resumed = 0
    /** MainActivity tells how many tabs are open (for memory lines and crash context). */
    @Volatile var tabCount: () -> Int = { -1 }

    val enabled get() = app != null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var fullAddresses: Boolean
        get() = app?.let { prefs(it).getBoolean("full", false) } ?: false
        set(v) { app?.let { prefs(it).edit().putBoolean("full", v).apply() } }

    private fun dir(c: Context) = File(c.filesDir, DIR).apply { mkdirs() }

    // ---------------- writing ----------------

    fun event(cat: String, msg: String) = write("I", cat, msg)
    fun warn(cat: String, msg: String) = write("W", cat, msg)
    fun error(cat: String, msg: String, t: Throwable? = null) =
        write("E", cat, if (t == null) msg else msg + " - " + t.javaClass.simpleName + ": " + (t.message ?: ""))

    private val lastSame = HashMap<String, Long>()

    /** A consistency check that failed: two things that should agree, didn't. Same text at most once a minute. */
    fun mismatch(what: String, detail: String) {
        val key = what + detail
        val now = SystemClock.elapsedRealtime()
        synchronized(lastSame) {
            if (now - (lastSame[key] ?: -120_000L) < 60_000) return
            lastSame[key] = now
        }
        write("W", "check", "$what: $detail")
    }

    private fun write(level: String, cat: String, msg: String) {
        val c = app ?: return
        val time = System.currentTimeMillis()
        val line = "${level} ${stamp(time)} [$cat] ${msg.replace('\n', ' ').take(600)}\n"
        io.execute {
            try {
                val f = File(dir(c), FILE)
                FileOutputStream(f, true).use { it.write(line.toByteArray()) }
                if (f.length() > MAX_BYTES) trim(f)
            } catch (e: Exception) {
                Log.e(TAG, "write", e)
            }
        }
    }

    private fun stamp(t: Long): String = synchronized(fmt) { fmt.format(Date(t)) }

    private fun parseStamp(line: String): Long? = try {
        synchronized(fmt) { fmt.parse(line.substring(2, 21))?.time }
    } catch (e: Exception) { null }

    /** Drops entries older than 7 days, and the oldest third when the file is too big. */
    private fun trim(f: File) {
        val lines = try { f.readLines() } catch (e: Exception) { return }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        var keep = lines.dropWhile { l -> parseStamp(l)?.let { it < cutoff } ?: false }
        var size = keep.sumOf { it.length + 1L }
        if (size > MAX_BYTES) {
            keep = keep.drop(keep.size / 3)
            size = keep.sumOf { it.length + 1L }
        }
        if (keep.size != lines.size) f.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
    }

    /** The address as the log shows it: the site name, unless full addresses are switched on. */
    fun site(url: String?): String {
        if (url.isNullOrEmpty()) return "-"
        if (url.startsWith("data:")) return "data:"
        if (url.startsWith("file:")) return "saved page"
        if (fullAddresses) return url.take(300)
        return try { Uri.parse(url).host ?: url.substringBefore(':') } catch (e: Exception) { "?" }
    }

    // ---------------- set up ----------------

    fun install(application: Application) {
        if (!Brand.phone || app != null) return
        app = application.applicationContext
        installCrashHandler(application)
        application.registerActivityLifecycleCallbacks(lifecycle)
        application.registerComponentCallbacks(memoryCallbacks)
        io.execute {
            try { trim(File(dir(application), FILE)) } catch (e: Exception) {}
            readExitReasons(application)
        }
        event("app", "started · ${versionLine(application)}")
    }

    private fun installCrashHandler(c: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val stack = sw.toString().lines().take(80).joinToString("\n")
                val text = "CRASH ${stamp(System.currentTimeMillis())} · thread ${thread.name} · screen $screen · " +
                        "tabs ${safeTabs()} · ${memoryLine(c)}\n$stack"
                //written at once (the process ends right after this)
                File(dir(c), LAST_PROBLEM).writeText(text.take(20_000))
                FileOutputStream(File(dir(c), FILE), true).use {
                    it.write(("E ${stamp(System.currentTimeMillis())} [crash] ${error.javaClass.name}: " +
                            "${(error.message ?: "").replace('\n', ' ').take(300)} · screen $screen\n").toByteArray())
                }
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun safeTabs() = try { tabCount() } catch (e: Throwable) { -1 }

    /** Why Android ended Wisp's process before (Android 11+): low memory, crash, froze... */
    private fun readExitReasons(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val seen = prefs(c).getLong("exit_seen", 0L)
            val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 12)
            var newest = seen
            for (x in list.sortedBy { it.timestamp }) {
                if (x.timestamp <= seen) continue
                newest = maxOf(newest, x.timestamp)
                val name = exitName(x.reason)
                val problem = x.reason !in setOf(
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF, android.app.ApplicationExitInfo.REASON_USER_REQUESTED,
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED, android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                    android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE) ||
                        (x.reason == android.app.ApplicationExitInfo.REASON_EXIT_SELF && x.status != 0)
                val line = "Android ended Wisp: $name (${x.description ?: "no details"}) · " +
                        "was ${importanceName(x.importance)} · memory ${x.pss / 1024} MB (rss ${x.rss / 1024} MB)" +
                        " · at ${stamp(x.timestamp)}"
                val f = File(dir(c), FILE)
                FileOutputStream(f, true).use {
                    it.write("${if (problem) "E" else "I"} ${stamp(x.timestamp)} [exit] ${line.replace('\n', ' ')}\n".toByteArray())
                }
                if (problem) {
                    //a Java crash already has its own box (LumenCrashLog); the others had none
                    if (x.reason != android.app.ApplicationExitInfo.REASON_CRASH) pendingNotice = name
                    var trace = ""
                    if (x.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                        x.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        trace = try {
                            x.traceInputStream?.bufferedReader()?.use { r -> r.readText().take(6000) } ?: ""
                        } catch (e: Exception) { "" }
                    }
                    File(dir(c), LAST_PROBLEM).writeText(("EXIT $line\n$trace").take(20_000))
                }
            }
            if (newest > seen) prefs(c).edit().putLong("exit_seen", newest).apply()
        } catch (e: Exception) {
            Log.e(TAG, "exit reasons", e)
        }
    }

    private fun exitName(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory (low memory killer)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native code)"
        android.app.ApplicationExitInfo.REASON_ANR -> "not responding (froze)"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a part it needs died"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by you (Quit, recents or force stop)"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by you"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "updated"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        android.app.ApplicationExitInfo.REASON_OTHER -> "other (system)"
        else -> "reason $r"
    }

    private fun importanceName(i: Int): String = when {
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "running a service (download/VPN)"
        else -> "in the background"
    }

    @Volatile private var pendingNotice: String? = null

    /** Once after Android closed Wisp (out of memory, froze...): says so, with Copy log. */
    fun showClosedNoticeIfAny(a: Activity) {
        if (app == null) return
        io.execute {
            val reason = pendingNotice ?: return@execute
            pendingNotice = null
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                try {
                    android.app.AlertDialog.Builder(a)
                        .setTitle(com.phlox.tvwebbrowser.R.string.wisp_diag_closed_title)
                        .setMessage(a.getString(com.phlox.tvwebbrowser.R.string.wisp_diag_closed_msg, reason))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(com.phlox.tvwebbrowser.R.string.wisp_diag_copy) { _, _ -> copyReport(a) }
                        .show()
                } catch (e: Exception) {}
            }
        }
    }

    fun copyReport(c: Context) {
        flush()
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Wisp diagnostics", report(c)))
        android.widget.Toast.makeText(c, com.phlox.tvwebbrowser.R.string.wisp_diag_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- screens and memory ----------------

    private var lastMemLog = 0L
    private var lastPssMb = 0

    private val memoryTick = object : Runnable {
        override fun run() {
            val c = app ?: return
            val pss = try { (Debug.getPss() / 1024).toInt() } catch (e: Exception) { 0 }
            val now = SystemClock.elapsedRealtime()
            //every 5 minutes, or sooner when memory moved by 40 MB
            if (now - lastMemLog > 5 * 60_000 || kotlin.math.abs(pss - lastPssMb) >= 40) {
                lastMemLog = now
                lastPssMb = pss
                event("memory", memoryLine(c))
            }
            if (resumed > 0) main.postDelayed(this, 60_000)
        }
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {
            screen = a.javaClass.simpleName
            if (resumed++ == 0) {
                event("app", "in front ($screen)")
                main.removeCallbacks(memoryTick)
                main.postDelayed(memoryTick, 3_000)
            }
        }
        override fun onActivityPaused(a: Activity) {
            resumed = (resumed - 1).coerceAtLeast(0)
            if (resumed == 0) {
                main.removeCallbacks(memoryTick)
                main.postDelayed({ if (resumed == 0) event("app", "in the background") }, 1500)
            }
        }
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val name = when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running, memory getting low"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running, memory low"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running, memory critical"
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> null //just went to the background
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background, may be closed"
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "background, likely closed soon"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "background, next to be closed"
                else -> "level $level"
            } ?: return
            val c = app ?: return
            warn("memory", "Android warns: $name · ${memoryLine(c)}")
        }
        @Deprecated("") override fun onLowMemory() {
            app?.let { error("memory", "Android: very low memory · ${memoryLine(it)}") }
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    fun memoryLine(c: Context): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val max = rt.maxMemory() / 1048576
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val pss = try { Debug.getPss() / 1024 } catch (e: Exception) { -1 }
        val tabs = safeTabs()
        return "Wisp uses $pss MB (Java $used of $max MB)" + (if (tabs >= 0) " · $tabs tabs" else "") +
                " · phone free ${mi.availMem / 1048576} of ${mi.totalMem / 1048576} MB" + (if (mi.lowMemory) " (LOW)" else "")
    }

    // ---------------- the report ----------------

    fun versionLine(c: Context): String {
        val v = try {
            val p = c.packageManager.getPackageInfo(c.packageName, 0)
            @Suppress("DEPRECATION")
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()})"
        } catch (e: Exception) { "?" }
        val bits = if (Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit()) "64-bit" else "32-bit"
        return "Wisp $v $bits · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun webViewLine(): String = try {
        if (Build.VERSION.SDK_INT >= 26) {
            val p = android.webkit.WebView.getCurrentWebViewPackage()
            "${p?.packageName} ${p?.versionName}"
        } else "?"
    } catch (e: Throwable) { "?" }

    private fun settingsLine(): String = try {
        val cfg = AppContext.provideConfig()
        listOf(
            "ad blocker " + (if (cfg.adBlockEnabled) "on" else "off"),
            "small ads " + (if (cfg.wispHideSmallAds) "hidden" else "shown"),
            "WebRTC relay " + (if (cfg.wispWebrtcRelay) "on" else "off"),
            "desktop sites ${cfg.desktopSites.size}",
            "kept logins ${cfg.wispKeepSites.size}",
            "VPN " + SofaVpn.status.value.name.lowercase(Locale.ROOT),
            "theme " + cfg.theme.value.name.lowercase(Locale.ROOT),
            "custom script " + (if (cfg.sofaUserScript.isNotBlank()) "yes" else "no"),
            "full addresses in log " + (if (fullAddresses) "yes" else "no")
        ).joinToString(" · ")
    } catch (e: Throwable) { "?" }

    fun lines(c: Context): List<String> = try {
        File(dir(c), FILE).takeIf { it.exists() }?.readLines() ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun lastProblem(c: Context): String? = try {
        File(dir(c), LAST_PROBLEM).takeIf { it.exists() }?.readText()?.ifBlank { null }
    } catch (e: Exception) { null }

    /** The text for Copy / Share. */
    fun report(c: Context, events: Int = 100): String {
        val all = lines(c)
        val sb = StringBuilder()
        sb.append("=== Wisp diagnostics ").append(stamp(System.currentTimeMillis())).append(" ===\n")
        sb.append(versionLine(c)).append('\n')
        sb.append("Web engine: ").append(webViewLine()).append('\n')
        sb.append("Memory: ").append(memoryLine(c)).append('\n')
        sb.append("Settings: ").append(settingsLine()).append('\n')
        sb.append("Log: ${all.size} entries").append(all.firstOrNull()?.let { " since " + it.substring(2, 21.coerceAtMost(it.length)) } ?: "").append('\n')
        sb.append("\n--- Last crash or closure ---\n").append(lastProblem(c) ?: "none").append('\n')
        val checks = all.filter { it.contains("[check]") }
        sb.append("\n--- Inconsistencies (${checks.size}) ---\n")
        if (checks.isEmpty()) sb.append("none\n") else checks.takeLast(40).forEach { sb.append(it).append('\n') }
        val problems = all.filter { it.startsWith("E ") && !it.contains("[check]") }
        sb.append("\n--- Errors (${problems.size}) ---\n")
        if (problems.isEmpty()) sb.append("none\n") else problems.takeLast(40).forEach { sb.append(it).append('\n') }
        sb.append("\n--- Last $events events ---\n")
        all.takeLast(events).forEach { sb.append(it).append('\n') }
        //the newest problem report or UI check: the screen's layout as text (pictures go with Share)
        WispSnapshot.latestText(c)?.let { sb.append("\n--- Latest report / UI check: layout ---\n").append(it).append('\n') }
        return sb.toString()
    }

    fun clear(c: Context) {
        io.execute {
            try {
                File(dir(c), FILE).delete()
                File(dir(c), LAST_PROBLEM).delete()
            } catch (e: Exception) {}
        }
        event("app", "log cleared")
    }

    /** Report a problem: the person's words, then what was going on. */
    fun userReport(c: Context, category: String, text: String) {
        warn("report", "$category: ${text.take(300)} · screen $screen · ${memoryLine(c)}")
    }

    /** Waits until everything written so far is on disk (before reading the file for a report). */
    fun flush() {
        try { io.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
    }

    // ---------------- page loads (reload loops) ----------------

    private val recentLoads = ArrayList<Pair<String, Long>>()

    /** Called on every main-page load start; notices the same page loading again and again. */
    fun pageStarted(url: String) {
        if (app == null) return
        val now = SystemClock.elapsedRealtime()
        val key = url.substringBefore('#')
        val n = synchronized(recentLoads) {
            recentLoads.removeAll { now - it.second > 10_000 }
            recentLoads.add(Pair(key, now))
            recentLoads.count { it.first == key }
        }
        if (n == 4) warn("loop", "${site(url)} loaded 4 times in 10 s (reload loop?)")
        event("page", "open ${site(url)}")
    }
}
