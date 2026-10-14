package com.rendyhd.vicu.widget

import android.content.Context
import java.io.File

/**
 * Where the home-screen widgets keep their state. The state holds task titles, and it is tied to
 * widget ids that mean nothing on another device, so it belongs in the no-backup folder: cloud
 * backup and device transfer do not carry it. Files an earlier version wrote to the files folder
 * are moved over the first time each one is asked for.
 */
internal object WidgetStateFiles {

    private const val FOLDER = "datastore"
    private const val EXTENSION = ".preferences_pb"

    /** The state file called [name] (the data store's name, without the extension). */
    fun locate(context: Context, name: String): File =
        locate(context.filesDir, context.noBackupFilesDir, name)

    // Glance asks for the data store and for the location on whatever thread it likes.
    @Synchronized
    internal fun locate(filesDir: File, noBackupDir: File, name: String): File {
        val fileName = "$name$EXTENSION"
        val target = File(File(noBackupDir, FOLDER), fileName)
        target.parentFile?.mkdirs()

        val old = File(File(filesDir, FOLDER), fileName)
        if (old.exists()) {
            // What is already in the new place is newer: it is what the widget has been using.
            if (!target.exists()) moveOver(old, target)
            old.delete()
        }
        File(old.path + ".tmp").delete()
        return target
    }

    private fun moveOver(from: File, to: File) {
        if (from.renameTo(to)) return
        runCatching { from.copyTo(to, overwrite = true) }
    }
}
