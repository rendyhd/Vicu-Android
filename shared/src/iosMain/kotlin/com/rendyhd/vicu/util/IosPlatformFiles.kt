package com.rendyhd.vicu.util

import com.rendyhd.vicu.ui.topViewController
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSinceNow
import platform.Foundation.writeToFile
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSInteger
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Files on iOS. A picked file is copied into the cache by the picker (iOS hands out temporary
 * copies), so the strings passed around are plain paths. Attachments downloaded to open or share
 * are cached under their own folders and removed after a day, like on Android.
 */
class IosPlatformFiles : PlatformFiles {

    override fun getDisplayName(uriString: String): String? =
        pathOf(uriString).substringAfterLast('/').takeIf { it.isNotBlank() }

    override fun offlineAttachmentsDirectory(): String =
        IosPaths.excludedSubdirectory(IosPaths.applicationSupport, OFFLINE_ATTACHMENTS_DIR)

    override suspend fun openForUpload(uriString: String, maxBytes: Long): UploadOpen = withContext(ioDispatcher) {
        val path = Path(pathOf(uriString))
        val name = getDisplayName(uriString) ?: "file"
        val size = SystemFileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size
            ?: return@withContext UploadOpen.Unreadable
        if (size > maxBytes) return@withContext UploadOpen.TooLarge(name, size)
        UploadOpen.Ready(
            UploadSource(
                fileName = name,
                size = size,
                openChannel = { readChannel(path) },
                release = { if (isStaged(path.toString())) deleteRecursively(path.toString().substringBeforeLast('/')) },
            ),
        )
    }

