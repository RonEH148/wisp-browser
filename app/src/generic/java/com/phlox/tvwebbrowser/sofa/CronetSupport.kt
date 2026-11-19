package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.content.Context
import java.net.URL
import java.net.URLConnection

/**
 * Lumen (TV builds): Chrome's network stack from Google Play services (Cronet). Android TVs before
 * Android 14 have no HttpEngine; without this, servers behind Cloudflare answer HTTP 403 to the
 * player. Wisp has its own copy of this file without any Google code (src/wisp).
 */
object CronetSupport {
    const val AVAILABLE = true

    /** Gets the Play services provider ready (it may download an update in the background). */
    fun installProvider(activity: Activity) {
        try {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(activity)
        } catch (e: Throwable) {
        }
    }

    private fun playServicesProvider(context: Context): org.chromium.net.CronetProvider? =
        org.chromium.net.CronetProvider.getAllProviders(context).firstOrNull {
            //"Google-Play-Services-Cronet-Provider" (its name constant isn't public)
            it.isEnabled && it.name.contains("Play-Services", ignoreCase = true)
        }

    /** A player data source on Play services' Cronet, or null when it isn't on this device. */
    fun playerFactory(context: Context, headers: Map<String, String>, userAgent: String?):
            androidx.media3.datasource.HttpDataSource.Factory? {
        return try {
            val provider = playServicesProvider(context) ?: return null
            val builder = provider.createBuilder()
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
            userAgent?.let { builder.setUserAgent(it) }
            val engine = builder.build()
            androidx.media3.datasource.cronet.CronetDataSource.Factory(engine, java.util.concurrent.Executors.newSingleThreadExecutor())
                .setDefaultRequestProperties(headers)
                .setUserAgent(userAgent)
                .setConnectionTimeoutMs(20000)
                .setReadTimeoutMs(20000)
        } catch (e: Throwable) {
            null
        }
    }

    /** Opens connections through Play services' Cronet, or null when it isn't on this device. */
    fun opener(context: Context): ((URL) -> URLConnection)? {
        return try {
            val provider = playServicesProvider(context) ?: return null
            val engine = provider.createBuilder().enableHttp2(true).enableQuic(true).enableBrotli(true).build()
            val open: (URL) -> URLConnection = { url -> engine.openConnection(url) }
            open
        } catch (e: Throwable) {
            null
        }
    }
}
