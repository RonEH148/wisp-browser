package com.phlox.tvwebbrowser

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine
import java.net.CookieHandler
import java.net.CookieManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Created by PDT on 09.09.2016.
 */
class TVBro : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        lateinit var instance: TVBro
        const val CHANNEL_ID_DOWNLOADS: String = "downloads"
        const val MAIN_PREFS_NAME = "main.xml"
        const val WISP_PRIVATE_PREFS = "wisp_private"
        const val WISP_WIPE_KEY = "wipe_on_start"
        val TAG = TVBro::class.simpleName
        private const val REPAIR_PREFS = "lumen_repair"
        private const val REPAIR_KEY = "repair_web_engine"

        /** The repair runs on the next start, before the web engine is loaded. */
        fun requestWebEngineRepair(context: android.content.Context) {
            context.getSharedPreferences(REPAIR_PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(REPAIR_KEY, true).commit()
        }
    }

    /** The web engine was repaired during this start (MainActivity says so once). */
    var repairDone = false

    lateinit var threadPool: ThreadPoolExecutor
        private set

    var needToExitProcessAfterMainActivityFinish = false
    var needRestartMainActivityAfterExitingProcess = false
    override fun onCreate() {
        //Lumen (TV) or Wisp (phone): read everywhere, so set it before anything else runs
        Brand.phone = BuildConfig.IS_WISP
        Log.i(TAG, "onCreate")
        super.onCreate()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            //we need this since when targetSdkVersion >= 33 then
            //deprecated WebSettingsCompat.setForceDark stops working on android 9 and below
            applicationInfo.targetSdkVersion = 32
        }

        instance = this
        com.phlox.tvwebbrowser.sofa.LumenCrashLog.install(this)

        AppContext.init(this, Config(getSharedPreferences(MAIN_PREFS_NAME, MODE_MULTI_PROCESS)))
        if (Brand.phone) setUpWispPrivateData()

        val maxThreadsInOfflineJobsPool = Runtime.getRuntime().availableProcessors()
        threadPool = ThreadPoolExecutor(0, maxThreadsInOfflineJobsPool, 20,
                TimeUnit.SECONDS, ArrayBlockingQueue(maxThreadsInOfflineJobsPool))

        repairWebEngineIfRequested()
        initWebEngineStuff()

        initNotificationChannels()

        ActiveModelsRepository.init(this)

        when (AppContext.provideConfig().theme.value) {
            Config.Theme.BLACK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            Config.Theme.WHITE -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        registerActivityLifecycleCallbacks(this)
    }

    /**
     * Lumen: "Repair web engine". Before any WebView is created, deletes the engine's graphics
     * (shader) caches, video statistics and cached files. Cookies, site storage and logins stay.
     */
    private fun repairWebEngineIfRequested() {
        val p = getSharedPreferences(REPAIR_PREFS, android.content.Context.MODE_PRIVATE)
        if (!p.getBoolean(REPAIR_KEY, false)) return
        p.edit().putBoolean(REPAIR_KEY, false).commit()
        val wipe = Regex("(?i)^(gpucache|shadercache|grshadercache|graphitedawncache|dawn.*cache|code cache|media history.*|videodecodestats.*|media_history.*|cache|blob_storage)$")
        var deleted = 0
        fun walk(dir: java.io.File, depth: Int) {
            if (depth > 4) return
            dir.listFiles()?.forEach { f ->
                if (wipe.matches(f.name)) {
                    if (f.deleteRecursively()) deleted++
                } else if (f.isDirectory) walk(f, depth + 1)
            }
        }
        try {
            dataDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("app_webview") }?.forEach { walk(it, 0) }
            //the HTTP cache lives in the cache folder
            cacheDir.listFiles()?.forEach { if (it.name.contains("webview", true) || it.name == "org.chromium.android_webview") it.deleteRecursively() }
            codeCacheDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            Log.e(TAG, "web engine repair", e)
        }
        Log.i(TAG, "web engine repaired: $deleted folders deleted")
        repairDone = true
    }

    @Suppress("KotlinConstantConditions")
    /**
     * Wisp runs in one process and is always private. Its web data (cookies, site storage, cache)
     * lives in its own WebView folder; "Quit" empties it: the folder is deleted here, at the next
     * start, before the web engine is used (it can't be deleted while pages use it).
     */
    private fun setUpWispPrivateData() {
        val p = getSharedPreferences(WISP_PRIVATE_PREFS, MODE_PRIVATE)
        if (p.getBoolean(WISP_WIPE_KEY, false)) {
            val suffix = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "_incognito" else ""
            val dirs = listOf(
                java.io.File(filesDir.parentFile, "app_webview$suffix"),
                java.io.File(cacheDir, "WebView$suffix"),
                java.io.File(cacheDir, "webview$suffix"))
            for (d in dirs) try { d.deleteRecursively() } catch (e: Exception) { Log.e(TAG, "wipe", e) }
            p.edit().putBoolean(WISP_WIPE_KEY, false).commit()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.webkit.WebView.setDataDirectorySuffix("incognito")
            } catch (e: Exception) {
                Log.e(TAG, "WebView data folder", e)
            }
        }
    }

    private fun initWebEngineStuff() {
        Log.i(TAG, "initWebEngineStuff")

        try {
            Class.forName("com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(e) // WebViews are always available
        }
        try {
            Class.forName("com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "GeckoWebEngine not found")//it is ok
        }

        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)
        FaviconsPool.databaseDelegate = object : FaviconsPool.DatabaseDelegate {
            override fun findByHostName(host: String): HostConfig? {
                return AppDatabase.db.hostsDao().findByHostName(host)
            }

            override suspend fun update(hostConfig: HostConfig) {
                AppDatabase.db.hostsDao().update(hostConfig)
            }

            override suspend fun insert(newHostConfig: HostConfig) {
                AppDatabase.db.hostsDao().insert(newHostConfig)
            }
        }
    }

    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.downloads)
            val descriptionText = getString(R.string.downloads_notifications_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID_DOWNLOADS, name, importance)
            channel.description = descriptionText
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        Log.i(TAG, "onActivityDestroyed: " + activity.javaClass.simpleName)
        if (needToExitProcessAfterMainActivityFinish && activity is MainActivity) {
            Log.i(TAG, "onActivityDestroyed: exiting process")
            if (needRestartMainActivityAfterExitingProcess) {
                Log.i(TAG, "onActivityDestroyed: restarting main activity")
                val intent = Intent(this@TVBro, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            }
            exitProcess(0)
        }
    }
}