    override suspend fun saveToCache(attachmentId: Long, fileName: String, content: ByteReadChannel): String =
        withContext(ioDispatcher) {
            val root = IosPaths.subdirectory(IosPaths.caches, ATTACHMENT_DIR)
            pruneOld(root)
            val dir = IosPaths.subdirectory(root, attachmentId.toString())
            val target = "$dir/${safeFileName(fileName)}"
            val partial = "$target.part"
            try {
                SystemFileSystem.sink(Path(partial)).buffered().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = content.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read > 0) sink.write(buffer, 0, read)
                    }
                }
                if (SystemFileSystem.exists(Path(target))) SystemFileSystem.delete(Path(target))
                SystemFileSystem.atomicMove(Path(partial), Path(target))
            } catch (e: Throwable) {
                SystemFileSystem.delete(Path(partial), mustExist = false)
                throw e
            }
            target
        }

    override fun openFile(path: String, mimeType: String?): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return "The file is no longer available"
        val presenter = topViewController() ?: return "The file cannot be shown right now"
        dispatch_async(dispatch_get_main_queue()) {
            val source = PreviewSource(NSURL.fileURLWithPath(path))
            val preview = QLPreviewController()
            // QLPreviewController holds its data source weakly; one preview is shown at a time.
            currentPreview = source
            preview.dataSource = source
            presenter.presentViewController(preview, animated = true) {}
        }
        return null
    }

    override fun shareFile(path: String, mimeType: String?): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return "The file is no longer available"
        val presenter = topViewController() ?: return "The file cannot be shared right now"
        dispatch_async(dispatch_get_main_queue()) {
            val sheet = UIActivityViewController(listOf(NSURL.fileURLWithPath(path)), null)
            // On iPad the sheet is a popover and needs an anchor.
            sheet.popoverPresentationController?.let { popover ->
                popover.sourceView = presenter.view
                presenter.view.bounds.useContents {
                    popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0)
                }
            }
            presenter.presentViewController(sheet, animated = true, completion = null)
        }
        return null
    }

    override fun shareText(text: String, subject: String?): String? {
        val presenter = topViewController() ?: return "The text cannot be shared right now"
        dispatch_async(dispatch_get_main_queue()) {
            val sheet = UIActivityViewController(listOf(text), null)
            // On iPad the sheet is a popover and needs an anchor.
            sheet.popoverPresentationController?.let { popover ->
                popover.sourceView = presenter.view
                presenter.view.bounds.useContents {
                    popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0)
                }
            }
            presenter.presentViewController(sheet, animated = true, completion = null)
        }
        return null
    }

    override suspend fun saveFile(path: String, fileName: String, mimeType: String?): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return "The file is no longer available"
        val presenter = topViewController() ?: return "The file cannot be saved right now"
        dispatch_async(dispatch_get_main_queue()) {
            // The Files "Save to" sheet; the cached copy is already named after the attachment.
            val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(path)), asCopy = true)
            presenter.presentViewController(picker, animated = true, completion = null)
        }
        return null
    }

    private fun readChannel(path: Path): ByteReadChannel =
        CoroutineScope(ioDispatcher).writer {
            SystemFileSystem.source(path).buffered().use { source ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = source.readAtMostTo(buffer, 0, buffer.size)
                    if (read < 0) break
                    channel.writeFully(buffer, 0, read)
                }
            }
        }.channel

    private fun pruneOld(root: String) {
        val fm = NSFileManager.defaultManager
        val entries = fm.contentsOfDirectoryAtPath(root, null)?.filterIsInstance<String>().orEmpty()
        for (entry in entries) {
            val full = "$root/$entry"
            val modified = fm.attributesOfItemAtPath(full, null)?.get(NSFileModificationDate) as? NSDate ?: continue
            if (-modified.timeIntervalSinceNow > CACHE_MAX_AGE_SECONDS) deleteRecursively(full)
        }
    }

    companion object {
        private const val ATTACHMENT_DIR = "attachments"
        private const val STAGING_DIR = "upload-staging"
        private const val OFFLINE_ATTACHMENTS_DIR = "offline-attachments"
        private const val CACHE_MAX_AGE_SECONDS = 24 * 60 * 60.0
        private const val COPY_BUFFER_BYTES = 64 * 1024

        private var currentPreview: PreviewSource? = null

        /** A new, empty folder for one picked file; old ones are removed. */
        internal fun newStagingDir(): String {
            val root = IosPaths.subdirectory(IosPaths.caches, STAGING_DIR)
            val fm = NSFileManager.defaultManager
            fm.contentsOfDirectoryAtPath(root, null)?.filterIsInstance<String>()?.forEach { entry ->
                val modified = fm.attributesOfItemAtPath("$root/$entry", null)?.get(NSFileModificationDate) as? NSDate
                if (modified != null && -modified.timeIntervalSinceNow > CACHE_MAX_AGE_SECONDS) deleteRecursively("$root/$entry")
            }
            return IosPaths.subdirectory(root, randomUuid())
        }

        /** Copies the file at [from] into a new staging folder and returns the copy's path. */
        internal fun stage(from: NSURL, preferredName: String? = null): String? {
            val name = safeFileName(preferredName ?: from.lastPathComponent ?: "file")
            val target = "${newStagingDir()}/$name"
            val copied = NSFileManager.defaultManager.copyItemAtURL(from, NSURL.fileURLWithPath(target), null)
            return if (copied) target else null
        }

        /** Writes [data] into a new staging folder and returns the file's path. */
        internal fun stage(data: NSData, name: String): String? {
            val target = "${newStagingDir()}/${safeFileName(name)}"
            return if (data.writeToFile(target, atomically = true)) target else null
        }

        private fun isStaged(path: String): Boolean = "/$STAGING_DIR/" in path

        private fun pathOf(uriString: String): String =
            if (uriString.startsWith("file://")) NSURL.URLWithString(uriString)?.path ?: uriString.removePrefix("file://") else uriString

        internal fun deleteRecursively(path: String) {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }

        internal fun safeFileName(name: String): String =
            name.replace('/', '_').replace(':', '_').trim().ifEmpty { "file" }
    }

    private class PreviewSource(url: NSURL) : NSObject(), QLPreviewControllerDataSourceProtocol {
        private val item = PreviewItem(url)

        override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): NSInteger = 1

        override fun previewController(controller: QLPreviewController, previewItemAtIndex: NSInteger): QLPreviewItemProtocol = item
    }

    private class PreviewItem(private val url: NSURL) : NSObject(), QLPreviewItemProtocol {
        override fun previewItemURL(): NSURL = url

        override fun previewItemTitle(): String? = url.lastPathComponent
    }
}
