package com.phlox.tvwebbrowser

/**
 * Which app this build is: Lumen (TV, remote cursor) or Wisp (phone, touch, private by default).
 * Set once at start by the application class from BuildConfig.IS_WISP, before anything reads it.
 */
object Brand {
    @JvmStatic
    @Volatile
    var phone: Boolean = false

    /** The name shown to the user. */
    @JvmStatic
    val NAME: String get() = if (phone) "Wisp" else "Lumen"
}
