package com.phlox.tvwebbrowser.sofa

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.model.Download
import java.io.File

/**
 * Where downloads are saved.
 *  - Default: internal storage → Download/Lumen
 *  - Or any folder picked with the system file manager (internal storage, SD card, USB drive),
 *    stored as a Storage Access Framework tree URI,
 *  - or a folder picked with Lumen's own folder browser (TVs often have no system picker),
 *    stored as a plain path such as /storage/emulated/0/Movies or /storage/1A2B-3C4D/Films.
 */
object LumenDownloads {
    val SUBFOLDER: String get() = com.phlox.tvwebbrowser.Brand.NAME

    fun isContentPath(path: String): Boolean = path.startsWith("content://")

    /** The folder chosen in settings, or null if none is set or it can't be used right now. */
    fun customFolder(): Uri? {
        val s = AppContext.provideConfig().lumenDownloadFolderUri
        if (s.isEmpty() || !isContentPath(s)) return null
        val uri = Uri.parse(s)
        //the permission may have been revoked (e.g. USB drive formatted or replaced)
        val granted = AppContext.get().contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!granted) return null
        return if (isReachable(uri)) uri else null
    }

    /** True when a folder was chosen in settings but is unavailable (e.g. USB drive unplugged). */
    fun customFolderMissing(): Boolean {
        val s = AppContext.provideConfig().lumenDownloadFolderUri
        if (s.isEmpty()) return false
        return if (isContentPath(s)) customFolder() == null else customFolderFile() == null
    }

    /** The folder chosen with the built-in folder browser, if it is there right now. */
    fun customFolderFile(): File? {
        val s = AppContext.provideConfig().lumenDownloadFolderUri
        if (!s.startsWith("/")) return null
        val dir = File(s)
        return if (dir.isDirectory) dir else null
    }

    /** Creates and deletes a small file to prove the folder is writable. Returns an error or null. */
    fun testWritable(dir: File): String? = try {
        val f = File(dir, ".lumen_write_test")
        java.io.FileOutputStream(f).use { it.write(byteArrayOf(1, 2, 3)) }
        f.delete()
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    /** "Internal storage/Movies", "USB / SD (1A2B-3C4D)/Films" */
    fun pathLabel(path: String): String {
        @Suppress("DEPRECATION")
        val internal = Environment.getExternalStorageDirectory().absolutePath
        if (path == internal || path.startsWith("$internal/")) return "Internal storage" + path.removePrefix(internal)
        val m = Regex("^/storage/([^/]+)(.*)$").find(path) ?: return path
        return "USB / SD (${m.groupValues[1]})" + m.groupValues[2]
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    /** Checks that the folder still exists, e.g. that the USB drive is plugged in. */
    fun isReachable(tree: Uri): Boolean = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        AppContext.get().contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    /** Creates and deletes a small file to prove the folder is writable. Returns an error or null. */
    fun testWritable(tree: Uri): String? {
        val resolver = AppContext.get().contentResolver
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", ".lumen_write_test")
                ?: return "The folder can't be written to"
            resolver.openFileDescriptor(doc, "w")?.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { it.write(byteArrayOf(1, 2, 3)) }
            }
            DocumentsContract.deleteDocument(resolver, doc)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    @Suppress("DEPRECATION")
    fun defaultFolderFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBFOLDER)

    /** Human readable name of the current download folder for the settings screen. */
    /**
     * True when downloads go to a USB drive or SD card (not internal storage). Doesn't touch the
     * drive: a busy or hanging drive must never block the screen.
     */
    fun targetIsRemovable(download: Download? = null): Boolean {
        val p = download?.filepath?.takeIf { it.isNotEmpty() } ?: AppContext.provideConfig().lumenDownloadFolderUri
        if (p.isEmpty()) return false
        @Suppress("DEPRECATION")
        val internal = Environment.getExternalStorageDirectory().absolutePath
        if (p.startsWith("/")) return !(p == internal || p.startsWith("$internal/") || p.startsWith("/data/"))
        if (p.startsWith("content://media")) return false
        //SAF tree or document of the chosen folder: "primary:" is internal storage
        return try {
            val id = if (p.contains("/document/")) DocumentsContract.getDocumentId(Uri.parse(p))
                else DocumentsContract.getTreeDocumentId(Uri.parse(p))
            !id.substringBefore(':').equals("primary", true)
        } catch (e: Exception) { false }
    }

    /** The download folder's name for messages, from the setting only (no access to the drive). */
    fun folderLabel(context: Context): String {
        val s = AppContext.provideConfig().lumenDownloadFolderUri
        if (s.startsWith("/")) return pathLabel(s)
        val custom = customFolder() ?: return "Internal storage/${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
        return try {
            val docId = DocumentsContract.getTreeDocumentId(custom) //e.g. "primary:Movies" or "1A2B-3C4D:Films"
            val volume = docId.substringBefore(':')
            val path = docId.substringAfter(':', "")
            val volumeName = if (volume.equals("primary", true)) "Internal storage" else "USB / SD ($volume)"
            if (path.isEmpty()) volumeName else "$volumeName/$path"
        } catch (e: Exception) {
            custom.toString()
        }
    }

    private fun mimeFor(download: Download): String {
        download.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { return it }
        val ext = download.filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * Creates the target file and opens it for reading and writing. Updates [Download.filepath]
     * (a content:// URI or a plain file path).
     */
    fun openOutput(download: Download): ParcelFileDescriptor {
        val resolver = AppContext.get().contentResolver
        val dir = customFolderFile()
        if (dir != null) {
            try {
                val f = uniqueFile(dir, download.filename.ifEmpty { "download" })
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                download.filepath = f.absolutePath
                download.filename = f.name
                return pfd
            } catch (e: Exception) {
                //no longer writable (permission removed, drive read-only): use the default folder
                e.printStackTrace()
            }
        }
        val custom = customFolder()
        if (custom != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(custom, DocumentsContract.getTreeDocumentId(custom))
            val name = download.filename.ifEmpty { "download" }
            val doc = DocumentsContract.createDocument(resolver, parent, mimeFor(download), name)
                ?: throw IllegalStateException("Couldn't create the file in the chosen folder")
            download.filepath = doc.toString()
            //the provider may have renamed it ("file (1).mp4")
            try {
                resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) download.filename = it.getString(0) ?: download.filename
                }
            } catch (ignored: Exception) {
            }
            return resolver.openFileDescriptor(doc, "rw") ?: throw IllegalStateException("Couldn't open the file")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = ContentValues().apply {
                if (download.filename.isNotEmpty()) put(MediaStore.Downloads.DISPLAY_NAME, download.filename)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + SUBFOLDER)
                put(MediaStore.Downloads.DOWNLOAD_URI, download.url)
                put(MediaStore.Downloads.REFERER_URI, download.referer)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("Couldn't create the file")
            download.filepath = uri.toString()
            return resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Couldn't open the file")
        }
        val file = File(download.filepath)
        file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }

    /** A paused or interrupted download's file, opened again without emptying it. */
    fun reopenOutput(download: Download): ParcelFileDescriptor {
        val p = download.filepath
        if (isContentPath(p)) {
            return AppContext.get().contentResolver.openFileDescriptor(Uri.parse(p), "rw")
                ?: throw IllegalStateException("Couldn't open the file")
        }
        return ParcelFileDescriptor.open(File(p), ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
    }

    private fun isMediaStoreUri(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    /** Marks a finished MediaStore download as visible to other apps. */
    fun publish(download: Download) {
        if (!isContentPath(download.filepath)) {
            //let galleries and video apps see the new file
            if (download.filepath.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(AppContext.get(), arrayOf(download.filepath), null, null)
                } catch (ignored: Exception) {
                }
            }
            return
        }
        val uri = Uri.parse(download.filepath)
        if (!isMediaStoreUri(uri) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            AppContext.get().contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun delete(path: String): Boolean {
        if (path.isEmpty()) return false
        if (!isContentPath(path)) return File(path).delete()
        val resolver = AppContext.get().contentResolver
        val uri = Uri.parse(path)
        return try {
            if (isMediaStoreUri(uri)) resolver.delete(uri, null, null) > 0
            else DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
