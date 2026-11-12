package com.rendyhd.vicu.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.rendyhd.vicu.data.local.PlatformContext
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlatformFiles(private val platformContext: PlatformContext) : PlatformFiles {
    override fun getDisplayName(uriString: String): String? {
        return try {
            FileUtils.getDisplayName(platformContext.context, uriString.toUri())
        } catch (_: Exception) {
            null
        }
    }

    override fun offlineAttachmentsDirectory(): String =
        java.io.File(platformContext.context.noBackupFilesDir, OFFLINE_ATTACHMENTS_DIR).apply { mkdirs() }.absolutePath

    override suspend fun openForUpload(uriString: String, maxBytes: Long): UploadOpen =
        withContext(Dispatchers.IO) {
            try {
                FileUtils.openForUpload(platformContext.context, uriString.toUri(), maxBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UploadOpen.Unreadable
            }
        }

    override suspend fun saveToCache(attachmentId: Long, fileName: String, content: ByteReadChannel): String =
        withContext(Dispatchers.IO) {
            FileUtils.saveAttachmentToCache(platformContext.context, attachmentId, fileName, content)
        }

    override fun openFile(path: String, mimeType: String?): String? =
        FileUtils.openFile(platformContext.context, path, mimeType)

    override fun shareFile(path: String, mimeType: String?): String? =
        FileUtils.shareFile(platformContext.context, path, mimeType)

    override fun shareText(text: String, subject: String?): String? {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .apply { if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject) }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            platformContext.context.startActivity(chooser)
            null
        } catch (_: ActivityNotFoundException) {
            "No app on this device can receive text"
        }
    }

    override suspend fun saveFile(path: String, fileName: String, mimeType: String?): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            withContext(Dispatchers.IO) { FileUtils.saveToDownloads(platformContext.context, path, fileName, mimeType) }
        } else {
            // Before Android 10 writing to Downloads needs a storage permission; the share sheet
            // offers the places to save it instead.
            FileUtils.shareFile(platformContext.context, path, mimeType)
        }

    private companion object {
        const val OFFLINE_ATTACHMENTS_DIR = "offline-attachments"
    }
}
