package com.phlox.tvwebbrowser.sofa

/**
 * Splits an animated WebP into its frames (Wisp's GIF maker). Android can decode a still WebP but
 * gives no access to an animation's frames, so each ANMF frame is rewrapped as a small still WebP
 * file that BitmapFactory can decode; the caller then places it on the canvas at (x, y).
 * Pure Kotlin, no Android classes.
 */
object WebpFrames {
    class Frame(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val durationMs: Int,
        /** draw over the canvas (alpha blending); false: replace the frame's rectangle */
        val blend: Boolean,
        /** clear the frame's rectangle to transparent after showing it */
        val disposeToBackground: Boolean,
        /** a complete still WebP file with just this frame */
        val still: ByteArray
    )

    class Animation(val width: Int, val height: Int, val loopCount: Int, val frames: List<Frame>)

    fun isWebp(b: ByteArray): Boolean = b.size >= 12 && tag(b, 0) == "RIFF" && tag(b, 8) == "WEBP"

    /** The frames of an animated WebP, or null for a still image or anything else. */
    fun parse(b: ByteArray): Animation? {
        if (!isWebp(b)) return null
        var canvasW = 0; var canvasH = 0; var loop = 0
        var animated = false
        val frames = ArrayList<Frame>()
        var p = 12
        val end = minOf(b.size, 8 + u32(b, 4).toInt().coerceAtLeast(0))
        while (p + 8 <= end) {
            val id = tag(b, p)
            val size = u32(b, p + 4).toInt()
            val data = p + 8
            if (size < 0 || data + size > b.size) break
            when (id) {
                "VP8X" -> if (size >= 10) {
                    animated = (b[data].toInt() and 0x02) != 0
                    canvasW = u24(b, data + 4) + 1
                    canvasH = u24(b, data + 7) + 1
                }
                "ANIM" -> if (size >= 6) loop = u16(b, data + 4)
                "ANMF" -> if (size >= 16) frame(b, data, size)?.let { frames.add(it) }
            }
            p = data + size + (size and 1) //chunks are padded to even sizes
        }
        if (!animated || frames.isEmpty() || canvasW <= 0 || canvasH <= 0) return null
        return Animation(canvasW, canvasH, loop, frames)
    }

    private fun frame(b: ByteArray, start: Int, size: Int): Frame? {
        val x = u24(b, start) * 2
        val y = u24(b, start + 3) * 2
        val w = u24(b, start + 6) + 1
        val h = u24(b, start + 9) + 1
        val duration = u24(b, start + 12)
        val flags = b[start + 15].toInt()
        val blend = (flags and 0x02) == 0
        val dispose = (flags and 0x01) != 0
        //the frame's own chunks: optional ALPH, then VP8 (lossy) or VP8L (lossless)
        var p = start + 16
        val end = start + size
        var alph: ByteArray? = null
        var image: ByteArray? = null
        var imageTag = ""
        while (p + 8 <= end) {
            val id = tag(b, p)
            val len = u32(b, p + 4).toInt()
            if (len < 0 || p + 8 + len > b.size) break
            val chunk = b.copyOfRange(p, p + 8 + len + (len and 1).coerceAtMost(b.size - (p + 8 + len)))
            when (id) {
                "ALPH" -> alph = chunk
                "VP8 ", "VP8L" -> { image = chunk; imageTag = id }
            }
            p += 8 + len + (len and 1)
        }
        val img = image ?: return null
        val body = java.io.ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        if (imageTag == "VP8 " && alph != null) {
            //lossy picture with a separate alpha channel: needs the extended (VP8X) header
            body.write("VP8X".toByteArray(Charsets.US_ASCII))
            put32(body, 10)
            body.write(0x10) //alpha
            body.write(0); body.write(0); body.write(0)
            put24(body, w - 1)
            put24(body, h - 1)
            body.write(alph)
        }
        body.write(img)
        val bytes = body.toByteArray()
        val out = java.io.ByteArrayOutputStream(bytes.size + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        put32(out, bytes.size)
        out.write(bytes)
        return Frame(x, y, w, h, if (duration <= 0) 100 else duration, blend, dispose, out.toByteArray())
    }

    private fun tag(b: ByteArray, p: Int) = String(b, p, 4, Charsets.US_ASCII)
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun u24(b: ByteArray, p: Int) = u16(b, p) or ((b[p + 2].toInt() and 0xFF) shl 16)
    private fun u32(b: ByteArray, p: Int): Long = (u24(b, p).toLong()) or ((b[p + 3].toLong() and 0xFF) shl 24)
    private fun put24(o: java.io.ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF) }
    private fun put32(o: java.io.ByteArrayOutputStream, v: Int) { put24(o, v); o.write((v shr 24) and 0xFF) }
}
