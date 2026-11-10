package com.rendyhd.vicu.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.markdownPasteReceiver(onPlainText: (String) -> Boolean): Modifier {
    val onPlainTextState = rememberUpdatedState(onPlainText)
    val listener = remember {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (transferableContent.source != TransferableContent.Source.Clipboard) return transferableContent
                if (!transferableContent.hasMediaType(MediaType.Text)) return transferableContent
                val clip = transferableContent.clipEntry.clipData
                val items = (0 until clip.itemCount).map(clip::getItemAt)
                if (items.isEmpty() || items.any { it.htmlText != null || it.uri != null }) return transferableContent
                val text = items.mapNotNull { it.text?.toString() }.joinToString("\n")
                return if (text.isNotEmpty() && onPlainTextState.value(text)) null else transferableContent
            }
        }
    }
    return this.contentReceiver(listener)
}
