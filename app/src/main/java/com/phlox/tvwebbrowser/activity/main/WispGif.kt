package com.phlox.tvwebbrowser.activity.main

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.sofa.GifEncoder
import com.phlox.tvwebbrowser.sofa.WebpFrames
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wisp: turns WebM videos and (animated) WebP images into GIFs on the phone, like ezgif.
 * Offered automatically when such a file is about to be downloaded, and from ⋮ → More →
 * Make a GIF for files already on the phone. Frames come from Android's own decoders
 * (MediaMetadataRetriever for video, BitmapFactory for each WebP frame), the GIF is written by
 * [GifEncoder] with one colour table for the whole animation, and saved to Download/Wisp.
 */
object WispGif {
    const val REQUEST_PICK = 10031
    private const val MAX_SOURCE_BYTES = 200L * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())

    class Options(var maxWidth: Int = 480, var fps: Int = 12, var maxSeconds: Int = 10)

    /** Is this download a WebM or WebP file (by address, file name or type)? */
    fun isConvertible(url: String, fileName: String?, mime: String?): Boolean {
        val m = mime?.lowercase(Locale.ROOT) ?: ""
        if (m.startsWith("video/webm") || m.startsWith("image/webp") || m.startsWith("audio/webm")) return !m.startsWith("audio")
        fun ext(s: String?) = s?.substringBefore('?')?.substringBefore('#')?.lowercase(Locale.ROOT) ?: ""
        val n = ext(fileName).ifEmpty { ext(Uri.parse(url).lastPathSegment) }
        return n.endsWith(".webm") || n.endsWith(".webp")
    }

    /** Before a WebM/WebP download: GIF or the original file? */
    fun offer(activity: Activity, url: String, fileName: String?, mime: String?, userAgent: String?, referer: String?,
              downloadOriginal: () -> Unit) {
        val video = (mime?.startsWith("video") == true) || (fileName ?: url).lowercase(Locale.ROOT).substringBefore('?').endsWith(".webm")
        AlertDialog.Builder(activity)
            .setTitle(R.string.wisp_gif_offer_title)
            .setMessage(activity.getString(if (video) R.string.wisp_gif_offer_video else R.string.wisp_gif_offer_image))
            .setPositiveButton(R.string.wisp_gif_make) { _, _ ->
                askOptions(activity, video) { opt ->
                    run(activity, opt, baseName(fileName ?: Uri.parse(url).lastPathSegment)) { file, cancelled ->
                        download(url, userAgent, referer, file, cancelled)
                    }
                }
            }
            .setNeutralButton(R.string.wisp_gif_original) { _, _ -> downloadOriginal() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** ⋮ → More → Make a GIF: pick a WebM / WebP (or other video) on the phone. */
    fun openPicker(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/webm", "image/webp", "video/*"))
        try {
            activity.startActivityForResult(intent, REQUEST_PICK)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.external_app_open_error, Toast.LENGTH_LONG).show()
        }
    }

    fun onPicked(activity: Activity, uri: Uri) {
        val name = try {
            activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
        val type = activity.contentResolver.getType(uri) ?: ""
        val video = !type.startsWith("image") && !(name ?: "").lowercase(Locale.ROOT).endsWith(".webp")
        askOptions(activity, video) { opt ->
            run(activity, opt, baseName(name)) { file, cancelled ->
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { out -> copyLimited(input, out, cancelled) }
                } ?: throw java.io.IOException("can't read the file")
            }
        }
    }

    private fun baseName(name: String?): String {
        val n = (name ?: "").substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return n.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(60).ifBlank { "wisp" }
    }

    // ---------------- options ----------------

    private fun askOptions(activity: Activity, video: Boolean, go: (Options) -> Unit) {
        val opt = Options()
        val dp = activity.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(px(22f), px(4f), px(22f), 0)
        fun group(title: Int, labels: List<String>, selected: Int, pick: (Int) -> Unit) {
            val t = TextView(activity)
            t.setText(title)
            t.textSize = 13f
            t.setTextColor(ContextCompat.getColor(activity, R.color.wisp_text_secondary))
            t.setPadding(0, px(10f), 0, px(2f))
            box.addView(t)
            val rg = RadioGroup(activity)
            rg.orientation = RadioGroup.HORIZONTAL
            labels.forEachIndexed { i, l ->
                val rb = RadioButton(activity)
                rb.id = 1000 + i
                rb.text = l
                rb.textSize = 14f
                rb.setPadding(0, 0, px(6f), 0)
                rg.addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            rg.check(1000 + selected)
            rg.setOnCheckedChangeListener { _, id -> pick(id - 1000) }
            box.addView(rg)
        }
        val widths = listOf(320, 480, 640, 0)
        group(R.string.wisp_gif_size, listOf("320", "480", "640", activity.getString(R.string.wisp_gif_original_size)), 1) {
            opt.maxWidth = widths[it]
        }
        if (video) {
            val fps = listOf(8, 12, 15, 20)
            group(R.string.wisp_gif_fps, fps.map { "$it" }, 1) { opt.fps = fps[it] }
            val secs = listOf(5, 10, 20, 30)
            group(R.string.wisp_gif_length, secs.map { "$it s" }, 1) { opt.maxSeconds = secs[it] }
        }
        val note = TextView(activity)
        note.setText(R.string.wisp_gif_note)
        note.textSize = 12.5f
        note.setTextColor(ContextCompat.getColor(activity, R.color.wisp_text_secondary))
        note.setPadding(0, px(12f), 0, 0)
        box.addView(note)
        AlertDialog.Builder(activity)
            .setTitle(R.string.wisp_gif_options)
            .setView(box)
            .setPositiveButton(R.string.wisp_gif_make) { _, _ -> go(opt) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- conversion ----------------

    /** Gets the source into a temporary file ([fetch]), converts it with a progress dialog, saves the GIF. */
    private fun run(activity: Activity, opt: Options, name: String,
                    fetch: (File, () -> Boolean) -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((22 * dp).toInt(), (8 * dp).toInt(), (22 * dp).toInt(), 0)
        val status = TextView(activity)
        status.setText(R.string.wisp_gif_getting)
        status.textSize = 14f
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.isIndeterminate = true
        box.addView(status)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = (10 * dp).toInt()
        })
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.wisp_gif_making)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel.set(true) }
            .show()
        fun progress(textRes: Int, fraction: Float) = main.post {
            status.setText(textRes)
            bar.isIndeterminate = fraction < 0
            if (fraction >= 0) bar.progress = (fraction * 1000).toInt()
        }
        val src = File(activity.cacheDir, "wisp_gif_source")
        Thread({
            try {
                src.delete()
                fetch(src) { cancel.get() }
                if (cancel.get()) return@Thread
                progress(R.string.wisp_gif_converting, 0f)
                val out = ByteArrayOutputStreamEx()
                val frames = convert(src, opt, out, { cancel.get() }) { f -> progress(R.string.wisp_gif_converting, f) }
                if (cancel.get()) return@Thread
                val (where, uri) = save(activity, name, out)
                main.post {
                    dialog.dismiss()
                    done(activity, where, uri, out.size(), frames)
                }
            } catch (e: Throwable) {
                main.post {
                    dialog.dismiss()
                    if (!cancel.get()) {
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.wisp_gif_failed)
                            .setMessage(e.message ?: e.javaClass.simpleName)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            } finally {
                src.delete()
            }
        }, "wisp-gif").start()
    }

    private class ByteArrayOutputStreamEx : java.io.ByteArrayOutputStream(1 shl 20) {
        fun writeTo2(o: OutputStream) = o.write(buf, 0, count)
    }

    private fun download(url: String, userAgent: String?, referer: String?, file: File, cancelled: () -> Boolean) {
        var u = URL(url)
        var redirects = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            userAgent?.let { c.setRequestProperty("User-Agent", it) }
            referer?.takeIf { it.startsWith("http") }?.let { c.setRequestProperty("Referer", it) }
            try { CookieManager.getInstance().getCookie(u.toString())?.let { c.setRequestProperty("Cookie", it) } } catch (e: Exception) {}
            val code = c.responseCode
            if (code in 300..399 && redirects < 8) {
                val loc = c.getHeaderField("Location") ?: throw java.io.IOException("HTTP $code")
                u = URL(u, loc)
                redirects++
                c.disconnect()
                continue
            }
            if (code !in 200..299) throw java.io.IOException("The server answered HTTP $code")
            if (c.contentLengthLong > MAX_SOURCE_BYTES) throw java.io.IOException("The file is too big for a GIF (over 200 MB)")
            c.inputStream.use { input -> FileOutputStream(file).use { out -> copyLimited(input, out, cancelled) } }
            c.disconnect()
            return
        }
    }

    private fun copyLimited(input: java.io.InputStream, out: OutputStream, cancelled: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (!cancelled()) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_SOURCE_BYTES) throw java.io.IOException("The file is too big for a GIF (over 200 MB)")
            out.write(buf, 0, n)
        }
    }

    /** One source frame on the way to the GIF. */
    private class Frame(val bitmap: Bitmap, val delayMs: Int)

    /** Writes the GIF into [out]; returns the number of frames. */
    private fun convert(src: File, opt: Options, out: OutputStream, cancelled: () -> Boolean, progress: (Float) -> Unit): Int {
        val head = ByteArray(12)
        src.inputStream().use { it.read(head) }
        return if (WebpFrames.isWebp(head)) convertWebp(src, opt, out, cancelled, progress)
            else convertVideo(src, opt, out, cancelled, progress)
    }

    private fun targetSize(w: Int, h: Int, maxWidth: Int): Pair<Int, Int> {
        if (maxWidth <= 0 || w <= maxWidth) return Pair(w.coerceAtLeast(1), h.coerceAtLeast(1))
        return Pair(maxWidth, (h * maxWidth.toFloat() / w).roundToInt().coerceAtLeast(1))
    }

    /** Pixels of [bmp] scaled to tw × th. */
    private fun pixels(bmp: Bitmap, tw: Int, th: Int): IntArray {
        val s = if (bmp.width == tw && bmp.height == th) bmp else Bitmap.createScaledBitmap(bmp, tw, th, true)
        val px = IntArray(tw * th)
        s.getPixels(px, 0, tw, 0, 0, tw, th)
        if (s !== bmp) s.recycle()
        return px
    }

    /** Every [step]-th pixel, for the colour table. */
    private fun sample(px: IntArray, into: IntArrayBuilder, step: Int) {
        var i = 0
        while (i < px.size) { into.add(px[i]); i += step }
    }

    private class IntArrayBuilder {
        var data = IntArray(65536)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
    }

    private fun encodeAll(out: OutputStream, w: Int, h: Int, samples: IntArrayBuilder, frameCount: Int,
                          frames: ((IntArray, Int) -> Unit) -> Unit, cancelled: () -> Boolean, progress: (Float) -> Unit) {
        var transparent = false
        for (i in 0 until samples.size) if ((samples.data[i] ushr 24) < 128) { transparent = true; break }
        val pal = GifEncoder.medianCut(samples.data, samples.size, if (transparent) 255 else 256)
        val palette = if (transparent) pal + intArrayOf(0) else pal
        val ti = if (transparent) palette.size - 1 else -1
        val mapper = GifEncoder.Mapper(palette, ti)
        val buffered = java.io.BufferedOutputStream(out, 1 shl 16)
        val enc = GifEncoder(buffered, w, h, palette, ti)
        val idx = ByteArray(w * h)
        var n = 0
        frames { px, delay ->
            if (cancelled()) return@frames
            mapper.map(px, idx)
            enc.addFrame(idx, delay)
            n++
            progress(0.35f + 0.65f * n / frameCount.coerceAtLeast(1))
        }
        enc.finish()
        buffered.flush()
    }

    private fun convertVideo(src: File, opt: Options, out: OutputStream, cancelled: () -> Boolean, progress: (Float) -> Unit): Int {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(src.absolutePath)
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = vw; vw = vh; vh = t }
            if (vw <= 0 || vh <= 0) throw java.io.IOException("No video picture found in this file")
            val (tw, th) = targetSize(vw, vh, opt.maxWidth)
            val length = if (duration > 0) minOf(duration, opt.maxSeconds * 1000L) else opt.maxSeconds * 1000L
            val stepMs = (1000.0 / opt.fps)
            val times = ArrayList<Long>()
            var t = 0.0
            while (t < length) { times.add((t * 1000).toLong()); t += stepMs }
            if (times.isEmpty()) times.add(0L)
            fun frameAt(us: Long, exact: Boolean): Bitmap? {
                val mode = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) mmr.getScaledFrameAtTime(us, mode, tw, th)
                    else mmr.getFrameAtTime(us, mode)
            }
            //colour table from a few frames spread over the clip
            val samples = IntArrayBuilder()
            val step = maxOf(1, (tw * th) / 6000)
            for (k in 0 until 8) {
                if (cancelled()) return 0
                val bmp = frameAt(times[(times.size - 1) * k / 7], false) ?: continue
                sample(pixels(bmp, tw, th), samples, step)
                bmp.recycle()
                progress(0.35f * (k + 1) / 8)
            }
            if (samples.size == 0) throw java.io.IOException("This video can't be decoded on this phone")
            var count = 0
            encodeAll(out, tw, th, samples, times.size, { emit ->
                for (us in times) {
                    if (cancelled()) break
                    val bmp = frameAt(us, true) ?: continue
                    emit(pixels(bmp, tw, th), stepMs.roundToInt())
                    bmp.recycle()
                    count++
                }
            }, cancelled, progress)
            return count
        } finally {
            try { mmr.release() } catch (e: Exception) {}
        }
    }

    private fun convertWebp(src: File, opt: Options, out: OutputStream, cancelled: () -> Boolean, progress: (Float) -> Unit): Int {
        val bytes = src.readBytes()
        val anim = WebpFrames.parse(bytes)
        if (anim == null) {
            //a still WebP: a one-frame GIF
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw java.io.IOException("This WebP can't be decoded")
            val (tw, th) = targetSize(bmp.width, bmp.height, opt.maxWidth)
            val px = pixels(bmp, tw, th)
            val samples = IntArrayBuilder()
            sample(px, samples, 1)
            encodeAll(out, tw, th, samples, 1, { emit -> emit(px, 1000) }, cancelled, progress)
            return 1
        }
        val (tw, th) = targetSize(anim.width, anim.height, opt.maxWidth)
        val canvasBmp = Bitmap.createBitmap(anim.width, anim.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val over = Paint(Paint.FILTER_BITMAP_FLAG)
        /** Composes the frames one after another (blending and disposal as the WebP says). */
        fun play(each: (IntArray, Int, Int) -> Unit) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            anim.frames.forEachIndexed { i, f ->
                if (cancelled()) return
                val bmp = BitmapFactory.decodeByteArray(f.still, 0, f.still.size)
                if (bmp != null) {
                    val r = Rect(f.x, f.y, f.x + bmp.width, f.y + bmp.height)
                    canvas.drawBitmap(bmp, null, r, if (f.blend) over else replace)
                    bmp.recycle()
                }
                each(pixels(canvasBmp, tw, th), f.durationMs, i)
                if (f.disposeToBackground) canvas.drawRect(Rect(f.x, f.y, f.x + f.width, f.y + f.height), clear)
            }
        }
        val samples = IntArrayBuilder()
        val every = maxOf(1, anim.frames.size / 12)
        val step = maxOf(1, (tw * th) / 6000)
        play { px, _, i ->
            if (i % every == 0) sample(px, samples, step)
            progress(0.35f * (i + 1) / anim.frames.size)
        }
        if (cancelled()) return 0
        encodeAll(out, tw, th, samples, anim.frames.size, { emit -> play { px, delay, _ -> emit(px, delay) } }, cancelled, progress)
        canvasBmp.recycle()
        return anim.frames.size
    }

    // ---------------- saving ----------------

    /** Saves to Download/Wisp; returns where (for the user) and the file's address. */
    private fun save(activity: Activity, name: String, data: ByteArrayOutputStreamEx): Pair<String, Uri?> {
        val file = "$name.gif"
        val folder = com.phlox.tvwebbrowser.Brand.NAME
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file)
                put(MediaStore.Downloads.MIME_TYPE, "image/gif")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Android didn't allow saving to Downloads")
            activity.contentResolver.openOutputStream(uri)?.use { data.writeTo2(it) }
                ?: throw java.io.IOException("Android didn't allow saving to Downloads")
            return Pair("Download/$folder/$file", uri)
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)
        dir.mkdirs()
        val f = File(dir, file)
        FileOutputStream(f).use { data.writeTo2(it) }
        return Pair("Download/$folder/$file", null)
    }

    private fun done(activity: Activity, where: String, uri: Uri?, size: Int, frames: Int) {
        val sizeText = android.text.format.Formatter.formatShortFileSize(activity, size.toLong())
        val b = AlertDialog.Builder(activity)
            .setTitle(R.string.wisp_gif_done)
            .setMessage(activity.getString(R.string.wisp_gif_done_s, where, sizeText, frames))
            .setPositiveButton(android.R.string.ok, null)
        if (uri != null) {
            b.setNeutralButton(R.string.wisp_gif_open) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/gif")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                    Toast.makeText(activity, R.string.external_app_open_error, Toast.LENGTH_LONG).show()
                }
            }
            b.setNegativeButton(R.string.wisp_share) { _, _ ->
                try {
                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/gif")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        activity.getString(R.string.wisp_share)))
                } catch (e: Exception) {
                }
            }
        }
        b.show()
    }
}
