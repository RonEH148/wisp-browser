package com.phlox.tvwebbrowser.sofa

import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Lumen's ad blocking engine: Brave's adblock-rust (understands uBlock Origin's filter syntax,
 * including element hiding), compiled from app/adblock-rust.
 *
 * If the library is missing from the APK (it could not be built), [available] is false and
 * AdblockModel keeps using the older built-in engine.
 */
object LumenAdblock {
    private const val TAG = "LumenAdblock"

    val available: Boolean = try {
        System.loadLibrary("lumen_adblock")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "adblock-rust library not available: ${e.message}")
        false
    }

    private val lock = ReentrantReadWriteLock()
    private var engine = 0L

    /** True once filter lists are loaded into adblock-rust. */
    val active: Boolean get() = lock.read { engine != 0L }

    @JvmStatic private external fun nativeCreate(listsPath: String): Long
    @JvmStatic private external fun nativeLoad(path: String): Long
    @JvmStatic private external fun nativeSave(ptr: Long, path: String): Boolean
    @JvmStatic private external fun nativeUseResources(ptr: Long, path: String): Int
    @JvmStatic private external fun nativeDestroy(ptr: Long)
    @JvmStatic private external fun nativeMatch(ptr: Long, url: String, sourceUrl: String, type: String): Int
    @JvmStatic private external fun nativeCosmetic(ptr: Long, url: String): String?
    @JvmStatic private external fun nativeClassIds(ptr: Long, url: String, classes: String, ids: String): String?

    /** Parses the joined filter lists in [listsFile]. Returns an engine handle, or 0. */
    fun create(listsFile: File): Long = if (available) nativeCreate(listsFile.absolutePath) else 0L

    /** Loads an engine saved with [save]. Returns an engine handle, or 0. */
    fun load(file: File): Long = if (available && file.exists()) nativeLoad(file.absolutePath) else 0L

    fun save(handle: Long, file: File): Boolean = available && handle != 0L && nativeSave(handle, file.absolutePath)

    /** Scriptlet/redirect resources (adblock-rust JSON). Only for a handle not yet [install]ed. */
    fun useResources(handle: Long, file: File): Int =
        if (available && handle != 0L && file.exists()) nativeUseResources(handle, file.absolutePath) else -1

    /** Makes [handle] the engine in use; the previous one is freed once no call uses it. */
    fun install(handle: Long) {
        if (!available || handle == 0L) return
        val old = lock.write {
            val previous = engine
            engine = handle
            previous
        }
        if (old != 0L && old != handle) nativeDestroy(old)
    }

    /** Frees a handle that was never installed. */
    fun discard(handle: Long) {
        if (available && handle != 0L) nativeDestroy(handle)
    }

    fun matches(url: String, sourceUrl: String, type: String): Boolean = lock.read {
        engine != 0L && nativeMatch(engine, url, sourceUrl, type) == 1
    }

    /** JSON {"css", "script", "generichide"} for a page or frame address, or null. */
    fun cosmetic(url: String): String? = lock.read {
        if (engine == 0L) null else nativeCosmetic(engine, url)
    }

    /** CSS hiding the generic ad rules that match these classes and ids (space separated). */
    fun classIds(url: String, classes: String, ids: String): String? = lock.read {
        if (engine == 0L) null else nativeClassIds(engine, url, classes, ids)
    }
}
