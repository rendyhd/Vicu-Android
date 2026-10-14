package com.rendyhd.vicu.ui.components.shared

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.withPlainText

actual suspend fun Clipboard.copyPlainText(label: String, text: String) {
    setClipEntry(ClipEntry.withPlainText(text))
}
