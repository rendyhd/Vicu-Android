package com.rendyhd.vicu.util

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray

/** A file the user picked, as the fake file access sees it. */
class PickedFile(
    val name: String,
    val bytes: ByteArray,
    /** What the content provider reports as the size; may differ from the bytes in a test. */
    val declaredSize: Long = bytes.size.toLong(),
)

/** In-memory [PlatformFiles]: records what was streamed in and out and what was opened or shared. */
class FakePlatformFiles : PlatformFiles {
    val files = mutableMapOf<String, PickedFile>()

    /** How many sources were opened, how many channels were read from them and how many closed. */
    var sourcesOpened = 0
    var channelsOpened = 0
    var sourcesClosed = 0

    val saved = mutableMapOf<Long, ByteArray>()
    var saveFailure: Exception? = null

    val openedPaths = mutableListOf<Pair<String, String?>>()
    val sharedPaths = mutableListOf<Pair<String, String?>>()

    /** What opening or sharing answers: null means it worked. */
    var presentMessage: String? = null

    override fun getDisplayName(uriString: String): String? = files[uriString]?.name

    override suspend fun openForUpload(uriString: String, maxBytes: Long): UploadOpen {
        val file = files[uriString] ?: return UploadOpen.Unreadable
        if (file.declaredSize > maxBytes) return UploadOpen.TooLarge(file.name, file.declaredSize)
        sourcesOpened++
        return UploadOpen.Ready(
            UploadSource(
                fileName = file.name,
                size = file.declaredSize,
                openChannel = {
                    channelsOpened++
                    ByteReadChannel(file.bytes)
                },
                release = { sourcesClosed++ },
            ),
        )
    }

    override suspend fun saveToCache(attachmentId: Long, fileName: String, content: ByteReadChannel): String {
        saveFailure?.let { throw it }
        saved[attachmentId] = content.toByteArray()
        return "/cache/attachments/$attachmentId/${safeFileName(fileName)}"
    }

    override fun openFile(path: String, mimeType: String?): String? {
        openedPaths += path to mimeType
        return presentMessage
    }

    override fun shareFile(path: String, mimeType: String?): String? {
        sharedPaths += path to mimeType
        return presentMessage
    }
}
