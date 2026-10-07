package com.rendyhd.vicu.ui.components.shared

import androidx.compose.ui.platform.Clipboard

/**
 * Puts [text] on the clipboard as plain text. [label] is the user-visible name some platforms
 * keep with the entry. Replaces LocalClipboardManager.setText, which is deprecated: the
 * Clipboard API is suspending and its entries are platform types, hence the expect/actual.
 */
expect suspend fun Clipboard.copyPlainText(label: String, text: String)
