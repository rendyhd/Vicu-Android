package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.rendyhd.vicu.util.IosPlatformFiles
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard

@Composable
actual fun Modifier.imagePasteReceiver(onImagePasted: (String) -> Unit): Modifier {
    return this
}

/** What [IosClipboardImage] needs from the pasteboard. A test binary has none: it is not an app. */
internal interface ImageBoard {
    val hasImages: Boolean
    val changeCount: Long

    fun data(type: String): NSData?

    /** Whatever image it holds, drawn to a PNG. */
    fun drawnPng(): NSData?
}

private object SystemImageBoard : ImageBoard {
    private val board get() = UIPasteboard.generalPasteboard
    override val hasImages get() = board.hasImages
    override val changeCount get() = board.changeCount

    override fun data(type: String) = board.dataForPasteboardType(type)

    override fun drawnPng() = board.image?.let { UIImagePNGRepresentation(it) }
}

internal class IosClipboardImage(private val board: ImageBoard = SystemImageBoard) : ClipboardImage {
    override fun stamp(): Long? = if (board.hasImages) board.changeCount else null

    override fun read(): String? {
        // As it came when every browser can show it, which a screenshot can; anything else as a PNG.
        val png = board.data(PNG_TYPE)
        val jpeg = if (png == null) board.data(JPEG_TYPE) else null
        val data = png ?: jpeg ?: board.drawnPng() ?: return null
        val millis = (NSDate().timeIntervalSince1970 * 1000).toLong()
        return IosPlatformFiles.stage(data, "pasted-$millis.${if (jpeg != null) "jpg" else "png"}")
    }

    private companion object {
        const val PNG_TYPE = "public.png"
        const val JPEG_TYPE = "public.jpeg"
    }
}

@Composable
actual fun rememberClipboardImage(): ClipboardImage = remember { IosClipboardImage() }
