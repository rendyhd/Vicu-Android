package com.rendyhd.vicu.ui

import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.imagePasteReceiver(onImagePasted: (String) -> Unit): Modifier {
    val onImagePastedState = rememberUpdatedState(onImagePasted)
    val pasteListener = remember {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
                return transferableContent.consume { item ->
                    val uri = item.uri
                    if (uri != null) {
                        onImagePastedState.value(uri.toString())
                        true
                    } else false
                }
            }
        }
    }
    return this.contentReceiver(pasteListener)
}

private class AndroidClipboardImage(private val manager: ClipboardManager) : ClipboardImage {
    override fun stamp(): Long? = manager.primaryClipDescription?.takeIf { it.hasMimeType("image/*") }?.timestamp

    override fun read(): String? {
        val clip = manager.primaryClip ?: return null
        return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }?.toString()
    }
}

@Composable
actual fun rememberClipboardImage(): ClipboardImage {
    val context = LocalContext.current
    return remember(context) { AndroidClipboardImage(context.getSystemService(ClipboardManager::class.java)) }
}
