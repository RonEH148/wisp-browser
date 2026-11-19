package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.content.Context
import java.net.URL
import java.net.URLConnection

/**
 * Wisp: no Google Play services. The player and the stream proxy use Android's own HttpEngine
 * (Android 14+, or 12/13 with the module update) or the basic connection instead.
 * (Lumen's copy of this file, in src/foss, src/generic and src/google, uses Play services' Cronet.)
 */
object CronetSupport {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun installProvider(activity: Activity) {}

    @Suppress("UNUSED_PARAMETER")
    fun playerFactory(context: Context, headers: Map<String, String>, userAgent: String?):
            androidx.media3.datasource.HttpDataSource.Factory? = null

    @Suppress("UNUSED_PARAMETER")
    fun opener(context: Context): ((URL) -> URLConnection)? = null
}
