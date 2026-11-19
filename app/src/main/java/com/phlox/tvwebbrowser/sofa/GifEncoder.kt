package com.phlox.tvwebbrowser.sofa

import java.io.OutputStream

/**
 * Wisp's GIF maker (WebM / WebP → GIF): a small animated GIF89a writer with one shared colour
 * table, plus a median-cut palette and a fast colour lookup. Pure Kotlin (no Android classes),
 * so the frames can come from any decoder.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    /** up to 256 colours as 0xRRGGBB; padded to 256 entries */
    palette: IntArray,
    /** palette index used for transparent pixels, or -1 */
    private val transparentIndex: Int = -1,
    /** 0 = loop forever */
    loopCount: Int = 0
) {
    init {
        require(width in 1..65535 && height in 1..65535)
        writeAscii("GIF89a")
        writeShort(width)
        writeShort(height)
        //global colour table, 8 bits per channel, 256 entries
        out.write(0x80 or 0x70 or 0x07)
        out.write(0) //background colour index
        out.write(0) //pixel aspect ratio
        for (i in 0 until 256) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        //NETSCAPE2.0: loop
        out.write(0x21); out.write(0xFF); out.write(11)
        writeAscii("NETSCAPE2.0")
        out.write(3); out.write(1); writeShort(loopCount); out.write(0)
    }

    /**
     * One full-size frame: [pixels] are palette indexes (width × height), [delayMs] how long it is
     * shown. Frames with transparent pixels clear to transparent before the next one.
     */
    fun addFrame(pixels: ByteArray, delayMs: Int) {
        require(pixels.size == width * height)
        val hasTransparency = transparentIndex >= 0
        //graphic control extension
        out.write(0x21); out.write(0xF9); out.write(4)
        val disposal = if (hasTransparency) 2 else 1
        out.write((disposal shl 2) or (if (hasTransparency) 1 else 0))
        writeShort(((delayMs + 5) / 10).coerceIn(2, 65535))
        out.write(if (hasTransparency) transparentIndex else 0)
        out.write(0)
        //image descriptor: whole canvas, no local colour table
        out.write(0x2C)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
        out.write(0)
        lzw(pixels)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    // ---------------- LZW (8-bit symbols) ----------------
    private val subBlock = ByteArray(255)
    private var subLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    private fun lzw(pixels: ByteArray) {
        val minCodeSize = 8
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        //dictionary: (prefix code << 8 | byte) → code, open addressing
        val hashSize = 8209
        val keys = IntArray(hashSize) { -1 }
        val values = IntArray(hashSize)
        bitBuf = 0; bitCount = 0; subLen = 0
        emit(clear, codeSize)
        if (pixels.isEmpty()) {
            emit(eoi, codeSize); flushBits(); out.write(0); return
        }
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            var h = ((key * 0x9E3779B1.toInt()) ushr 1) % hashSize
            var found = -1
            while (keys[h] != -1) {
                if (keys[h] == key) { found = values[h]; break }
                h = (h + 1) % hashSize
            }
            if (found >= 0) {
                prefix = found
                continue
            }
            emit(prefix, codeSize)
            if (next < 4096) {
                keys[h] = key
                values[h] = next
                next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                //table full: start again
                emit(clear, codeSize)
                java.util.Arrays.fill(keys, -1)
                codeSize = minCodeSize + 1
                next = eoi + 1
            }
            prefix = k
        }
        emit(prefix, codeSize)
        emit(eoi, codeSize)
        flushBits()
        out.write(0) //block terminator
    }

    private fun emit(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += size
        while (bitCount >= 8) {
            byte(bitBuf and 0xFF)
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun flushBits() {
        if (bitCount > 0) byte(bitBuf and 0xFF)
        bitBuf = 0; bitCount = 0
        if (subLen > 0) { out.write(subLen); out.write(subBlock, 0, subLen); subLen = 0 }
    }

    private fun byte(b: Int) {
        subBlock[subLen++] = b.toByte()
        if (subLen == 255) { out.write(255); out.write(subBlock, 0, 255); subLen = 0 }
    }

    private fun writeShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    private fun writeAscii(s: String) { for (c in s) out.write(c.code) }

    companion object {
        /**
         * Median cut: up to [maxColors] colours (0xRRGGBB) that represent the sampled pixels
         * (0xAARRGGBB; transparent ones are skipped).
         */
        fun medianCut(samples: IntArray, count: Int, maxColors: Int): IntArray {
            val px = IntArray(count)
            var n = 0
            for (i in 0 until count) {
                val c = samples[i]
                if ((c ushr 24) >= 128) px[n++] = c and 0xFFFFFF
            }
            if (n == 0) return intArrayOf(0)
            class Box(val from: Int, val to: Int) //range in px
            val boxes = ArrayList<Box>()
            boxes.add(Box(0, n))
            fun ch(c: Int, k: Int) = when (k) { 0 -> (c shr 16) and 0xFF; 1 -> (c shr 8) and 0xFF; else -> c and 0xFF }
            fun spread(b: Box): IntArray { //[channel, range]
                var best = 0; var bestRange = -1
                for (k in 0..2) {
                    var lo = 255; var hi = 0
                    for (i in b.from until b.to) { val v = ch(px[i], k); if (v < lo) lo = v; if (v > hi) hi = v }
                    if (hi - lo > bestRange) { bestRange = hi - lo; best = k }
                }
                return intArrayOf(best, bestRange)
            }
            while (boxes.size < maxColors) {
                //split the box with the most pixels × widest range
                var pick = -1; var pickScore = 0L; var pickCh = 0
                for ((idx, b) in boxes.withIndex()) {
                    if (b.to - b.from < 2) continue
                    val s = spread(b)
                    if (s[1] == 0) continue
                    val score = s[1].toLong() * (b.to - b.from)
                    if (score > pickScore) { pickScore = score; pick = idx; pickCh = s[0] }
                }
                if (pick < 0) break
                val b = boxes.removeAt(pick)
                val sub = px.copyOfRange(b.from, b.to).sortedBy { ch(it, pickCh) }
                for (i in sub.indices) px[b.from + i] = sub[i]
                val mid = b.from + (b.to - b.from) / 2
                boxes.add(Box(b.from, mid))
                boxes.add(Box(mid, b.to))
            }
            return IntArray(boxes.size) { bi ->
                val b = boxes[bi]
                var r = 0L; var g = 0L; var bl = 0L
                for (i in b.from until b.to) { val c = px[i]; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF }
                val cnt = (b.to - b.from).coerceAtLeast(1)
                ((r / cnt).toInt() shl 16) or ((g / cnt).toInt() shl 8) or (bl / cnt).toInt()
            }
        }
    }

    /** Nearest palette colour for a pixel, cached per 15-bit colour. */
    class Mapper(private val palette: IntArray, private val transparentIndex: Int) {
        private val cache = IntArray(32768) { -1 }

        fun index(argb: Int): Int {
            if (transparentIndex >= 0 && (argb ushr 24) < 128) return transparentIndex
            val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val c = cache[key]
            if (c >= 0) return c
            var best = 0; var bestD = Int.MAX_VALUE
            for (i in palette.indices) {
                if (i == transparentIndex) continue
                val p = palette[i]
                val dr = r - ((p shr 16) and 0xFF); val dg = g - ((p shr 8) and 0xFF); val db = b - (p and 0xFF)
                //perceptual-ish weights
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bestD) { bestD = d; best = i }
            }
            cache[key] = best
            return best
        }

        fun map(pixels: IntArray, out: ByteArray) {
            for (i in pixels.indices) out[i] = index(pixels[i]).toByte()
        }
    }
}
