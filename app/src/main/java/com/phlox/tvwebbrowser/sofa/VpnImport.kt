package com.phlox.tvwebbrowser.sofa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * "Import from file or archive", like the WireGuard app: a picked file can be
 *  - a WireGuard .conf (text),
 *  - a .zip with one or more .conf files (as the WireGuard app and many VPN providers export them),
 *  - a picture of a WireGuard QR code (screenshot or photo).
 * Reading happens off the screen's thread; the caller saves the chosen config with SofaVpn.importConfig.
 */
object VpnImport {

    /** One WireGuard config found in the file: its name (file name in the archive) and its text. */
    class Found(val name: String, val text: String)

    sealed class Result {
        class Configs(val list: List<Found>) : Result()
        /** the file couldn't be read, or nothing usable was in it */
        object Nothing : Result()
        /** a picture without a readable QR code */
        object NoQrCode : Result()
    }

    private const val MAX_FILE = 20 * 1024 * 1024
    private const val MAX_CONF = 100_000

    fun read(context: Context, uri: Uri): Result {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_FILE) return Result.Nothing
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        } ?: return Result.Nothing
        val name = fileName(context, uri)
        return when {
            isZip(bytes) -> fromZip(bytes)
            isImage(bytes) -> fromQrPicture(bytes)
            else -> {
                if (bytes.size > MAX_CONF) return Result.Nothing
                val text = String(bytes, Charsets.UTF_8)
                if (looksLikeConfig(text)) Result.Configs(listOf(Found(name ?: "WireGuard", text)))
                else Result.Nothing
            }
        }
    }

    private fun looksLikeConfig(text: String) = text.contains("[Interface]", ignoreCase = true)

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
            b[2] == 3.toByte() && b[3] == 4.toByte()

    private fun isImage(b: ByteArray): Boolean {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun fromZip(bytes: ByteArray): Result {
        val found = ArrayList<Found>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val entryName = e.name.substringAfterLast('/')
                    if (!entryName.endsWith(".conf", ignoreCase = true) || entryName.startsWith(".")) continue
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_CONF) break
                    }
                    val text = out.toString("UTF-8")
                    if (out.size() <= MAX_CONF && looksLikeConfig(text)) {
                        found.add(Found(entryName.removeSuffix(".conf").removeSuffix(".CONF"), text))
                    }
                    if (found.size >= 200) break
                }
            }
        } catch (e: Exception) {
            if (found.isEmpty()) return Result.Nothing
        }
        return if (found.isEmpty()) Result.Nothing else Result.Configs(found.sortedBy { it.name.lowercase() })
    }

    /** A screenshot or photo of the QR code the WireGuard app (or a VPN provider) shows. */
    private fun fromQrPicture(bytes: ByteArray): Result {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 3000 || bounds.outHeight / sample > 3000) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return Result.Nothing
        var text: String? = null
        //ZXing finds a code best at a certain size: photos are tried at several sizes
        //(tested with screenshots, small images, blurred and tilted photos and inverted codes)
        for (max in intArrayOf(2000, 1400, 1000, 700, 500)) {
            val scaled = scaleTo(bmp, max)
            text = decodeQr(scaled)
            if (scaled !== bmp) scaled.recycle()
            if (text != null) break
        }
        bmp.recycle()
        if (text == null) return Result.NoQrCode
        return if (looksLikeConfig(text)) Result.Configs(listOf(Found("WireGuard", text))) else Result.NoQrCode
    }

    private fun scaleTo(bmp: Bitmap, max: Int): Bitmap {
        val big = maxOf(bmp.width, bmp.height)
        if (big <= max) return bmp
        val f = max.toFloat() / big
        return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true)
    }

    private fun decodeQr(bmp: Bitmap): String? {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = com.google.zxing.RGBLuminanceSource(w, h, pixels)
        val hints = java.util.EnumMap<com.google.zxing.DecodeHintType, Any>(com.google.zxing.DecodeHintType::class.java)
        hints[com.google.zxing.DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
        //dark codes on light and light on dark; two ways of telling black from white
        for (src in listOf(source, source.invert())) {
            for (binarizer in listOf(com.google.zxing.common.HybridBinarizer(src),
                    com.google.zxing.common.GlobalHistogramBinarizer(src))) {
                try {
                    return com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(binarizer), hints).text
                } catch (e: Exception) {
                    //not found this way: try the next
                }
            }
        }
        return null
    }

    private fun fileName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.substringBeforeLast('.')
    } catch (e: Exception) {
        null
    }
}
