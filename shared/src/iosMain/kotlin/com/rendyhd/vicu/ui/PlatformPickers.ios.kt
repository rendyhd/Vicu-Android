package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.rendyhd.vicu.util.IosPaths
import com.rendyhd.vicu.util.IosPlatformFiles
import com.rendyhd.vicu.util.Logger
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationAssetRepresentationModeCompatible
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeCalendarEvent
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// The pickers hand out temporary copies, so every picked file is copied into the app before the
// callback runs: attachments into a staging folder in Caches (removed once uploaded, or after a
// day), the completion sound into Application Support (kept). The callback gets a plain path.
// The pickers hold their delegates weakly, so each delegate is remembered by the composable.

/** Photos, through PHPicker: no photo library permission is needed. HEIC comes back as JPEG. */
@Composable
actual fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onImagePicked)
    val delegate = remember { ImagePickerDelegate(callback) }
    return {
        val configuration = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
            preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCompatible
        }
        val picker = PHPickerViewController(configuration = configuration)
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

/** Any file, through the Files picker. */
@Composable
actual fun rememberFilePicker(onFilePicked: (String) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onFilePicked)
    val delegate = remember { DocumentPickerDelegate(callback) { url -> IosPlatformFiles.stage(url) } }
    return { presentDocumentPicker(listOf(UTTypeItem), delegate) }
}

/** An audio file, copied into the app so it keeps working after the picker's copy is gone. */
@Composable
actual fun rememberCompletionSoundPicker(onSoundPicked: (String) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onSoundPicked)
    val delegate = remember { DocumentPickerDelegate(callback, ::keepSound) }
    return { presentDocumentPicker(listOf(UTTypeAudio), delegate) }
}

/** A calendar file (`.ics` is a calendar event type), read straight from the picker's copy. */
@Composable
actual fun rememberCalendarFilePicker(onPicked: (String?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val delegate = remember { CalendarFileDelegate(callback) }
    return { presentCalendarPicker(delegate) }
}

private fun presentCalendarPicker(delegate: CalendarFileDelegate) {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeCalendarEvent), asCopy = true)
    picker.allowsMultipleSelection = false
    picker.delegate = delegate
    topViewController()?.presentViewController(picker, animated = true, completion = null)
}

/** The text of the calendar file at [url], or null when it is too large or does not read. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun readCalendarFile(url: NSURL): String? {
    val path = url.path ?: return null
    val size = (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    if (size > MAX_CALENDAR_FILE_BYTES) return null
    return NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
}

private fun presentDocumentPicker(types: List<*>, delegate: DocumentPickerDelegate) {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
    picker.allowsMultipleSelection = false
    picker.delegate = delegate
    topViewController()?.presentViewController(picker, animated = true, completion = null)
}

/** Replaces the kept completion sound with the file at [url]; returns the kept copy's path. */
private fun keepSound(url: NSURL): String? {
    val dir = IosPaths.subdirectory(IosPaths.applicationSupport, SOUND_DIR)
    val fm = NSFileManager.defaultManager
    fm.contentsOfDirectoryAtPath(dir, null)?.filterIsInstance<String>()?.forEach { fm.removeItemAtPath("$dir/$it", null) }
    val target = "$dir/${IosPlatformFiles.safeFileName(url.lastPathComponent ?: "sound")}"
    return if (fm.copyItemAtURL(url, NSURL.fileURLWithPath(target), null)) target else null
}

private const val SOUND_DIR = "completion-sound"
private const val TAG = "Pickers"

private class DocumentPickerDelegate(
    private val callback: State<(String) -> Unit>,
    private val keep: (NSURL) -> String?,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val path = keep(url)
        // The picker's copy lives in the app's tmp folder; it is not needed any more.
        NSFileManager.defaultManager.removeItemAtURL(url, null)
        if (path != null) callback.value(path) else Logger.w(TAG, "Could not keep the picked file")
    }
}

private class CalendarFileDelegate(
    private val callback: State<(String?) -> Unit>,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val text = readCalendarFile(url)
        // The picker's copy lives in the app's tmp folder; the import keeps nothing of the file.
        NSFileManager.defaultManager.removeItemAtURL(url, null)
        callback.value(text)
    }
}

private class ImagePickerDelegate(
    private val callback: State<(String) -> Unit>,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return
        val baseName = provider.suggestedName?.takeIf { it.isNotBlank() } ?: "Photo"
        // The file only exists during the completion handler, which runs on a background queue.
        provider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, error ->
            val path = url?.let { picked ->
                val extension = picked.pathExtension?.takeIf { it.isNotBlank() } ?: "jpg"
                IosPlatformFiles.stage(picked, "$baseName.$extension")
            }
            if (path == null) {
                Logger.w(TAG, "Could not load the picked photo: ${error?.localizedDescription}")
                return@loadFileRepresentationForTypeIdentifier
            }
            dispatch_async(dispatch_get_main_queue()) { callback.value(path) }
        }
    }
}
