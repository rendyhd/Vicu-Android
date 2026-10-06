package com.rendyhd.vicu.util

import android.net.Uri
import com.rendyhd.vicu.data.local.PlatformContext
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlatformFiles(private val platformContext: PlatformContext) : PlatformFiles {
    override fun getDisplayName(uriString: String): String? {
        return try {
            FileUtils.getDisplayName(platformContext.context, Uri.parse(uriString))
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun openForUpload(uriString: String, maxBytes: Long): UploadOpen =
        withContext(Dispatchers.IO) {
            try {
                FileUtils.openForUpload(platformContext.context, Uri.parse(uriString), maxBytes)
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
}
