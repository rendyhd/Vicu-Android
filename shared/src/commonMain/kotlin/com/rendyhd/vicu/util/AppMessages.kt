package com.rendyhd.vicu.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A message for the app-wide snackbar. When [actionLabel] and [onAction] are set the snackbar
 * shows that action (for example "Undo") and calls [onAction] if the user taps it.
 */
class AppMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * App-wide one-shot messages for outcomes nobody is looking at when they arrive, such as an
 * autosave that fails after the editor was closed. The app scaffold shows them in a snackbar
 * that sits above every screen, including the full-screen task editor.
 *
 * Messages posted while nothing is collecting wait in a small buffer (the oldest is dropped
 * when it is full), so a failure that happens while the app is in the background is still
 * shown when the user comes back.
 */
class AppMessages {
    private val channel = Channel<AppMessage>(capacity = BUFFER_SIZE, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Single-consumer stream: collect it from exactly one place (the app scaffold). */
    val messages: Flow<AppMessage> = channel.receiveAsFlow()

    fun post(message: String) {
        if (message.isNotBlank()) channel.trySend(AppMessage(message))
    }

    /** Posts [message] with an action the user can tap (an Undo, for example). */
    fun post(message: String, actionLabel: String, onAction: () -> Unit) {
        if (message.isNotBlank()) channel.trySend(AppMessage(message, actionLabel, onAction))
    }

    private companion object {
        const val BUFFER_SIZE = 16
    }
}
