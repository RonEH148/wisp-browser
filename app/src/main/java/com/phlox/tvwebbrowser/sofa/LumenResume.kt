package com.phlox.tvwebbrowser.sofa

import android.content.Context
import org.json.JSONObject

/** Where each video was left in Lumen's player (the last 300 videos). */
object LumenResume {
    private const val PREFS = "lumen_resume"
    private const val KEY = "positions"
    private const val MAX = 300

    private fun load(context: Context): JSONObject = try {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(context: Context, json: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    fun get(context: Context, key: String): Long = load(context).optJSONObject(key)?.optLong("p", 0L) ?: 0L

    fun put(context: Context, key: String, positionMs: Long) {
        val json = load(context)
        json.put(key, JSONObject().put("p", positionMs).put("t", System.currentTimeMillis()))
        if (json.length() > MAX) {
            //forget the oldest
            val keys = json.keys().asSequence().toList().sortedBy { json.optJSONObject(it)?.optLong("t") ?: 0L }
            keys.take(json.length() - MAX).forEach { json.remove(it) }
        }
        save(context, json)
    }

    fun remove(context: Context, key: String) {
        val json = load(context)
        if (json.has(key)) {
            json.remove(key)
            save(context, json)
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
