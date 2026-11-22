package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wisp diagnostics for UI bugs: a picture of the screen (only when the person reports a problem)
 * and the layout as text - every visible element with its position and size, the screen size,
 * the status/navigation bar and keyboard sizes. Typed text is never written down.
 */
object WispSnapshot {
    private const val KEEP = 10

    fun dir(c: Context) = File(File(c.filesDir, "diag"), "reports").apply { mkdirs() }

    /** Newest first. */
    fun reports(c: Context): List<File> =
        (dir(c).listFiles()?.toList() ?: emptyList()).sortedByDescending { it.lastModified() }

    private fun prune(c: Context) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val all = reports(c)
        val stems = all.map { it.nameWithoutExtension }.distinct()
        val keepStems = stems.take(KEEP * 2).toSet()
        for (f in all) if (f.lastModified() < cutoff || f.nameWithoutExtension !in keepStems) f.delete()
    }

    // ---------------- layout as text ----------------

    fun layoutText(a: Activity, extra: Window? = null): String {
        val sb = StringBuilder()
        val root = a.window.decorView
        val dm = a.resources.displayMetrics
        sb.append("Screen ${dm.widthPixels}x${dm.heightPixels} px, density ${dm.density} (${(dm.widthPixels / dm.density).toInt()}x${(dm.heightPixels / dm.density).toInt()} dp), ")
        sb.append(if (a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        sb.append(", font scale ${a.resources.configuration.fontScale}")
        sb.append(", dark ${(a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES}\n")
        ViewCompat.getRootWindowInsets(root)?.let { ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.statusBars())
            val n = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
            val k = ins.getInsets(WindowInsetsCompat.Type.ime())
            val cut = ins.getInsets(WindowInsetsCompat.Type.displayCutout())
            sb.append("Status bar ${s.top} px${if (!ins.isVisible(WindowInsetsCompat.Type.statusBars())) " (hidden)" else ""}, ")
            sb.append("navigation bar l${n.left} r${n.right} b${n.bottom} px, keyboard ${if (ins.isVisible(WindowInsetsCompat.Type.ime())) "${k.bottom} px" else "closed"}, ")
            sb.append("cutout t${cut.top} l${cut.left} r${cut.right}\n")
        }
        sb.append("Window focus ${a.hasWindowFocus()}\n\n")
        var count = 0
        fun walk(v: View, depth: Int) {
            if (count > 700 || depth > 30) return
            if (v.visibility == View.GONE) return
            count++
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            sb.append("  ".repeat(depth)).append(v.javaClass.simpleName.ifEmpty { "View" })
            val id = if (v.id != View.NO_ID) try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { null } else null
            if (id != null) sb.append('#').append(id)
            sb.append(" [${loc[0]},${loc[1]} ${v.width}x${v.height}]")
            if (v.visibility == View.INVISIBLE) sb.append(" invisible")
            if (v.alpha < 1f) sb.append(" alpha=${"%.2f".format(Locale.US, v.alpha)}")
            if (v.translationY != 0f || v.translationX != 0f) sb.append(" moved=${v.translationX.toInt()},${v.translationY.toInt()}")
            if (!v.isEnabled) sb.append(" disabled")
            if (v.hasFocus() && v !is ViewGroup) sb.append(" focused")
            if (v is EditText) {
                val pw = (v.inputType and InputType.TYPE_MASK_VARIATION) in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                val t = v.text?.toString() ?: ""
                sb.append(when {
                    pw -> " (password field)"
                    id == "etUrl" && !v.hasFocus() -> " \"" + WispLog.site(t) + "\""
                    t.isEmpty() -> " (empty, hint \"${v.hint ?: ""}\")"
                    else -> " (typed text, ${t.length} characters)"
                })
            } else if (v is TextView) {
                val t = v.text?.toString()?.replace('\n', ' ')?.trim() ?: ""
                if (t.isNotEmpty()) sb.append(" \"").append(t.take(50)).append('"')
            } else if (v.contentDescription != null) {
                sb.append(" '").append(v.contentDescription.toString().take(40)).append('\'')
            }
            sb.append('\n')
            if (v is ViewGroup && v !is android.webkit.WebView) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        if (extra != null) {
            sb.append("\n-- window on top --\n")
            walk(extra.decorView, 0)
        }
        return sb.toString()
    }

    // ---------------- picture ----------------

    /** A picture of the activity's window (including web pages, which View.draw can miss). */
    fun capture(a: Activity, done: (Bitmap?) -> Unit) {
        val v = a.window.decorView
        if (v.width <= 0 || v.height <= 0) { done(null); return }
        try {
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(a.window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) done(bmp) else { bmp.recycle(); done(null) }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Throwable) {
            WispLog.error("report", "screenshot failed", e)
            done(null)
        }
    }

    /**
     * Saves report-<time>.txt (layout and [note]) and, with [picture], report-<time>.jpg.
     * Calls [done] with the name (without extension) on the main thread.
     */
    fun save(a: Activity, tag: String, note: String, picture: Bitmap?, extra: Window? = null, done: ((String) -> Unit)? = null) {
        val c = a.applicationContext
        val name = tag + "-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val layout = try { layoutText(a, extra) } catch (e: Throwable) { "layout failed: $e" }
        Thread {
            try {
                File(dir(c), "$name.txt").writeText(note + "\n\n" + layout)
                if (picture != null) {
                    val maxW = 1080
                    val scaled = if (picture.width > maxW) Bitmap.createScaledBitmap(picture, maxW,
                        (picture.height * maxW.toFloat() / picture.width).toInt(), true) else picture
                    FileOutputStream(File(dir(c), "$name.jpg")).use { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                    if (scaled !== picture) scaled.recycle()
                    picture.recycle()
                }
                prune(c)
            } catch (e: Throwable) {
                WispLog.error("report", "saving the report failed", e)
            }
            Handler(Looper.getMainLooper()).post { done?.invoke(name) }
        }.start()
    }

    /** The newest report's layout text, for the copied log. */
    fun latestText(c: Context, maxChars: Int = 8000): String? =
        reports(c).firstOrNull { it.extension == "txt" }?.let { f ->
            "(${f.name})\n" + try { f.readText().take(maxChars) } catch (e: Exception) { "" }
        }

    /** Copies the newest pictures where the share sheet can read them; returns content:// addresses. */
    fun sharePictures(c: Context, max: Int = 3): ArrayList<android.net.Uri> {
        val out = ArrayList<android.net.Uri>()
        val shareDir = File(c.cacheDir, "diag-share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        for (f in reports(c).filter { it.extension == "jpg" }.take(max)) {
            try {
                val copy = File(shareDir, f.name)
                f.copyTo(copy, overwrite = true)
                out.add(androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".provider", copy))
            } catch (e: Exception) {
                WispLog.error("report", "sharing a picture failed", e)
            }
        }
        return out
    }
}
