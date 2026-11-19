package com.phlox.tvwebbrowser.activity.main

import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.deleteDirectory
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivityViewModel: ActiveModel() {
    companion object {
        var TAG: String = MainActivityViewModel::class.java.simpleName
        const val WEB_VIEW_DATA_FOLDER = "app_webview"
        const val HOME_PAGE_SLOTS = 8
        const val WEB_VIEW_CACHE_FOLDER = "WebView"
        const val WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX = "_backup"
        const val INCOGNITO_DATA_DIRECTORY_SUFFIX = "incognito"
    }

    var loaded = false
    var lastHistoryItem: HistoryItem? = null
    private var lastHistoryItemSaveJob: Job? = null
    val homePageLinks = ObservableList<HomePageLink>()
    val config = AppContext.provideConfig()

    fun loadState() = modelScope.launch(Dispatchers.Main) {
        Log.d(TAG, "loadState")
        if (loaded) return@launch
        checkVersionCodeAndRunMigrations()
        initHistory()
        loadHomePageLinks()
        loaded = true
    }

    private suspend fun checkVersionCodeAndRunMigrations() {
        Log.d(TAG, "checkVersionCodeAndRunMigrations")
        if (config.appVersionCodeMark != BuildConfig.VERSION_CODE) {
            Log.i(TAG, "App version code changed from ${config.appVersionCodeMark} to ${BuildConfig.VERSION_CODE}")
            config.appVersionCodeMark = BuildConfig.VERSION_CODE
            withContext(Dispatchers.IO) {
                //remove the downloaded update APK once it's installed
                listOfNotNull(TVBro.instance.externalCacheDir, TVBro.instance.cacheDir).forEach {
                    java.io.File(it, "lumen-update.apk").delete()
                }
            }
        }
    }

    private suspend fun initHistory() {
        Log.d(TAG, "initHistory")
        val count = AppDatabase.db.historyDao().count()
        if (count > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            AppDatabase.db.historyDao().deleteWhereTimeLessThan(c.time.time)
        }
        try {
            val result = AppDatabase.db.historyDao().last()
            if (result.isNotEmpty()) {
                lastHistoryItem = result[0]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Re-reads bookmarks for the home page, e.g. after one was added. */
    fun refreshHomePageLinks() = modelScope.launch(Dispatchers.Main) { loadHomePageLinks() }

    /** Deletes the sample bookmarks TV Bro used to add on first start (once). */
    private suspend fun removeDefaultSuggestionsOnce(config: Config) {
        config.initialBookmarksSuggestionsLoaded = true //never add them again
        val prefs = AppContext.get().getSharedPreferences("lumen_flags", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("defaults_removed", false)) return
        val defaults = setOf(
            "https://www.wikipedia.org", "https://www.reddit.com", "https://mytuner-radio.com/",
            "https://www.instagram.com", "https://www.imdb.com/", "https://www.ebay.com/",
            "https://itch.io/games/input-gamepad/platform-web", "https://www.bbc.com/news"
        )
        val dao = AppDatabase.db.favoritesDao()
        for (f in dao.getHomePageBookmarks()) {
            if (f.url in defaults) dao.delete(f.id)
        }
        prefs.edit().putBoolean("defaults_removed", true).apply()
    }

    private suspend fun loadHomePageLinks() {
        Log.d(TAG, "loadHomePageLinks")
        val config = AppContext.provideConfig()
        if (config.homePageMode == Config.HomePageMode.HOME_PAGE) {
            when (config.homePageLinksMode) {
                Config.HomePageLinksMode.MOST_VISITED -> {
                    homePageLinks.replaceAll(
                        AppDatabase.db.historyDao().frequentlyUsedUrls()
                            .map { HomePageLink.fromHistoryItem(it) })
                }
                Config.HomePageLinksMode.LATEST_HISTORY -> {
                    homePageLinks.replaceAll(
                        AppDatabase.db.historyDao().last(8)
                            .map { HomePageLink.fromHistoryItem(it) })
                }
                Config.HomePageLinksMode.BOOKMARKS -> {
                    //Lumen: the home page shows your own bookmarks (no built-in suggestions)
                    removeDefaultSuggestionsOnce(config)
                    val dao = AppDatabase.db.favoritesDao()
                    val items = ArrayList<FavoriteItem>()
                    items.addAll(dao.getHomePageBookmarks())
                    for (f in dao.getAll(false)) {
                        val url = f.url ?: continue
                        if (f.isFolder || url.isBlank()) continue
                        if (items.none { it.url == url }) items.add(f)
                    }
                    val links = items.take(HOME_PAGE_SLOTS).mapIndexed { i, f ->
                        HomePageLink(f.title ?: "", f.url ?: "", f.favicon, f.id, i, f.destUrl, f.description, f.validUntil)
                    }
                    //don't notify (and reload the home page) when nothing changed
                    val same = links.size == homePageLinks.size && links.indices.all {
                        links[it].url == homePageLinks[it].url && links[it].title == homePageLinks[it].title &&
                                links[it].favicon == homePageLinks[it].favicon
                    }
                    if (!same) homePageLinks.replaceAll(links)
                }
            }
        }
    }

    fun logVisitedHistory(title: String?, url: String, faviconHash: String?) {
        Log.d(TAG, "logVisitedHistory: $url")
        if ((url == lastHistoryItem?.url) || url == Config.HOME_PAGE_URL || !url.startsWith("http", true)) {
            return
        }

        val now = System.currentTimeMillis()
        val minVisitedInterval = 5000L //5 seconds

        lastHistoryItem?.let {
            if ((!it.saved) && (it.time + minVisitedInterval) > now) {
                lastHistoryItemSaveJob?.cancel()
            }
        }

        val item = HistoryItem()
        item.url = url
        item.title = title ?: ""
        item.time = now
        item.favicon = faviconHash
        lastHistoryItem = item
        lastHistoryItemSaveJob = modelScope.launch(Dispatchers.Main) {
            delay(minVisitedInterval)
            item.id = AppDatabase.db.historyDao().insert(item)
            item.saved = true
        }
    }

    fun onTabTitleUpdated(tab: WebTabState) {
        Log.d(TAG, "onTabTitleUpdated: ${tab.url} ${tab.title}")
        if (AppContext.provideConfig().incognitoMode && !com.phlox.tvwebbrowser.Brand.phone) return
        val lastHistoryItem = lastHistoryItem ?: return
        if (tab.url == lastHistoryItem.url) {
            lastHistoryItem.title = tab.title
            if (lastHistoryItem.saved) {
                modelScope.launch(Dispatchers.Main) {
                    AppDatabase.db.historyDao().updateTitle(lastHistoryItem.id, lastHistoryItem.title)
                }
            }
        }
    }

    fun prepareSwitchToIncognito() {
        Log.d(TAG, "prepareSwitchToIncognito")
        if (AppContext.provideConfig().isWebEngineGecko()) return
        //to isolate incognito mode data:
        //in api >= 28 we just use another directory for WebView data
        //on earlier apis we backup-ing existing WebView data directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val incognitoWebViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
            )
            //Always: the folder is left over when private mode wasn't closed properly, and returning
            //here then made private mode use the normal WebView folder - shared with the main
            //process ("Using WebView from more than one process ... is not supported" crash)
            if (incognitoWebViewData.exists()) Log.i(TAG, "Incognito WebView data folder already there")
            try {
                WebView.setDataDirectorySuffix(INCOGNITO_DATA_DIRECTORY_SUFFIX)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WebView data directory suffix", e)
            }
        } else {
            val webViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER
            )
            val backupedWebViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            if (backupedWebViewData.exists()) {
                Log.i(TAG, "Looks like we already in incognito mode")
                return
            }
            webViewData.renameTo(backupedWebViewData)
            val webViewCache =
                File(TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER)
            val backupedWebViewCache = File(
                TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER +
                        WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            webViewCache.renameTo(backupedWebViewCache)
        }
    }

    fun clearIncognitoData() = modelScope.launch(Dispatchers.IO) {
        Log.d(TAG, "clearIncognitoData")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val webViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
            )
            deleteDirectory(webViewData)
            var webViewCache =
                File(
                    TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER +
                            "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
                )
            if (!webViewCache.exists()) {
                webViewCache = File(
                    TVBro.instance.cacheDir.absolutePath + "/" +
                            WEB_VIEW_CACHE_FOLDER.lowercase(Locale.getDefault()) +
                            "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
                )
            }
            deleteDirectory(webViewCache)
        } else {
            val webViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER
            )
            deleteDirectory(webViewData)
            val webViewCache =
                File(TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER)
            deleteDirectory(webViewCache)

            val backupedWebViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            backupedWebViewData.renameTo(webViewData)
            val backupedWebViewCache = File(
                TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER +
                        WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            backupedWebViewCache.renameTo(webViewCache)
        }
    }

    override fun onClear() {

    }

    fun removeHomePageLink(bookmark: HomePageLink) = modelScope.launch {
        homePageLinks.remove(bookmark)
        bookmark.favoriteId?.let {
            AppDatabase.db.favoritesDao().delete(it)
        }
    }

    fun onHomePageLinkEdited(item: FavoriteItem) = modelScope.launch {
        if (item.id == 0L) {
            val lastInsertRowId = AppDatabase.db.favoritesDao().insert(item)
            item.id = lastInsertRowId
            homePageLinks.add(HomePageLink.fromBookmarkItem(item))
        } else {
            AppDatabase.db.favoritesDao().update(item)
            val index = homePageLinks.indexOfFirst { it.favoriteId == item.id }
            if (index != -1) {
                homePageLinks[index] = HomePageLink.fromBookmarkItem(item)
            }
        }
    }

    fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
        val link = homePageLinks.find { it.order == bookmarkOrder } ?: return
        val favoriteId = link.favoriteId ?: return
        if (link.validUntil == null) return
        modelScope.launch {
            AppDatabase.db.favoritesDao().markAsUseful(favoriteId)
        }
    }
}