package com.phlox.tvwebbrowser.activity.downloads

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter

import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.Utils

import java.util.ArrayList

import de.halfbit.pinnedsection.PinnedSectionListView

/**
 * Created by PDT on 24.01.2017.
 */

class DownloadListAdapter(private val downloadsActivity: DownloadsActivity) : BaseAdapter(), PinnedSectionListView.PinnedSectionListAdapter {
    private val downloads = ArrayList<Download>()
    private var lastHeaderDate: Long = -1
    var realCount: Long = 0
        private set

    fun addItems(items: List<Download>) {
        if (items.isEmpty()) {
            return
        }
        for (download in items) {
            if (download.id != 0L && contains(download.id)) continue //already shown (a running download)
            if (!Utils.isSameDate(download.time, lastHeaderDate)) {
                lastHeaderDate = download.time
                this.downloads.add(Download.createDateHeaderInfo(download.time))
            }
            this.downloads.add(download)
            realCount++
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return downloads.size
    }

    override fun getItem(i: Int): Any {
        return downloads[i]
    }

    override fun getItemId(i: Int): Long {
        return i.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val hiv = if (convertView != null) {
            convertView as DownloadListItemView
        } else {
            DownloadListItemView(downloadsActivity, getItemViewType(position))
        }
        hiv.download = downloads[position]
        return hiv
    }

    override fun getViewTypeCount(): Int {
        return 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (downloads[position].isDateHeader) VIEW_TYPE_HEADER else VIEW_TYPE_DOWNLOAD_ITEM
    }

    override fun isItemViewTypePinned(viewType: Int): Boolean {
        return viewType == VIEW_TYPE_HEADER
    }

    fun contains(id: Long): Boolean = downloads.any { !it.isDateHeader && it.id == id }

    /** Lumen: a download started after the list was loaded goes on top. */
    fun addOnTop(download: Download) {
        if (download.id == 0L || contains(download.id)) return
        val first = downloads.firstOrNull()
        if (first != null && first.isDateHeader && Utils.isSameDate(first.time, download.time)) {
            downloads.add(1, download)
        } else {
            downloads.add(0, download)
            downloads.add(0, Download.createDateHeaderInfo(download.time))
            if (lastHeaderDate == -1L) lastHeaderDate = download.time
        }
        realCount++
        notifyDataSetChanged()
    }

    fun remove(download: Download) {
        downloads.remove(download)
        notifyDataSetChanged()
    }

    /** Removes the download with this id (and its date heading when nothing else is under it). */
    fun removeById(id: Long) {
        val i = downloads.indexOfFirst { !it.isDateHeader && it.id == id }
        if (i < 0) return
        downloads.removeAt(i)
        realCount--
        val headerAbove = i > 0 && downloads[i - 1].isDateHeader
        val nothingBelow = i >= downloads.size || downloads[i].isDateHeader
        if (headerAbove && nothingBelow) downloads.removeAt(i - 1)
        notifyDataSetChanged()
    }

    fun isEmptyList(): Boolean = downloads.none { !it.isDateHeader }

    companion object {
        val VIEW_TYPE_DOWNLOAD_ITEM = 0
        val VIEW_TYPE_HEADER = 1
    }
}
