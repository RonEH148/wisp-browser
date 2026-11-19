package com.phlox.tvwebbrowser.sofa

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lumen hands web videos to other player apps (Just Player, MX, VLC, ...) through this small proxy
 * on the TV itself (http://127.0.0.1:port/...). Video servers often refuse players with HTTP 403:
 * they want the page's Referer/Origin, its cookies, the browser's name and a Chrome-like
 * connection. The player asks the proxy, and Lumen fetches every playlist and video piece the way
 * the browser did. HLS playlists are rewritten so their segments, keys and subtitles also go
 * through the proxy.
 */
object LumenStreamProxy {
    private const val TAG = "LumenStreamProxy"

    /** [pageOrigin]: download through that open page (LumenPageFetch) instead of over the network. */
    private class Session(val headers: Map<String, String>, val userAgent: String?, val pageOrigin: String? = null) {
        @Volatile var lastUse = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null
    @Volatile var lastActivity = 0L
        private set

    /** Network stack like Chrome's when available (see [SofaPlayerActivity]); else Android's basic one. */
    @Volatile private var opener: ((URL) -> java.net.URLConnection)? = null
    @Volatile private var openerName = "basic"

    // ---- diagnostics: what happened to the last requests (Settings → Video hand-off log) ----
    private val log = java.util.ArrayDeque<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)

    fun note(line: String) {
        synchronized(log) {
            log.addLast(timeFmt.format(java.util.Date()) + "  " + line)
            while (log.size > 200) log.removeFirst()
        }
    }

    fun logText(): String = synchronized(log) { if (log.isEmpty()) "(nothing yet)" else log.joinToString("\n") }

    fun clearLog() = synchronized(log) { log.clear() }

    @Volatile private var okCount = 0
    private val notedHosts = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** The whole log as a file in Download/Lumen (to send it from a phone or computer). Returns its place. */
    fun saveLog(context: Context): String {
        val name = "lumen-video-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date()) + ".txt"
        val text = logText()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/${com.phlox.tvwebbrowser.Brand.NAME}")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Can't create the file")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "${com.phlox.tvwebbrowser.Brand.NAME}")
            dir.mkdirs()
            java.io.File(dir, name).writeText(text)
        }
        return "Download/${com.phlox.tvwebbrowser.Brand.NAME}/$name"
    }

    private fun short(url: String): String {
        return try {
            val u = URI(url)
            val path = u.rawPath ?: ""
            val tail = if (path.length > 40) "…" + path.takeLast(39) else path
            u.host + tail + (if (u.rawQuery != null) "?…" else "")
        } catch (e: Exception) { url.take(60) }
    }

    // ---- the headers the page last sent to each server ----
    // A playlist on one server (api.site) can point to another (cdn.site) that wants its own
    // headers (tokens, signatures): those of the page's latest request to that server are used.
    private class HostHeaders(val headers: Map<String, String>, val time: Long)
    private val hostHeaders = ConcurrentHashMap<String, HostHeaders>()
    private val SKIP = setOf("range", "host", "content-length", "accept-encoding", "connection", "cookie",
        "user-agent", "x-requested-with", "if-none-match", "if-modified-since", "upgrade-insecure-requests")

    fun rememberHostHeaders(url: String, headers: Map<String, String>?) {
        try {
            val u = URI(url)
            if (!u.rawQuery.isNullOrEmpty() && u.host != null) hostQuery[u.host] = u.rawQuery
        } catch (ignored: Exception) {
        }
        if (headers.isNullOrEmpty()) return
        val host = try { URI(url).host } catch (e: Exception) { null } ?: return
        val clean = headers.filterKeys { it.lowercase(Locale.ROOT) !in SKIP }
        if (clean.isEmpty()) return
        hostHeaders[host] = HostHeaders(clean, System.currentTimeMillis())
        if (hostHeaders.size > 200) hostHeaders.entries.sortedBy { it.value.time }.take(50).forEach { hostHeaders.remove(it.key) }
    }

    // ---- playlists as the page received them ----
    private class Copy(val text: String, val time: Long)
    private val copies = ConcurrentHashMap<String, Copy>()
    private const val COPY_MS = 20 * 60_000L

    fun rememberCopy(url: String, text: String) {
        val now = System.currentTimeMillis()
        copies[url.substringBefore('#')] = Copy(text, now)
        copies.entries.removeAll { now - it.value.time > COPY_MS }
        if (copies.size > 40) copies.entries.sortedBy { it.value.time }.take(copies.size - 40).forEach { copies.remove(it.key) }
    }

    private fun copyFor(url: String): Copy? {
        val now = System.currentTimeMillis()
        copies[url.substringBefore('#')]?.let { if (now - it.time < COPY_MS) return it }
        //the page's player may add a token to the address itself ("…m3u8?token=…"): same file, newest copy
        if (!url.contains('?')) {
            val path = url.substringBefore('#')
            copies.entries.filter { it.key.substringBefore('?') == path && now - it.value.time < COPY_MS }
                .maxByOrNull { it.value.time }?.let { return it.value }
        }
        return null
    }

    /** The query (tokens) of the page's latest request to each server. */
    private val hostQuery = ConcurrentHashMap<String, String>()

    /** Settings: use Android's basic connection for players instead of the Chrome-like one. */
    @Volatile var forceBasic = false
        set(value) { field = value; opener = null }

    @SuppressLint("NewApi", "WrongConstant")
    private fun initOpener(context: Context) {
        if (opener != null) return
        if (forceBasic) {
            opener = { url -> url.openConnection() }
            openerName = "basic (chosen)"
            return
        }
        //1. Android's own copy of Chrome's network stack (Android 14+, updated 12/13)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
                val engine = android.net.http.HttpEngine.Builder(context)
                    .setEnableHttp2(true).setEnableQuic(true).setEnableBrotli(true).build()
                opener = { url -> engine.openConnection(url) }
                openerName = "Android HttpEngine"
                return
            }
        } catch (e: Throwable) {
        }
        //2. Chrome's network stack from Google Play services (Lumen only, see CronetSupport)
        CronetSupport.opener(context)?.let {
            opener = it
            openerName = "Play services Cronet"
            return
        }
        opener = { url -> url.openConnection() }
        openerName = "basic"
    }

    @Synchronized
    private fun ensureServer(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    if (!s.isClosed) Log.e(TAG, "accept", e)
                }
            }
        }, "lumen-stream-proxy").apply { isDaemon = true }.start()
        return s.localPort
    }

    /**
     * Returns an address on this TV that plays [url] for any player app, sending [headers]
     * (Referer, Origin, ...) and the browser's [userAgent] and cookies upstream.
     */
    fun wrap(context: Context, url: String, headers: Map<String, String>, userAgent: String?,
             pageOrigin: String? = null): String {
        initOpener(context.applicationContext)
        val port = ensureServer()
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        sessions[token] = Session(HashMap(headers), userAgent, pageOrigin)
        okCount = 0
        note("HAND-OFF " + short(url) + "  [" + (if (pageOrigin != null) "downloaded by the page $pageOrigin" else "network: $openerName") + "]")
        for ((k, v) in headers) note("   header $k: " + if (k.equals("Cookie", true)) "(${v.length} chars)" else v.take(70))
        note("   page's own copy of this playlist: " + (if (copyFor(url) != null) "yes" else "no"))
        note("   cookies for it: " + (try { CookieManager.getInstance().getCookie(url)?.split(';')?.size ?: 0 } catch (e: Exception) { 0 }) +
                ", user agent: " + (userAgent?.take(50) ?: "none"))
        //forget sessions nobody used for a day
        val old = System.currentTimeMillis() - 24 * 3600_000L
        sessions.entries.removeAll { it.value.lastUse < old }
        lastActivity = System.currentTimeMillis()
        LumenProxyService.start(context)
        return proxied(port, token, url)
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64(s: String) = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    /**
     * http://127.0.0.1:port/s/<token>/<base64(folder)>/<file?query> - the file name stays readable
     * (players look at ".m3u8" / ".mp4"), and relative addresses inside playlists still work.
     */
    private fun proxied(port: Int, token: String, url: String): String {
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val slash = url.lastIndexOf('/', q - 1)
        val dir = if (slash > url.indexOf("://") + 2) url.substring(0, slash + 1) else "$url/"
        var rest = if (slash > url.indexOf("://") + 2) url.substring(slash + 1) else ""
        if (rest.isEmpty() || rest.startsWith("?")) rest = "index$rest"
        return "http://127.0.0.1:$port/s/$token/${b64(dir)}/$rest"
    }

    private fun readHead(input: InputStream): List<String>? {
        val buf = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (buf.length > 32768) return null
        }
        return buf.toString().split("\r\n").filter { it.isNotEmpty() }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 60_000
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val head = readHead(input) ?: return
                val parts = head[0].split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.ROOT)
                val path = parts[1]
                val reqHeaders = HashMap<String, String>()
                for (h in head.drop(1)) {
                    val i = h.indexOf(':')
                    if (i > 0) reqHeaders[h.substring(0, i).trim().lowercase(Locale.ROOT)] = h.substring(i + 1).trim()
                }
                val m = Regex("^/s/([A-Za-z0-9]+)/([A-Za-z0-9_-]+)/(.*)$").find(path)
                if (m == null || (method != "GET" && method != "HEAD")) {
                    simple(out, 404, "Not found")
                    return
                }
                val token = m.groupValues[1]
                val session = sessions[token] ?: run { simple(out, 410, "Expired"); return }
                session.lastUse = System.currentTimeMillis()
                lastActivity = session.lastUse
                val upstream = unb64(m.groupValues[2]) + m.groupValues[3]
                forward(out, method, upstream, reqHeaders, session, token, sock.localPort)
            } catch (e: Exception) {
                Log.w(TAG, "request failed: $e")
            }
        }
    }

    private fun simple(out: OutputStream, code: Int, text: String) {
        val body = text.toByteArray()
        out.write("HTTP/1.1 $code $text\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun isPlaylist(url: String, contentType: String?): Boolean {
        val ct = contentType?.lowercase(Locale.ROOT) ?: ""
        if (ct.contains("mpegurl")) return true
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun forward(out: OutputStream, method: String, upstream: String, req: Map<String, String>,
                        session: Session, token: String, port: Int) {
        //the page's player already received this playlist: hand over that copy (asking again can
        //be refused, e.g. when the site's request signatures are only accepted once)
        copyFor(upstream)?.let { c ->
            val dash = c.text.trimStart('\uFEFF', ' ', '\r', '\n', '\t').let { !it.startsWith("#EXTM3U") }
            val body = (if (dash) rewriteMpd(c.text, upstream, port, token) else rewritePlaylist(c.text, upstream, port, token)).toByteArray()
            note("$method ${short(upstream)} -> page's copy (${if (dash) "DASH" else "HLS"}, ${c.text.length} chars)")
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + (if (dash) "application/dash+xml" else "application/vnd.apple.mpegurl") +
                    "\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
            if (method != "HEAD") out.write(body)
            out.flush()
            return
        }
        if (session.pageOrigin != null) {
            forwardViaPage(out, method, upstream, req, session, token, port)
            return
        }
        val upHost = try { URI(upstream).host } catch (e: Exception) { null }
        //the page's own latest headers for this server, if it talked to it; else those of the hand-off
        val pageHeaders = upHost?.let { hostHeaders[it] }?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
        val useHeaders = pageHeaders?.headers ?: session.headers
        if (pageHeaders != null && upHost != null && notedHosts.add(token + upHost)) {
            note("   for $upHost the page's own headers are used: " + pageHeaders.headers.keys.joinToString(", "))
        } else if (pageHeaders == null && upHost != null && notedHosts.add(token + upHost)) {
            note("   the page never talked to $upHost itself: hand-off headers used")
        }
        fun open(target: String): HttpURLConnection {
            val c = opener!!(URL(target)) as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            for ((k, v) in useHeaders) {
                if (k.equals("Cookie", true) || k.equals("Range", true) || k.equals("Host", true)) continue
                c.setRequestProperty(k, v)
            }
            session.userAgent?.let { c.setRequestProperty("User-Agent", it) }
            if (useHeaders.keys.none { it.equals("Accept", true) }) c.setRequestProperty("Accept", "*/*")
            addBrowserHeaders(c, useHeaders, target)
            try {
                CookieManager.getInstance().getCookie(target)?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
            } catch (ignored: Exception) {
            }
            req["range"]?.let { c.setRequestProperty("Range", it) }
            return c
        }
        var upstream = upstream
        var conn = open(upstream)
        var code = try { conn.responseCode } catch (e: Exception) {
            note("$method ${short(upstream)} -> FAILED ${e.message}")
            simple(out, 502, "Upstream failed: ${e.message}")
            return
        }
        //refused, and the address has no token: the page's player adds its token to the addresses
        //itself - try again with the token the page used for this server
        val q = hostQuery[upHost ?: ""]
        if ((code == 401 || code == 403) && !upstream.contains('?') && q != null) {
            try { conn.disconnect() } catch (ignored: Exception) {}
            val retry = "$upstream?$q"
            val c2 = open(retry)
            val code2 = try { c2.responseCode } catch (e: Exception) { -1 }
            if (notedHosts.add("q$token$upHost")) note("   $upHost refused without a token; with the page's token: $code2")
            if (code2 > 0) {
                conn = c2
                code = code2
                upstream = retry
            }
        }
        if (code >= 400) {
            //keep what the server said, for the log, then pass it on to the player
            val body = try { conn.errorStream?.use { it.readBytes() } ?: ByteArray(0) } catch (e: Exception) { ByteArray(0) }
            val said = String(body, 0, minOf(body.size, 200), Charsets.UTF_8).replace(Regex("\\s+"), " ")
            note("$method ${short(upstream)} -> $code ${conn.responseMessage ?: ""}  «$said»")
            val h = try { URI(upstream).host } catch (e: Exception) { null }
            if (h != null && notedHosts.add("copies$token$h")) {
                val same = copies.keys.filter { it.contains(h) }
                note("   page's playlists from $h: " + (if (same.isEmpty()) "none" else same.joinToString(" | ") { short(it) }))
            }
            out.write(("HTTP/1.1 $code ${conn.responseMessage ?: ""}\r\nContent-Type: ${conn.contentType ?: "text/plain"}\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(body)
            out.flush()
            conn.disconnect()
            return
        }
        //successful video pieces: only the first few are written down (errors always are)
        okCount++
        if (okCount <= 6 || okCount % 50 == 0) {
            note("$method ${short(upstream)} -> $code" + (req["range"]?.let { " ($it)" } ?: "") +
                    (if (okCount > 6) "  ($okCount OK so far)" else ""))
        }
        val type = conn.contentType
        val raw = (if (code >= 400) conn.errorStream else conn.inputStream)
        val stream = raw?.let { BufferedInputStream(it, 64 * 1024) }
        //many hosts serve their playlists as text/plain or octet-stream from addresses without
        //".m3u8": look at the first bytes, otherwise the segments inside would bypass the proxy (403)
        val manifestKind = if (code in 200..299 && stream != null) sniffManifest(upstream, type, conn, stream) else null
        if (manifestKind == "dash") {
            val finalUrl = conn.url?.toString() ?: upstream
            val text = stream!!.bufferedReader().use { it.readText() }
            val body = rewriteMpd(text, finalUrl, port, token).toByteArray()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/dash+xml\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
            if (method != "HEAD") out.write(body)
            out.flush()
            conn.disconnect()
            return
        }
        if (manifestKind == "hls") {
            //playlists: every address inside goes through the proxy too
            val finalUrl = conn.url?.toString() ?: upstream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val body = rewritePlaylist(text, finalUrl, port, token).toByteArray()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
            if (method != "HEAD") out.write(body)
            out.flush()
            conn.disconnect()
            return
        }
        val sb = StringBuilder("HTTP/1.1 $code ${conn.responseMessage ?: ""}\r\n")
        type?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
        //(a body the HTTP library already decompressed has another length: then let "Connection: close" end it)
        if (conn.getHeaderField("Content-Encoding").isNullOrBlank()) {
            conn.getHeaderField("Content-Length")?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
        }
        conn.getHeaderField("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
        sb.append("Accept-Ranges: ").append(conn.getHeaderField("Accept-Ranges") ?: "bytes").append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (method != "HEAD" && stream != null) {
            stream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    lastActivity = System.currentTimeMillis()
                }
            }
        }
        out.flush()
        conn.disconnect()
    }

    /**
     * The open page downloads [upstream] with its own fetch() (see [LumenPageFetch]) and the bytes
     * are passed on to the player: the server sees the site's own player asking.
     */
    private fun forwardViaPage(out: OutputStream, method: String, upstream: String, req: Map<String, String>,
                               session: Session, token: String, port: Int) {
        val origin = session.pageOrigin!!
        val upHost = try { URI(upstream).host } catch (e: Exception) { null }
        val pageHeaders = upHost?.let { hostHeaders[it] }?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
        val useHeaders = pageHeaders?.headers ?: session.headers
        val r = LumenPageFetch.fetch(origin, upstream, useHeaders, req["range"])
        if (r == null) {
            note("$method ${short(upstream)} -> the page ($origin) is closed")
            simple(out, 502, "The page is closed")
            return
        }
        if (!r.awaitHead(40_000)) {
            note("$method ${short(upstream)} -> the page couldn't fetch it: ${r.error ?: "no answer"}")
            r.cancel(origin)
            simple(out, 502, "Page fetch failed")
            return
        }
        val code = r.status
        if (code >= 400) {
            val body = try { r.body.readBytes() } catch (e: Exception) { ByteArray(0) }
            val said = String(body, 0, minOf(body.size, 200), Charsets.UTF_8).replace(Regex("\\s+"), " ")
            note("$method ${short(upstream)} -> $code (page fetch)  «$said»")
            out.write(("HTTP/1.1 $code Error\r\nContent-Type: ${r.header("content-type") ?: "text/plain"}\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(body)
            out.flush()
            return
        }
        okCount++
        if (okCount <= 6 || okCount % 50 == 0) {
            note("$method ${short(upstream)} -> $code via the page" + (req["range"]?.let { " ($it)" } ?: "") +
                    (if (okCount > 6) "  ($okCount OK so far)" else ""))
        }
        val type = r.header("content-type")
        val input = java.io.PushbackInputStream(r.body, 16)
        //playlists: their addresses must go through the proxy (and so through the page) too
        val first = ByteArray(16)
        var got = 0
        try {
            while (got < first.size) {
                val n = input.read(first, got, first.size - got)
                if (n < 0) break
                got += n
            }
        } catch (e: Exception) {
            note("$method ${short(upstream)} -> page stopped: ${e.message}")
            simple(out, 502, "Page fetch failed")
            return
        }
        if (got > 0) input.unread(first, 0, got)
        val start = String(first, 0, got, Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val ct = type?.lowercase(Locale.ROOT) ?: ""
        val path = upstream.substringBefore('?').lowercase(Locale.ROOT)
        val hls = start.startsWith("#EXTM3U") || ct.contains("mpegurl") || path.endsWith(".m3u8")
        val dash = !hls && (start.startsWith("<?xml") || start.startsWith("<MPD") || ct.contains("dash+xml") || path.endsWith(".mpd"))
        if (hls || dash) {
            val text = input.bufferedReader().use { it.readText() }
            val finalUrl = r.finalUrl ?: upstream
            val body = (if (hls) rewritePlaylist(text, finalUrl, port, token) else rewriteMpd(text, finalUrl, port, token)).toByteArray()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + (if (hls) "application/vnd.apple.mpegurl" else "application/dash+xml") +
                    "\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
            if (method != "HEAD") out.write(body)
            out.flush()
            return
        }
        val sb = StringBuilder("HTTP/1.1 $code OK\r\n")
        type?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
        //fetch() hands over the body already decompressed: its length is only right without compression
        if (r.header("content-encoding").isNullOrBlank()) {
            r.header("content-length")?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
        }
        r.header("content-range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
        sb.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (method == "HEAD") {
            r.cancel(origin)
            out.flush()
            return
        }
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                lastActivity = System.currentTimeMillis()
            }
            out.flush()
        } catch (e: Exception) {
            //the player closed the connection (seek, quality change): stop the page's download too
            r.cancel(origin)
            throw e
        }
    }

    /** The headers a browser adds to every video request (some servers refuse requests without them). */
    private fun addBrowserHeaders(conn: HttpURLConnection, headers: Map<String, String>, url: String) {
        fun has(k: String) = headers.keys.any { it.equals(k, true) }
        if (!has("Accept-Language")) conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.8")
        val from = headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value
            ?: headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val site = try {
            val a = URI(from ?: "").host
            val b = URI(url).host
            fun base(h: String?) = h?.split('.')?.takeLast(2)?.joinToString(".")
            when {
                a == null -> "none"
                a == b -> "same-origin"
                base(a) == base(b) -> "same-site"
                else -> "cross-site"
            }
        } catch (e: Exception) { "cross-site" }
        if (!has("Sec-Fetch-Dest")) conn.setRequestProperty("Sec-Fetch-Dest", "empty")
        if (!has("Sec-Fetch-Mode")) conn.setRequestProperty("Sec-Fetch-Mode", "cors")
        if (!has("Sec-Fetch-Site")) conn.setRequestProperty("Sec-Fetch-Site", site)
    }

    /** "hls", "dash" or null (media / anything else). Looks at the first bytes when the type is unclear. */
    private fun sniffManifest(url: String, contentType: String?, conn: HttpURLConnection, stream: BufferedInputStream): String? {
        val ct = contentType?.lowercase(Locale.ROOT) ?: ""
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        if (isPlaylist(url, contentType)) return "hls"
        if (ct.contains("dash+xml") || path.endsWith(".mpd")) return "dash"
        //obviously media: never buffer it
        if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("mp2t") || ct.startsWith("image/")) return null
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        if (len > 8L * 1024 * 1024) return null
        return try {
            stream.mark(1024)
            val buf = ByteArray(1024)
            var n = 0
            while (n < buf.size) {
                val r = stream.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            stream.reset()
            val head = String(buf, 0, n, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            when {
                head.startsWith("#EXTM3U") -> "hls"
                head.contains("<MPD") -> "dash"
                else -> null
            }
        } catch (e: Exception) {
            try { stream.reset() } catch (ignored: Exception) { }
            null
        }
    }

    /**
     * Like [proxied], but the part kept readable starts before the first DASH template placeholder
     * ("$Number$", "$RepresentationID$"), so the player can still fill them in.
     */
    private fun proxiedTemplate(port: Int, token: String, url: String): String {
        val stop = listOf(url.indexOf('$'), url.indexOf('?')).filter { it >= 0 }.minOrNull() ?: url.length
        val slash = url.lastIndexOf('/', stop - 1)
        if (slash <= url.indexOf("://") + 2) return proxied(port, token, url)
        return "http://127.0.0.1:$port/s/$token/${b64(url.substring(0, slash + 1))}/${url.substring(slash + 1)}"
    }

    /**
     * DASH manifests: every absolute address (BaseURL, segment templates, initialization, ...) goes
     * through the proxy as well. Relative ones already do, because the manifest itself is served
     * from a proxy address in the same "folder".
     */
    private fun rewriteMpd(text: String, base: String, port: Int, token: String): String {
        val baseUri = URI(base)
        fun abs(u: String): String = try { baseUri.resolve(u.trim()).toString() } catch (e: Exception) { u }
        var out = Regex("(<BaseURL[^>]*>)([^<]+)(</BaseURL>)").replace(text) { mr ->
            val u = mr.groupValues[2].trim().replace("&amp;", "&")
            mr.groupValues[1] + proxiedTemplate(port, token, abs(u)).replace("&", "&amp;") + mr.groupValues[3]
        }
        out = Regex("""\b(media|initialization|sourceURL|href|index)="(https?://[^"]+)"""").replace(out) { mr ->
            val u = mr.groupValues[2].replace("&amp;", "&")
            mr.groupValues[1] + "=\"" + proxiedTemplate(port, token, u).replace("&", "&amp;") + "\""
        }
        return out
    }

    private fun rewritePlaylist(text: String, base: String, port: Int, token: String): String {
        val baseUri = URI(base)
        fun abs(u: String): String = try { baseUri.resolve(u.trim()).toString() } catch (e: Exception) { u }
        val uriAttr = Regex("URI=\"([^\"]+)\"")
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriAttr.replace(line) { mr ->
                    val u = mr.groupValues[1]
                    if (u.startsWith("data:") || u.startsWith("skd:")) mr.value
                    else "URI=\"" + proxied(port, token, abs(u)) + "\""
                }
                else -> proxied(port, token, abs(t))
            }
        }
    }

    fun networkName(): String = openerName
}

/**
 * Keeps Lumen (and so the proxy) running while another app plays a video through it; Android
 * would otherwise close Lumen in the background on TVs with little memory. Stops itself after
 * 20 minutes without any request.
 */
class LumenProxyService : Service() {
    companion object {
        private const val CHANNEL = "lumen_proxy"
        private const val IDLE_MS = 20 * 60_000L

        fun start(context: Context) {
            val i = Intent(context, LumenProxyService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                //not allowed right now (e.g. app in background): the proxy still works while Lumen lives
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - LumenStreamProxy.lastActivity > IDLE_MS) {
                stopSelf()
                return
            }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video to player", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("${com.phlox.tvwebbrowser.Brand.NAME} is passing a video to your player")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(4242, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(4242, n)
            }
        } catch (e: Exception) {
            Log.w("LumenProxyService", "foreground: $e")
        }
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 60_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }
}
