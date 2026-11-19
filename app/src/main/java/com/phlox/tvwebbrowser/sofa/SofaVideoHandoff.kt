package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.R
import java.util.Collections
import java.util.Locale

/**
 * Hands a web video over to the native 4K player or to an external player app.
 *
 * Sites that use Media Source Extensions play from a "blob:" URL that no other player can open,
 * so the browser also remembers the stream files (HLS .m3u8, DASH .mpd, MP4, WebM, MKV) each page
 * requests and offers those instead.
 */
object SofaVideoHandoff {
    const val MODE_NATIVE = "native"
    const val MODE_EXTERNAL = "external"
    /** Lumen's player with anime deband switched on from the start. */
    const val MODE_NATIVE_DEBAND = "native_deband"
    /** Lumen's player, every piece downloaded by the open page itself (sites that refuse other players). */
    const val MODE_NATIVE_PAGE = "native_page"

    /** Should this site's streamed videos play in Lumen's player, downloaded by the page? */
    fun wantsPagePlayer(context: Context, host: String?): Boolean {
        if (!LumenPageFetch.supported) return false
        val config = com.phlox.tvwebbrowser.AppContext.provideConfig()
        return when (config.pagePlayerModeFor(host)) {
            "on" -> true
            "off" -> false
            else -> config.lumenTvPagePlayer && com.phlox.tvwebbrowser.utils.Utils.isTV(context)
        }
    }

    /** When a player was last opened (several frames of a page may answer the same request). */
    @Volatile var lastLaunchAt = 0L

    data class Stream(
        val url: String,
        val title: String,
        val referer: String?,
        val userAgent: String?,
        val cookies: String?,
        val positionMs: Long,
        /** Request headers the page's own player used for this stream (Origin, Referer, ...). */
        val headers: Map<String, String> = emptyMap(),
        /** Subtitle files the page loaded (VTT, SRT, ASS, TTML), offered in the player. */
        val subtitles: List<String> = emptyList()
    )

    enum class MediaKind(val label: String) { VIDEO("Video"), AUDIO("Audio"), SUBTITLE("Subtitles") }

    class DetectedMedia(val url: String, val referer: String?, val time: Long,
                        val kind: MediaKind = MediaKind.VIDEO,
                        val headers: Map<String, String> = emptyMap())

    /** Thread-safe list of media requests seen by one tab; newest last. */
    class MediaLog {
        private val items = Collections.synchronizedList(ArrayList<DetectedMedia>())

        /** Called (on any thread) whenever the list changes; used for the count on the grab button. */
        @Volatile var onChanged: (() -> Unit)? = null

        val size: Int get() = items.size

        fun clear() {
            val had = items.isNotEmpty()
            items.clear()
            if (had) onChanged?.invoke()
        }

        fun onRequest(url: String, pageUrl: String?, headers: Map<String, String>? = null) {
            val kind = classify(url) ?: return
            add(normalize(url), pageUrl, kind, headers)
        }

        fun add(url: String, pageUrl: String?, kind: MediaKind, headers: Map<String, String>? = null) {
            synchronized(items) {
                //keep headers seen earlier for the same address (a later DOM scan has none)
                val old = items.firstOrNull { it.url == url }
                val keep = headers?.takeIf { it.isNotEmpty() } ?: old?.headers ?: emptyMap()
                items.removeAll { it.url == url }
                items.add(DetectedMedia(url, pageUrl ?: old?.referer, System.currentTimeMillis(), kind, keep))
                while (items.size > 100) items.removeAt(0)
            }
            onChanged?.invoke()
        }

        /** Videos only, manifests first (newest first), then progressive files. */
        fun candidates(): List<DetectedMedia> {
            val copy = synchronized(items) { ArrayList(items) }
            copy.reverse()
            return copy.filter { it.kind == MediaKind.VIDEO }.sortedBy { if (isManifest(it.url)) 0 else 1 }
        }

        fun find(url: String): DetectedMedia? = synchronized(items) { items.lastOrNull { it.url == url } }

