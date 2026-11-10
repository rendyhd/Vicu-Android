package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Compose on iOS gives a text field's paste no receiver: pasted text goes in as typed. */
@Composable
actual fun Modifier.markdownPasteReceiver(onPlainText: (String) -> Boolean): Modifier = this
