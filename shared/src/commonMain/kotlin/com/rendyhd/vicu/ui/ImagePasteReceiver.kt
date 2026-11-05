package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun Modifier.imagePasteReceiver(onImagePasted: (String) -> Unit): Modifier

/**
 * The system clipboard's image, for a screen to offer it. A text field on iOS has no way to take an
 * image and on Android it takes one only from a long press or the keyboard, so a composer shows its
 * own control while there is something to paste.
 */
interface ClipboardImage {
    /**
     * Null without an image, else a stamp that changes when the clipboard does. This only asks what
     * kind of content is there, which shows no paste notice on either platform, so it can be asked
     * often.
     */
    fun stamp(): Long?

    /**
     * The image as an address the upload path takes, or null when it is gone or the person refused.
     * This is the read itself: Android says so in a toast and iOS asks first, so call it only when
     * the person asked to paste.
     */
    fun read(): String?
}

@Composable
expect fun rememberClipboardImage(): ClipboardImage