        /** Subtitle files of the page, newest first (thumbnail/preview "VTT" sprites left out). */
        fun subtitles(): List<String> = all().filter { it.kind == MediaKind.SUBTITLE }.map { it.url }
            .filterNot { u -> THUMB_VTT_RE.containsMatchIn(u) }.distinct().take(12)

        /** Everything captured (video, audio, subtitles), grouped by kind, newest first. */
        fun all(): List<DetectedMedia> {
            val copy = synchronized(items) { ArrayList(items) }
            copy.reverse()
            return copy.sortedBy { it.kind.ordinal }
        }
    }

    private val THUMB_VTT_RE = Regex("thumb|sprite|preview|storyboard", RegexOption.IGNORE_CASE)
    private val AUDIO_RE = Regex("""\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|weba|mka)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val NUMBERED_PIECE_RE = Regex("""(seg|chunk|frag|segment|part)[-_]?\d+.*\.(aac|vtt|webvtt)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val SUBTITLE_RE = Regex("""\.(vtt|webvtt|srt|ass|ssa|ttml|dfxp|sbv|sub)(\?|#|$)""", RegexOption.IGNORE_CASE)

    /** Video, audio or subtitle file/stream; null for anything else (and for stream segments). */
    fun classify(url: String): MediaKind? {
        if (!url.startsWith("http")) return null
        val lower = url.lowercase(Locale.ROOT)
        //pieces of a stream (HLS/DASH segments) are not useful on their own
        if (SEGMENT_RE.containsMatchIn(url) || NUMBERED_PIECE_RE.containsMatchIn(url) ||
            lower.substringBefore('?').endsWith(".m4s")) return null
        if (SUBTITLE_RE.containsMatchIn(url) || lower.contains("/api/timedtext") ||
            lower.contains("fmt=vtt") || lower.contains("format=vtt") || lower.contains("fmt=srv3")) return MediaKind.SUBTITLE
        //YouTube-style "videoplayback?mime=video/..." streams
        if (lower.contains("mime=audio%2f") || lower.contains("mime=audio/")) return MediaKind.AUDIO
        if (lower.contains("mime=video%2f") || lower.contains("mime=video/")) return MediaKind.VIDEO
        if (AUDIO_RE.containsMatchIn(url)) return MediaKind.AUDIO
        if (isStreamUrl(url)) return MediaKind.VIDEO
        return null
    }

    /** Drops per-request byte-range parameters so one stream shows up once. */
    private fun normalize(url: String): String {
        if (!url.contains("videoplayback")) return url
        return url.replace(Regex("""[&?](range|rn|rbuf|ump|srfvp)=[^&]*"""), "").let {
            if (!it.contains('?') && it.contains('&')) it.replaceFirst('&', '?') else it
        }
    }

    private val STREAM_RE = Regex("""\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|ts)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val SEGMENT_RE = Regex("""(seg|chunk|frag|segment)[-_]?\d+.*\.(ts|mp4|m4s)|/\d+\.ts(\?|$)""", RegexOption.IGNORE_CASE)

    fun isManifest(url: String): Boolean {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    fun isStreamUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (!STREAM_RE.containsMatchIn(url)) return false
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        if (path.endsWith(".ts")) return false //HLS segment, not playable on its own
        if (SEGMENT_RE.containsMatchIn(url)) return false
        return true
    }

    fun guessMimeType(url: String): String? {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".mov") -> "video/quicktime"
            else -> null
        }
    }

    /**
     * Entry point used by the video bar. [src] is the video element's currentSrc, which may be a
     * blob: URL. Shows a picker when several detected streams could be the right one.
     */
    fun open(activity: Activity, mode: String, src: String?, pageUrl: String?, title: String?,
             positionSec: Double, userAgent: String?, log: MediaLog?) {
        val positionMs = (positionSec * 1000).toLong().coerceAtLeast(0L)
        val direct = src?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (direct != null) {
            val known = log?.find(direct)
            launch(activity, mode, makeStream(direct, known?.referer ?: pageUrl, title, userAgent, positionMs, known?.headers, log?.subtitles().orEmpty()))
            return
        }
        val candidates = log?.candidates().orEmpty()
        when {
            candidates.isEmpty() -> {
                Toast.makeText(activity, R.string.sofa_no_stream_found, Toast.LENGTH_LONG).show()
            }
            candidates.size == 1 || autoPick -> {
                //started by itself (Settings → Videos from web pages): no question. A page loads its
                //main playlist (all qualities) first, so the oldest playlist is the one to play.
                val manifests = candidates.filter { isManifest(it.url) }
                val c = if (manifests.isNotEmpty()) manifests.last() else candidates[0]
                launch(activity, mode, makeStream(c.url, c.referer ?: pageUrl, title, userAgent, positionMs, c.headers, log?.subtitles().orEmpty()))
            }
            else -> {
                val labels = candidates.map { describe(it.url) }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle(R.string.sofa_choose_stream)
                    .setItems(labels) { _, which ->
                        val c = candidates[which]
                        launch(activity, mode, makeStream(c.url, c.referer ?: pageUrl, title, userAgent, positionMs, c.headers, log?.subtitles().orEmpty()))
                    }
                    .show()
            }
        }
    }

    private fun describe(url: String): String {
        val uri = Uri.parse(url)
        val kind = when {
            url.contains(".m3u8", true) -> "HLS"
            url.contains(".mpd", true) -> "DASH"
            else -> (uri.lastPathSegment?.substringAfterLast('.', "")?.uppercase(Locale.ROOT) ?: "FILE")
        }
        val name = uri.lastPathSegment ?: url
        return "$kind · ${uri.host} · $name"
    }

    /** Headers that must not be copied (the player sets them itself or they'd break the request). */
    private val SKIPPED_HEADERS = setOf("range", "host", "content-length", "accept-encoding", "connection",
        "cookie", "user-agent", "x-requested-with", "if-none-match", "if-modified-since")

    private fun makeStream(url: String, referer: String?, title: String?, userAgent: String?, positionMs: Long,
                           seenHeaders: Map<String, String>? = null, subs: List<String> = emptyList()) =
        Stream(
            url = url,
            title = title ?: "",
            referer = referer,
            userAgent = userAgent,
            cookies = try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null },
            positionMs = positionMs,
            headers = playerHeaders(url, referer, seenHeaders),
            subtitles = subs
        )

    /**
     * The headers the site's own player sent. Many video hosts refuse a stream unless Origin and
     * Referer match the page that embeds the player, which is why such links "don't play anywhere else".
     */
    private fun playerHeaders(url: String, referer: String?, seen: Map<String, String>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        seen?.forEach { (k, v) -> if (k.lowercase(Locale.ROOT) !in SKIPPED_HEADERS) out[k] = v }
        if (out.keys.none { it.equals("Referer", true) } && !referer.isNullOrBlank()) out["Referer"] = referer
        val ref = out.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        if (out.keys.none { it.equals("Origin", true) } && ref != null) {
            try {
                val r = Uri.parse(ref)
                val target = Uri.parse(url)
                //browsers send Origin on cross-site player requests
                if (r.scheme != null && r.host != null && r.host != target.host) {
                    out["Origin"] = r.scheme + "://" + r.host + (if (r.port > 0) ":" + r.port else "")
                }
            } catch (ignored: Exception) {
            }
        }
        return out
    }

    private fun launch(activity: Activity, mode: String, stream: Stream) {
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < 2000) return
        lastLaunchAt = now
        when (mode) {
            MODE_NATIVE_PAGE -> startPagePlayer(activity, stream)
            MODE_EXTERNAL -> openExternal(activity, stream)
            MODE_NATIVE_DEBAND -> SofaPlayerActivity.start(activity, stream)
            else -> SofaPlayerActivity.start(activity, stream)
        }
    }

    /**
     * Lumen's player with every playlist and video piece downloaded by the frame of the page that
     * played the video (its own connection, cookies and tokens), see [LumenPageFetch].
     */
    private fun startPagePlayer(activity: Activity, stream: Stream) {
        val config = com.phlox.tvwebbrowser.AppContext.provideConfig()
        val fromHeader = stream.headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        val origin = listOfNotNull(LumenPageFetch.originOf(fromHeader), LumenPageFetch.originOf(stream.referer))
            .firstOrNull { LumenPageFetch.hasFrame(it) }
        val host = try { Uri.parse(stream.referer).host } catch (e: Exception) { null }
        val lines = config.ivtcModeFor(host).isNotEmpty()
        if (origin == null) {
            //the frame isn't reachable: download it directly instead
            LumenStreamProxy.note("PAGE PLAYER: no open frame of ${fromHeader ?: stream.referer} - direct download")
            SofaPlayerActivity.start(activity, stream, lines = lines)
            return
        }
        val upstreamHeaders = LinkedHashMap(stream.headers)
        val playUrl = LumenStreamProxy.wrap(activity, stream.url, upstreamHeaders, stream.userAgent, pageOrigin = origin)
        val subs = stream.subtitles.take(8).map {
            LumenStreamProxy.wrap(activity, it, upstreamHeaders, stream.userAgent, pageOrigin = origin)
        }
        SofaPlayerActivity.start(activity, stream.copy(url = playUrl, headers = emptyMap(), cookies = null, subtitles = subs),
            pageFetch = true, lines = lines)
    }

    /** True when the page loaded an HLS/DASH playlist (the page player can take over). */
    fun hasManifest(log: MediaLog?): Boolean = log?.candidates()?.any { isManifest(it.url) } == true

    /** Just Player (also self-built copies of it), if installed. */
    fun justPlayerPackage(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/v.mkv"), "video/*")
        return pm.queryIntentActivities(probe, android.content.pm.PackageManager.MATCH_ALL).firstOrNull {
            val pkg = it.activityInfo.packageName
            pkg.contains("brouken") || it.loadLabel(pm).toString().contains("Just Player", ignoreCase = true)
        }?.activityInfo?.packageName
    }

    /** Not handed over: the video stays in the page. */
    const val MODE_PAGE = "page"
    /** Android's app chooser each time. */
    const val MODE_ASK = "ask"

    /**
     * Where a page video goes. [reason]: "play" (any real video started), "4k" or "unsupported".
     * Setting values: "" automatic (only 4K/unsupported, to Just Player if installed),
     * "external_all" / "ask_all" / "native_all" (every video), "page" (never), and the older
     * "external" / "native" (only 4K/unsupported).
     */
    fun videoMode(context: Context, reason: String?, host: String? = null, allowPagePlayer: Boolean = true): String {
        val config = com.phlox.tvwebbrowser.AppContext.provideConfig()
        //Wisp (phone): videos always play in the page, like in any phone browser (the hand-offs
        //to a player app exist for TVs)
        if (com.phlox.tvwebbrowser.Brand.phone) return MODE_PAGE
        //sites set to "always play videos in Just Player" (Site fixes)
        if (config.isExternalVideoSite(host)) return MODE_EXTERNAL
        //TVs: the TV's video chip shows some streams broken inside web pages (blocks, green
        //patches) while players show them cleanly: Lumen's player, the page still downloading
        if (allowPagePlayer && wantsPagePlayer(context, host)) return MODE_NATIVE_PAGE
        val pref = config.lumenBigVideoPlayer
        val auto = if (justPlayerPackage(context) != null) MODE_EXTERNAL else MODE_NATIVE
        return when (pref) {
            "page" -> MODE_PAGE
            "external_all" -> MODE_EXTERNAL
            "ask_all" -> MODE_ASK
            "native_all" -> MODE_NATIVE
            "external" -> if (reason == "play") MODE_PAGE else MODE_EXTERNAL
            "native" -> if (reason == "play") MODE_PAGE else MODE_NATIVE
            else -> if (reason == "play") MODE_PAGE else auto
        }
    }

    /** 4K or unsupported video in a page: straight to Just Player if installed, else the chooser. */
    fun openBig(activity: Activity, mode: String, src: String?, pageUrl: String?, title: String?,
                positionSec: Double, userAgent: String?, log: MediaLog?) {
        preferJustPlayer = mode == MODE_EXTERNAL
        autoPick = true
        try {
            open(activity, if (mode == MODE_ASK) MODE_EXTERNAL else mode, src, pageUrl, title, positionSec, userAgent, log)
        } finally {
            preferJustPlayer = false
            autoPick = false
        }
    }

    @Volatile private var preferJustPlayer = false
    @Volatile private var autoPick = false

    /** Always shows Android's app chooser so the player can be picked each time. */
    fun openExternal(context: Context, stream: Stream) {
        //through Lumen's proxy: the player gets the stream with the page's Referer/Origin, cookies,
        //browser name and a browser-like connection (otherwise many servers answer HTTP 403)
        val upstreamHeaders = LinkedHashMap(stream.headers)
        if (upstreamHeaders.keys.none { it.equals("Referer", true) }) stream.referer?.let { upstreamHeaders["Referer"] = it }
        val playUrl = try {
            LumenStreamProxy.wrap(context, stream.url, upstreamHeaders, stream.userAgent)
        } catch (e: Exception) {
            stream.url
        }
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(playUrl), guessMimeType(stream.url) ?: "video/*")
        if (playUrl == stream.url) {
            //no proxy: hand the page's headers to the player itself (Just Player URL / MX style "headers")
            val h = ArrayList<String>()
            upstreamHeaders.forEach { (k, v) -> h.add(k); h.add(v) }
            stream.userAgent?.let { h.add("User-Agent"); h.add(it) }
            stream.cookies?.takeIf { it.isNotBlank() }?.let { h.add("Cookie"); h.add(it) }
            intent.putExtra("headers", h.toTypedArray())
        }
        intent.putExtra("title", stream.title)
        intent.putExtra(Intent.EXTRA_TITLE, stream.title)
        if (stream.positionMs > 0) intent.putExtra("position", stream.positionMs.toInt())
        //subtitle files the page loaded (MX Player / Just Player style extras)
        if (stream.subtitles.isNotEmpty()) {
            val subs = stream.subtitles.take(8).map {
                Uri.parse(try { LumenStreamProxy.wrap(context, it, upstreamHeaders, stream.userAgent) } catch (e: Exception) { it })
            }
            intent.putExtra("subs", subs.toTypedArray<android.os.Parcelable>())
            intent.putExtra("subs.name", stream.subtitles.take(8).map { Uri.parse(it).lastPathSegment ?: "Subtitles" }.toTypedArray())
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        //Player apps are found with the stream's own address: their filters may not accept the
        //local proxy address, and many TVs have no system app chooser. The chosen player is then
        //started directly (an explicit intent doesn't depend on its filters).
        val pm = context.packageManager
        val mime = guessMimeType(stream.url) ?: "video/*"
        fun query(type: String) = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(stream.url), type), android.content.pm.PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
        val apps = (query(mime) + query("video/*")).distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
        if (apps.isEmpty()) {
            Toast.makeText(context, R.string.sofa_no_player_app, Toast.LENGTH_LONG).show()
            return
        }
        fun launch(info: android.content.pm.ResolveInfo) {
            try {
                context.startActivity(Intent(intent).setClassName(info.activityInfo.packageName, info.activityInfo.name))
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.sofa_no_player_app) + "\n" + e.message, Toast.LENGTH_LONG).show()
            }
        }
        if (preferJustPlayer) {
            apps.firstOrNull {
                it.activityInfo.packageName.contains("brouken") ||
                        it.loadLabel(pm).toString().contains("Just Player", ignoreCase = true)
            }?.let { launch(it); return }
        }
        if (apps.size == 1 || context !is Activity) {
            launch(apps[0])
            return
        }
        val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.sofa_open_with)
            .setItems(labels) { _, which -> launch(apps[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
