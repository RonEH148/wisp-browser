package com.phlox.tvwebbrowser.sofa

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * Opening a page in another installed browser - Firefox first. Some sites only work in Firefox's
 * own engine; those can be set to always open there (shield menu → Site fixes).
 */
object LumenExternal {
    private val FIREFOX = listOf("org.mozilla.firefox", "org.mozilla.fenix", "org.mozilla.firefox_beta",
        "org.mozilla.tv.firefox", "org.mozilla.focus", "org.mozilla.klar")

    /** Package of the browser to use: an installed Firefox, otherwise null (system chooser). */
    fun firefoxPackage(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val handlers = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
        return FIREFOX.firstOrNull { it in handlers } ?: FIREFOX.firstOrNull {
            try { pm.getPackageInfo(it, 0); true } catch (e: Exception) { false }
        }
    }

    /** Opens [url] in Firefox (or lets the user pick a browser). Returns false if nothing could open it. */
    fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ff = firefoxPackage(context)
        if (ff != null) {
            try {
                context.startActivity(Intent(intent).setPackage(ff))
                return true
            } catch (ignored: Exception) {
            }
        }
        //any other browser except Lumen itself
        val others = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
        if (others.isEmpty()) {
            Toast.makeText(context, "No other browser is installed", Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }
}
