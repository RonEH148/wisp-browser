package com.phlox.tvwebbrowser.model

import androidx.room.*
import java.io.InputStream

/**
 * Created by PDT on 23.01.2017.
 */

@Entity(tableName = "downloads", indices = arrayOf(Index(value = ["time"], name = "downloads_time_idx"),
        Index(value = ["filename"], name = "downloads_filename_idx")))
class Download() {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
    var time: Long = 0
    var filename: String = ""
    var filepath: String = ""
    var url: String = ""

    @Volatile
    var size: Long = 0
    @Volatile @ColumnInfo(name = "bytes_received")
    var bytesReceived: Long = 0
    @Ignore
    var operationAfterDownload = OperationAfterDownload.NOP

    //non-db fields
    @Ignore
    @Volatile
    var cancelled: Boolean = false
    /** Lumen: paused by the user (the file and the missing parts are kept, see LumenDownloadState). */
    @Ignore
    @Volatile
    var paused: Boolean = false
    /** Lumen: the server allows continuing (byte ranges), so pausing doesn't start over. */
    @Ignore
    @Volatile
    var canResume: Boolean = false
    /** Lumen: a step besides downloading, shown in the list ("copying to the USB drive 40%"). */
    @Ignore
    @Volatile
    var phase: String? = null
    /** Lumen: waiting in the queue for a free slot (Settings → Downloads at once). */
    @Ignore
    @Volatile
    var queued: Boolean = false
    /** Lumen: connections downloading right now. */
    @Ignore
    @Volatile
    var connections: Int = 0
    @Ignore
    var isDateHeader = false//user for displaying date headers inside list view
    @Ignore
    var mimeType: String? = null
    @Ignore
    var referer: String? = null
    @Ignore
    var userAgentString: String? = null
    @Ignore
    var base64BlobData: String? = null
    @Ignore
    var stream: InputStream? = null
    /** Wisp: the private session's cookies for the file's site (the download runs in the main process,
     *  which can't see the private session's cookie jar). */
    @Ignore
    var cookies: String? = null
    /** Wisp: asked for by private mode: never read the main process's cookie jar for it. */
    @Ignore
    var fromPrivateMode: Boolean = false

    enum class OperationAfterDownload {
        NOP, INSTALL
    }

    constructor(url: String, filename: String, filepath: String?, operationAfterDownload: OperationAfterDownload,
                mimeType: String?, referer: String?, userAgentString: String?, base64BlobData: String?,
                stream: InputStream?, size: Long = 0L) : this() {
        this.url = url
        this.filename = filename
        this.filepath = filepath ?: ""
        this.operationAfterDownload = operationAfterDownload
        this.mimeType = mimeType
        this.referer = referer
        this.userAgentString = userAgentString
        this.base64BlobData = base64BlobData
        this.stream = stream
        this.size = size
    }

    companion object {
        val BROKEN_MARK: Long = -2
        val CANCELLED_MARK: Long = -3

        fun createDateHeaderInfo(time: Long): Download {
            val download = Download()
            download.time = time
            download.isDateHeader = true
            return download
        }
    }
}
