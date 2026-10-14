package com.rendyhd.vicu.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

object FileUtils {

    private const val ATTACHMENT_DIR = "attachments"
    private const val STAGING_DIR = "upload-staging"
    private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

    fun getDisplayName(context: Context, uri: Uri): String? = getFileName(context, uri)

    /**
     * Opens [uri] for streaming into an upload. Blocking: call it off the main thread.
     *
     * The size comes from the content provider, so a file over [maxBytes] is turned down without
     * reading a byte of it. A provider that does not say how big the file is gets it copied into
     * the cache first (counting, and stopping at [maxBytes]) so the size is known and enforced
     * before the request starts; that copy is removed when the source is closed.
     */
    fun openForUpload(context: Context, uri: Uri, maxBytes: Long): UploadOpen {
        val resolver = context.contentResolver
        val name = getFileName(context, uri) ?: "file"
        val size = querySize(context, uri)
        if (size != null) {
            if (size > maxBytes) return UploadOpen.TooLarge(name, size)
            // Find out now, not halfway through a request, that the file cannot be opened.
            (resolver.openInputStream(uri) ?: return UploadOpen.Unreadable).close()
            return UploadOpen.Ready(
                UploadSource(
                    fileName = name,
                    size = size,
                    openChannel = {
                        val stream = resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
                        stream.toByteReadChannel(context = Dispatchers.IO)
                    },
                ),
            )
        }

        val staged = File(File(context.cacheDir, STAGING_DIR).apply { mkdirs() }, UUID.randomUUID().toString())
        val copied = try {
            (resolver.openInputStream(uri) ?: return UploadOpen.Unreadable).use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            staged.delete()
                            return UploadOpen.TooLarge(name, total)
                        }
                        output.write(buffer, 0, read)
                    }
                    total
                }
            }
        } catch (e: Exception) {
            staged.delete()
            throw e
        }
        return UploadOpen.Ready(
            UploadSource(
                fileName = name,
                size = copied,
                openChannel = { staged.inputStream().toByteReadChannel(context = Dispatchers.IO) },
                release = { staged.delete() },
            ),
        )
    }

    /**
     * Streams [content] into a new file in the cache and returns its path. The file appears under
     * its final name only when it is complete. Old cached attachments are deleted first.
     */
    suspend fun saveAttachmentToCache(
        context: Context,
        attachmentId: Long,
        fileName: String,
        content: ByteReadChannel,
    ): String {
        val root = File(context.cacheDir, ATTACHMENT_DIR).apply { mkdirs() }
        pruneOld(root)
        val dir = File(root, attachmentId.toString()).apply { mkdirs() }
        val target = File(dir, safeFileName(fileName))
        val partial = File(dir, "${target.name}.part")
        try {
            partial.outputStream().use { content.copyTo(it) }
            target.delete()
            if (!partial.renameTo(target)) throw java.io.IOException("Could not save ${target.name}")
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
        return target.absolutePath
    }

    /** Opens [path] (a file in the attachment cache) in another app. */
    fun openFile(context: Context, path: String, mimeType: String?): String? {
        val uri = uriFor(context, path) ?: return "The file is no longer available"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            "No app on this device can open this kind of file. Try sharing it instead."
        }
    }

    /** Offers [path] (a file in the attachment cache) to other apps. */
    fun shareFile(context: Context, path: String, mimeType: String?): String? {
        val uri = uriFor(context, path) ?: return "The file is no longer available"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri(null, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(chooser)
            null
        } catch (_: ActivityNotFoundException) {
            "No app on this device can receive this file"
        }
    }

    private fun uriFor(context: Context, path: String): Uri? {
        val file = File(path)
        if (!file.isFile) return null
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun pruneOld(root: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        root.listFiles()?.forEach { entry ->
            if (entry.lastModified() < cutoff) entry.deleteRecursively()
        }
    }

    /** The size in bytes the provider reports, or null when it does not say (or says 0). */
    private fun querySize(context: Context, uri: Uri): Long? {
        var size: Long? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    size = cursor.getLong(index)
                }
            }
        } else if (uri.scheme == "file") {
            size = uri.path?.let { File(it).length() }
        }
        return size?.takeIf { it > 0 }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private const val COPY_BUFFER_BYTES = 64 * 1024
}
