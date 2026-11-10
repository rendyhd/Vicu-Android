package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Offers text pasted into the text fields inside this modifier to [onPlainText] when the clipboard
 * holds no HTML version of it (description format, "Markdown input"). It returns true when it took
 * the paste; otherwise the text field pastes the text as usual.
 */
@Composable
expect fun Modifier.markdownPasteReceiver(onPlainText: (String) -> Boolean): Modifier
