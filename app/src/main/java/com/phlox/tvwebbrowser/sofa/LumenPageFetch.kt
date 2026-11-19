package com.phlox.tvwebbrowser.sofa

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The page downloads, Lumen's player plays.
 *
 * Some video sites accept a stream only from their own page (same browser connection, same
 * session, tokens that work only for the page's own requests): any other downloader gets HTTP 403,
 * however well it copies cookies and headers. Here Lumen's player asks the open page to fetch each
 * piece with the page's own fetch(); the bytes come back over a binary message channel
 * (WebView's addWebMessageListener, object "LumenRelay" in every frame, see sofa_enhancements.js).
 */
object LumenPageFetch {
    private const val TAG = "LumenPageFetch"
    private const val OBJECT_NAME = "LumenRelay"

    private class FrameRef(val proxy: JavaScriptReplyProxy, val webView: WeakReference<WebView>,
                           val mainFrame: Boolean, @Volatile var time: Long)

    /** Latest frame of each origin ("https://host[:port]") that said hello. */
    private val frames = ConcurrentHashMap<String, FrameRef>()
    private val requests = ConcurrentHashMap<Int, Response>()
    private val nextId = AtomicInteger(1)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binary = false

    /** Installs the channel on a WebView (UI thread, before pages load). False if WebView can't. */
    fun install(webView: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        binary = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        return try {
            WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri,
                                               isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                        onMessage(view, message, sourceOrigin, isMainFrame, replyProxy)
                    }
                })
            true
        } catch (e: Throwable) {
            Log.w(TAG, "can't install the page relay: $e")
            false
        }
    }

    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    fun originOf(url: String?): String? = try {
        val u = Uri.parse(url ?: return null)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        if (scheme != "http" && scheme != "https") null
        else "$scheme://$host" + (if (u.port > 0) ":${u.port}" else "")
    } catch (e: Exception) {
        null
    }

    /** True when a frame of this origin is open and reachable. */
    fun hasFrame(origin: String?): Boolean = origin != null && frames[origin]?.webView?.get() != null

    private fun onMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean,
                          replyProxy: JavaScriptReplyProxy) {
        if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            val bytes = message.arrayBuffer
            if (bytes.size < 4) return
            val id = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            requests[id]?.addChunk(bytes, 4)
            return
        }
        val text = message.data ?: return
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        val origin = sourceOrigin.toString().trimEnd('/')
        when (m.optString("op")) {
            "hello" -> {
                frames[origin] = FrameRef(replyProxy, WeakReference(view), isMainFrame, System.currentTimeMillis())
            }
            "head" -> requests[m.optInt("id")]?.setHead(m)
            "data" -> requests[m.optInt("id")]?.let { r ->
                //base64 fallback when WebView can't send binary messages
                val b = try { android.util.Base64.decode(m.optString("b64"), android.util.Base64.DEFAULT) } catch (e: Exception) { null }
                if (b != null) r.addChunk(b, 0)
            }
            "end" -> requests[m.optInt("id")]?.finish(null)
            "error" -> requests[m.optInt("id")]?.finish(m.optString("message", "failed"))
        }
    }

    /**
     * Asks the frame of [origin] to fetch [url]. Returns null when no such frame is open.
     * [headers]: the page player's own request headers (the browser drops the ones it sets itself).
     */
    fun fetch(origin: String, url: String, headers: Map<String, String>, range: String?): Response? {
        val frame = frames[origin] ?: return null
        if (frame.webView.get() == null) {
            frames.remove(origin)
            return null
        }
        val id = nextId.getAndIncrement()
        val response = Response(id)
        requests[id] = response
        val h = JSONObject()
        for ((k, v) in headers) {
            val lk = k.lowercase(java.util.Locale.ROOT)
            //only what the site's script itself adds (tokens): anything else the browser sets on
            //its own, and an extra header would make the server's CORS check refuse the request
            if (lk in FORBIDDEN || lk.startsWith("sec-")) continue
            if (lk.startsWith("x-") || lk == "authorization" || lk == "accept") h.put(k, v)
        }
        if (range != null) h.put("Range", range)
        val msg = JSONObject().put("op", "fetch").put("id", id).put("url", url).put("headers", h)
            .put("bin", binary).toString()
        main.post {
            try {
                frame.proxy.postMessage(msg)
            } catch (e: Throwable) {
                response.finish("page not reachable: ${e.message}")
            }
        }
        return response
    }

    private fun abort(id: Int, origin: String) {
        requests.remove(id)
        val frame = frames[origin] ?: return
        val msg = JSONObject().put("op", "abort").put("id", id).toString()
        main.post { try { frame.proxy.postMessage(msg) } catch (ignored: Throwable) {} }
    }

    /** Headers the browser sets itself (fetch() refuses or ignores them). */
    private val FORBIDDEN = setOf("referer", "origin", "cookie", "user-agent", "host", "connection",
        "content-length", "accept-encoding", "accept-charset", "dnt", "keep-alive", "te", "trailer",
        "transfer-encoding", "upgrade", "via", "x-requested-with", "range", "if-none-match", "if-modified-since")

    private val END = ByteArray(0)

    /** One fetch by the page: status and headers first, then the body as a stream. */
    class Response internal constructor(val id: Int) {
        private val headLatch = CountDownLatch(1)
        private val chunks = LinkedBlockingQueue<ByteArray>()
        @Volatile var status = 0
            private set
        @Volatile var finalUrl: String? = null
            private set
        @Volatile var error: String? = null
            private set
        val headers = HashMap<String, String>()

        internal fun setHead(m: JSONObject) {
            status = m.optInt("status")
            finalUrl = m.optString("url").takeIf { it.isNotEmpty() }
            m.optJSONObject("headers")?.let { o ->
                synchronized(headers) {
                    for (k in o.keys()) headers[k.lowercase(java.util.Locale.ROOT)] = o.optString(k)
                }
            }
            headLatch.countDown()
        }

        internal fun addChunk(bytes: ByteArray, offset: Int) {
            chunks.offer(if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size))
        }

        internal fun finish(err: String?) {
            if (err != null) error = err
            headLatch.countDown()
            chunks.offer(END)
            requests.remove(id)
        }

        /** Waits for the status line. False on timeout or failure (see [error]). */
        fun awaitHead(timeoutMs: Long): Boolean =
            headLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && status > 0

        fun header(name: String): String? = synchronized(headers) { headers[name.lowercase(java.util.Locale.ROOT)] }

        /** Stop the page's download (the player went elsewhere). */
        fun cancel(origin: String) {
            abort(id, origin)
            chunks.offer(END)
        }

        val body: InputStream = object : InputStream() {
            private var current: ByteArray? = null
            private var pos = 0
            private var done = false

            private fun fill(): Boolean {
                while (!done && (current == null || pos >= current!!.size)) {
                    val next = chunks.poll(45, TimeUnit.SECONDS) ?: throw IOException("the page stopped sending")
                    if (next === END) {
                        done = true
                        error?.let { throw IOException(it) }
                        return false
                    }
                    current = next
                    pos = 0
                }
                return !done
            }

            override fun read(): Int {
                if (!fill()) return -1
                return current!![pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val cur = current!!
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n)
                pos += n
                return n
            }
        }
    }
}
