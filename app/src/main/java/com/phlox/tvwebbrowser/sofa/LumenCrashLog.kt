package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the details of the last crash so they can be shown (and copied) the next time Lumen starts.
 * Nothing is sent anywhere.
 */
object LumenCrashLog {
    private const val FILE_NAME = "lumen_last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = try {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                } catch (e: Exception) { "?" }
                val text = "Lumen $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                        " · thread ${thread.name}\n\n" + sw.toString()
                File(appContext.filesDir, FILE_NAME).writeText(text.take(60_000))
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Shows the last crash once (with a Copy button) and forgets it. */
    fun showIfAny(activity: Activity) {
        val file = File(activity.filesDir, FILE_NAME)
        if (!file.exists()) return
        val text = try { file.readText() } catch (e: Exception) { "" }
        file.delete()
        if (text.isBlank()) return
        val firstLines = text.lineSequence().drop(3).take(6).joinToString("\n")
        AlertDialog.Builder(activity)
            .setTitle("${com.phlox.tvwebbrowser.Brand.NAME} closed unexpectedly last time")
            .setMessage("If it happens again, tap Copy details and send them so it can be fixed.\n\n$firstLines")
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Copy details") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lumen crash", text))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
