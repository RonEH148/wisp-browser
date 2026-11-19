package com.phlox.tvwebbrowser.sofa

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import com.phlox.tvwebbrowser.AppContext
import java.io.File

/**
 * Lumen's own folder browser for the download folder. Android TVs usually have no system
 * folder picker (the request ends with "no app can do this"), so this lists internal storage,
 * SD cards and USB drives directly and works with the remote.
 */
class LumenFolderPicker(private val activity: Activity, private val onPicked: () -> Unit) {
    companion object {
        const val REQUEST_CODE_STORAGE = 10031

        /** Android 11+: may write to every folder (not only Download/Documents and the like). */
        fun hasAllFilesAccess(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

        fun hasLegacyWritePermission(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private class Root(val label: String, val dir: File)

    fun show() {
        if (!hasLegacyWritePermission(activity)) {
            //Android 10 and older: ask for storage access first, then the user taps "Choose folder" again
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_CODE_STORAGE)
            return
        }
        val start = LumenDownloads.customFolderFile()
        if (start != null) showDir(start) else showRoots()
    }

    private fun roots(): List<Root> {
        val list = ArrayList<Root>()
        val seen = HashSet<String>()
        fun add(label: String, dir: File?) {
            if (dir == null || !dir.isDirectory) return
            if (seen.add(dir.absolutePath)) list.add(Root(label, dir))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = activity.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                if (v.state != Environment.MEDIA_MOUNTED) continue
                add(v.getDescription(activity) ?: "Storage", v.directory)
            }
        }
        @Suppress("DEPRECATION")
        add("Internal storage", Environment.getExternalStorageDirectory())
        //SD cards and USB drives on older Android: /storage/<id>/Android/data/<pkg>/files → /storage/<id>
        for (f in activity.getExternalFilesDirs(null)) {
            val path = f?.absolutePath ?: continue
            val i = path.indexOf("/Android/data/")
            if (i > 0) {
                val root = File(path.substring(0, i))
                add(LumenDownloads.pathLabel(root.absolutePath), root)
            }
        }
        //anything else mounted under /storage that the app can see
        File("/storage").listFiles()?.forEach {
            if (it.name != "emulated" && it.name != "self" && it.isDirectory && it.canRead()) {
                add(LumenDownloads.pathLabel(it.absolutePath), it)
            }
        }
        return list
    }

    private fun showRoots() {
        val roots = roots()
        if (roots.isEmpty()) {
            Toast.makeText(activity, "No storage found", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Choose the download folder")
            .setItems(roots.map { "💾  " + it.label }.toTypedArray()) { _, which -> showDir(roots[which].dir) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isRoot(dir: File): Boolean = roots().any { it.dir.absolutePath == dir.absolutePath }

    private fun showDir(dir: File) {
        val subdirs = (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") && !(it.name == "Android" && isRoot(dir)) }
            .sortedBy { it.name.lowercase() }
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add("✓  Save downloads here" to { choose(dir) })
        actions.add("⬆  Up" to {
            if (isRoot(dir)) showRoots() else dir.parentFile?.let { showDir(it) } ?: showRoots()
        })
        actions.add("＋  New folder" to { newFolder(dir) })
        if (!hasAllFilesAccess()) {
            actions.add("🔓  Allow saving to any folder…" to { requestAllFilesAccess() })
        }
        for (d in subdirs) actions.add("📁  " + d.name to { showDir(d) })

        AlertDialog.Builder(activity)
            .setTitle(LumenDownloads.pathLabel(dir.absolutePath))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newFolder(parent: File) {
        val edit = EditText(activity)
        edit.setSingleLine()
        edit.hint = "Folder name"
        AlertDialog.Builder(activity)
            .setTitle("New folder")
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = edit.text.toString().trim().replace("/", "")
                if (name.isEmpty()) { showDir(parent); return@setPositiveButton }
                val dir = File(parent, name)
                if (dir.isDirectory || dir.mkdirs()) showDir(dir) else {
                    AlertDialog.Builder(activity)
                        .setMessage("The folder couldn't be created here." + permissionHint())
                        .setPositiveButton(android.R.string.ok) { _, _ -> showDir(parent) }
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showDir(parent) }
            .show()
    }

    private fun permissionHint(): String = if (hasAllFilesAccess()) "" else
        "\n\nWithout \"Allow saving to any folder\", Android lets apps save only into folders such as " +
                "Download and Documents (and their subfolders)."

    private fun choose(dir: File) {
        val problem = LumenDownloads.testWritable(dir)
        if (problem != null) {
            AlertDialog.Builder(activity)
                .setTitle("Can't save in this folder")
                .setMessage(problem + permissionHint())
                .setPositiveButton(android.R.string.ok) { _, _ -> showDir(dir) }
                .apply {
                    if (!hasAllFilesAccess()) setNeutralButton("Allow any folder") { _, _ -> requestAllFilesAccess() }
                }
                .show()
            return
        }
        AppContext.provideConfig().lumenDownloadFolderUri = dir.absolutePath
        Toast.makeText(activity, "Downloads will be saved to " + LumenDownloads.pathLabel(dir.absolutePath),
            Toast.LENGTH_LONG).show()
        onPicked()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.packageName)),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))
        )
        for (intent in intents) {
            //TVs often have a placeholder that only says "no app can do this": skip those
            val handlers = activity.packageManager.queryIntentActivities(intent, 0)
                .filter { !it.activityInfo.packageName.contains("frameworkpackagestubs") }
            if (handlers.isEmpty()) continue
            try {
                activity.startActivity(intent)
                Toast.makeText(activity, "Turn on \"Allow access to manage all files\" for ${com.phlox.tvwebbrowser.Brand.NAME}, then come back " +
                        "and choose the folder again", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                //try the next screen
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Not available on this device")
            .setMessage("This TV has no settings screen for \"All files access\". You can still save into " +
                    "Download or Documents (or a folder inside them), on internal storage and on USB drives.\n\n" +
                    "With a computer it can be turned on once with:\nadb shell appops set " +
                    activity.packageName + " MANAGE_EXTERNAL_STORAGE allow")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
