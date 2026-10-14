package com.rendyhd.vicu.util

import io.ktor.utils.io.ByteReadChannel

class IosPlatformFiles : PlatformFiles {
    override fun getDisplayName(uriString: String): String? {
        // TODO: Implement display name extraction using iOS APIs
        return null
    }

    override suspend fun openForUpload(uriString: String, maxBytes: Long): UploadOpen {
        // TODO: Implement file streaming using iOS APIs (NSURL / NSFileHandle)
        return UploadOpen.Unreadable
    }

    override suspend fun saveToCache(attachmentId: Long, fileName: String, content: ByteReadChannel): String {
        // TODO: Implement with NSFileManager once attachments are supported on iOS
        throw UnsupportedOperationException("Saving attachments is not supported on iOS yet")
    }

    override fun openFile(path: String, mimeType: String?): String? = "Opening files is not supported on iOS yet"

    override fun shareFile(path: String, mimeType: String?): String? = "Sharing files is not supported on iOS yet"
}
