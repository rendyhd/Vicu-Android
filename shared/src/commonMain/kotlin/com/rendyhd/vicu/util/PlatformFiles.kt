package com.rendyhd.vicu.util

import io.ktor.utils.io.ByteReadChannel

/**
 * A picked file opened for streaming into an upload. Nothing is read until [channel] is called,
 * and a request that has to be sent again simply calls it again.
 */
class UploadSource(
    val fileName: String,
    /** The size in bytes, known before the upload starts. */
    val size: Long,
    private val openChannel: () -> ByteReadChannel,
    private val release: () -> Unit = {},
) {
    fun channel(): ByteReadChannel = openChannel()

    /** Frees what [channel] needed (a staged copy of the file, for providers that hide the size). */
    fun close() = release()
}

/** The outcome of opening a picked file for upload. */
sealed interface UploadOpen {
    class Ready(val source: UploadSource) : UploadOpen

    /** The file is bigger than the server accepts. */
    data class TooLarge(val fileName: String, val size: Long) : UploadOpen

    /** The file cannot be read (it was removed, or access to it was revoked). */
    data object Unreadable : UploadOpen
}

interface PlatformFiles {
    fun getDisplayName(uriString: String): String?

    /**
     * Opens the file behind [uriString] for streaming, off the main thread. A file larger than
     * [maxBytes] is not opened: it comes back as [UploadOpen.TooLarge].
     */
    suspend fun openForUpload(uriString: String, maxBytes: Long): UploadOpen

    /**
     * Writes [content] to a new file in the app's cache, off the main thread, and returns its
     * path. The file is named after [fileName] inside a folder of its own, so attachments with
     * the same name do not overwrite each other, and old cached attachments are cleaned up.
     * Throws when the file cannot be written; nothing is left behind in that case.
     */
    suspend fun saveToCache(attachmentId: Long, fileName: String, content: ByteReadChannel): String

    /** Opens the cached file at [path] in another app. Returns a message when that is not possible. */
    fun openFile(path: String, mimeType: String?): String?

    /** Offers the cached file at [path] to other apps. Returns a message when that is not possible. */
    fun shareFile(path: String, mimeType: String?): String?
}
