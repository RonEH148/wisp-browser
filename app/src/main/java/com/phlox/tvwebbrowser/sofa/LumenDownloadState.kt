package com.phlox.tvwebbrowser.sofa

import android.content.Context
import com.phlox.tvwebbrowser.AppContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a paused or interrupted download stopped, so it can continue later (also after the app
 * was closed): the parts still missing, or the bytes already written for a one-connection download.
 */
object LumenDownloadState {
    private const val PREFS = "lumen_download_state"

    class State(
        val url: String,
        val referer: String?,
        val userAgent: String?,
        val mimeType: String?,
        val size: Long,
        /** Byte ranges still missing (start, end inclusive); empty for a one-connection download. */
        val missing: List<LongArray>,
        /** One-connection download: bytes already in the file. */
        val written: Long,
        /** USB drives without random access: the parts are collected in this file first. */
        val tempFile: String?,
        /** true = paused by the user, false = stopped by an error. */
        val paused: Boolean
    ) {
        val received: Long
            get() = if (missing.isEmpty()) written else size - missing.sumOf { it[1] - it[0] + 1 }
    }

    private fun prefs() = AppContext.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(id: Long, s: State) {
        val segs = JSONArray()
        for (m in s.missing) segs.put(JSONArray().put(m[0]).put(m[1]))
        val o = JSONObject()
            .put("url", s.url).put("ref", s.referer ?: "").put("ua", s.userAgent ?: "").put("mime", s.mimeType ?: "")
            .put("size", s.size).put("segs", segs).put("written", s.written).put("temp", s.tempFile ?: "")
            .put("paused", s.paused)
        prefs().edit().putString(id.toString(), o.toString()).apply()
    }

    fun get(id: Long): State? {
        val raw = prefs().getString(id.toString(), null) ?: return null
        return try {
            val o = JSONObject(raw)
            val segs = o.optJSONArray("segs") ?: JSONArray()
            State(
                o.getString("url"), o.optString("ref").ifEmpty { null }, o.optString("ua").ifEmpty { null },
                o.optString("mime").ifEmpty { null }, o.getLong("size"),
                (0 until segs.length()).map { val a = segs.getJSONArray(it); longArrayOf(a.getLong(0), a.getLong(1)) },
                o.optLong("written"), o.optString("temp").ifEmpty { null }, o.optBoolean("paused")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads with a saved state (paused, interrupted or queued). */
    fun ids(): List<Long> = prefs().all.keys.mapNotNull { it.toLongOrNull() }

    fun remove(id: Long) {
        get(id)?.tempFile?.let { try { java.io.File(it).delete() } catch (ignored: Exception) {} }
        prefs().edit().remove(id.toString()).apply()
    }
}
