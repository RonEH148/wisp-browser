package com.phlox.tvwebbrowser.activity.main

import android.net.Uri
import android.widget.Toast
import com.brave.adblock.AdBlockClient
import com.brave.adblock.AdBlockClient.FilterOption
import com.brave.adblock.Utils
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.sofa.LumenAdblock
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.*

class AdblockModel : ActiveModel() {
    companion object {
        val TAG: String = AdblockModel::class.java.simpleName

        const val SERIALIZED_LIST_FILE = "adblock_ser.dat"
        //Lumen: adblock-rust engine, saved after parsing (loads in a moment on the next start)
        const val RUST_ENGINE_FILE = "adblock_rust.dat"
        const val RUST_LISTS_FILE = "adblock_lists.txt"
        const val AUTO_UPDATE_INTERVAL_MINUTES = 60 * 24 * 30 //30 days
    }

    private var client: AdBlockClient? = null
    private val MAX_LIST_CHARS = 15 * 1024 * 1024
    val clientLoading = ObservableValue(false)
    /** Which engine blocks ads now (shown in Settings). */
    val engineName: String
        get() = if (LumenAdblock.active) "adblock-rust" else if (client != null) "basic" else "loading"
    val config = AppContext.provideConfig()

    init {
        loadAdBlockList(false)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun loadAdBlockList(forceReload: Boolean) = modelScope.launch {
        if (clientLoading.value) return@launch
        val checkDate = Calendar.getInstance()
        checkDate.timeInMillis = config.adBlockListLastUpdate
        checkDate.add(Calendar.MINUTE, AUTO_UPDATE_INTERVAL_MINUTES)
        val now = Calendar.getInstance()
        val needUpdate = forceReload || checkDate.before(now)
        clientLoading.value = true
        val client = AdBlockClient()
        var success = false
        var rustLoaded = false
        var keepOldDate = false
        withContext(Dispatchers.IO) ioContext@ {
            val serializedFile = File(TVBro.instance.filesDir, SERIALIZED_LIST_FILE)
            val rustFile = File(TVBro.instance.filesDir, RUST_ENGINE_FILE)
            if (!needUpdate && LumenAdblock.available) {
                val handle = LumenAdblock.load(rustFile)
                if (handle != 0L) {
                    LumenAdblock.install(handle)
                    rustLoaded = true
                    success = true
                    return@ioContext
                }
            }
            if ((!needUpdate) && !LumenAdblock.available && serializedFile.exists() &&
                client.deserialize(serializedFile.absolutePath)) {
                success = true
                return@ioContext
            }
            try {
                val urls = config.adBlockListURL.value.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
                //builds up to 113: the uBlock list was there without its "badware" list (scam and fake-prompt sites)
                val ub = "https://ublockorigin.github.io/uAssets/filters/filters.txt"
                val badware = "https://ublockorigin.github.io/uAssets/filters/badware.txt"
                if (ub in urls && badware !in urls) urls.add(urls.indexOf(ub) + 1, badware)
                val combined = StringBuilder()
                var loadedLists = 0
                for (listUrl in urls) {
                    try {
                        val connection = URL(listUrl).openConnection()
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        //read at most MAX_LIST_CHARS per list, so a wrong or huge address can't use up the memory
                        connection.inputStream.bufferedReader().use { reader ->
                            val buf = CharArray(64 * 1024)
                            var total = 0
                            while (total < MAX_LIST_CHARS) {
                                val n = reader.read(buf)
                                if (n <= 0) break
                                combined.append(buf, 0, n)
                                total += n
                            }
                        }
                        combined.append('\n')
                        loadedLists++
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (loadedLists > 0 && LumenAdblock.available) {
                    val listsFile = File(TVBro.instance.filesDir, RUST_LISTS_FILE)
                    try {
                        listsFile.bufferedWriter().use { it.append(combined) }
                        val handle = LumenAdblock.create(listsFile)
                        if (handle != 0L) {
                            LumenAdblock.save(handle, rustFile)
                            LumenAdblock.install(handle)
                            rustLoaded = true
                            success = true
                        }
                    } finally {
                        listsFile.delete()
                    }
                }
                if (!rustLoaded) {
                    if (loadedLists > 0) {
                        success = client.parse(combined.toString())
                        client.serialize(serializedFile.absolutePath)
                    } else if (serializedFile.exists() && client.deserialize(serializedFile.absolutePath)) {
                        //offline: keep blocking with the lists saved last time, try again next start
                        success = true
                        keepOldDate = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        //with adblock-rust in use the older engine is not kept in memory
        this@AdblockModel.client = if (rustLoaded) null else client
        if (!keepOldDate) config.adBlockListLastUpdate = now.timeInMillis
        if (!success) {
            Toast.makeText(TVBro.instance, "Couldn't load the ad blocker list", Toast.LENGTH_SHORT).show()
        }
        clientLoading.value = false
    }

    fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean {
        if (LumenAdblock.active) {
            val host = url.host ?: ""
            if (host == "challenges.cloudflare.com" || url.path?.startsWith("/cdn-cgi/challenge-platform") == true ||
                host.endsWith("hcaptcha.com") || url.path?.startsWith("/recaptcha/") == true) {
                return false
            }
            return try {
                LumenAdblock.matches(url.toString(), baseUri.toString(), rustRequestType(url, type))
            } catch (e: Exception) {
                false
            }
        }
        val client = client ?: return false
        //never block human-verification / anti-bot challenge resources, otherwise checks fail
        val host = url.host ?: ""
        if (host == "challenges.cloudflare.com" || url.path?.startsWith("/cdn-cgi/challenge-platform") == true ||
            host.endsWith("hcaptcha.com") || url.path?.startsWith("/recaptcha/") == true) {
            return false
        }
        val baseHost = baseUri.host
        val filterOption = try {
            mapRequestToFilterOption(url, type)
        } catch (e: Exception) {
            return false
        }
        val result = try {
            baseHost != null && client.matches(url.toString(), filterOption, baseHost)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        return result
    }

    /** Request type in adblock-rust's words, from the Accept header and the file extension. */
    private fun rustRequestType(url: Uri, accept: String?): String {
        val a = accept?.lowercase() ?: ""
        when {
            a.startsWith("text/html") -> return "sub_frame"
            a.startsWith("image/") || (a.contains("image/") && !a.contains("text/html")) -> return "image"
            a.contains("text/css") -> return "stylesheet"
            a.contains("javascript") -> return "script"
            a.startsWith("video/") || a.startsWith("audio/") -> return "media"
        }
        val ext = (url.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js", "mjs" -> "script"
            "css" -> "stylesheet"
            "png", "jpg", "jpeg", "webp", "avif", "svg", "gif", "bmp", "ico" -> "image"
            "mp4", "webm", "mov", "avi", "mp3", "m4a", "ogg" -> "media"
            "woff", "woff2", "ttf", "otf", "eot" -> "font"
            "json" -> "xmlhttprequest"
            else -> "other"
        }
    }

    private fun mapRequestToFilterOption(url: Uri?, type: String?): FilterOption? {
        if (type != null) {
            if (type == "image" || type.contains("image/")) {
                return FilterOption.IMAGE
            }
            if (type == "style" || type.contains("/css")) {
                return FilterOption.CSS
            }
            if (type == "script" || type.contains("javascript")) {
                return FilterOption.SCRIPT
            }
            if (type.contains("video/")) {
                return FilterOption.OBJECT
            }
        }
        if (url != null) {
            if (Utils.uriHasExtension(url, "css")) {
                return FilterOption.CSS
            }
            if (Utils.uriHasExtension(url, "js")) {
                return FilterOption.SCRIPT
            }
            if (Utils.uriHasExtension(
                    url,
                    "png",
                    "jpg",
                    "jpeg",
                    "webp",
                    "svg",
                    "gif",
                    "bmp",
                    "tiff"
                )
            ) {
                return FilterOption.IMAGE
            }
            if (Utils.uriHasExtension(url, "mp4", "mov", "avi")) {
                return FilterOption.OBJECT
            }
        }
        return FilterOption.UNKNOWN
    }
}