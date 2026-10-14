package com.rendyhd.vicu.ui.components.shared

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry

actual suspend fun Clipboard.copyPlainText(label: String, text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
}
