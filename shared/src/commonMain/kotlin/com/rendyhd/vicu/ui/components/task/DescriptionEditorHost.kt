package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Clears Cascade's logical focus when a tap lands elsewhere in the host form.
 */
@Composable
internal fun Modifier.clearDescriptionEditorFocusOnHostTap(
    controller: DescriptionEditorController,
): Modifier {
    var hostBounds by remember(controller) { mutableStateOf<Rect?>(null) }
    return this
        .onGloballyPositioned { hostBounds = it.boundsInRoot() }
        .pointerInput(controller) {
            detectTapGestures { offset ->
                val origin = hostBounds?.topLeft ?: return@detectTapGestures
                controller.clearFocusWhenHostTapped(origin + offset)
            }
        }
}

@Stable
internal class DescriptionEditorController {
    private var owner: Any? = null
    private var flushAction: (() -> Unit)? = null
    private var clearFocusAction: (() -> Unit)? = null
    private var editorBounds: Rect? = null
    private var toolbarBounds: Rect? = null

    var isEditorFocused by mutableStateOf(false)
        private set

    /**
     * Set by [requestFocus] and taken by the field once it is composed, so a host can open the
     * notes and put the cursor in them in one tap.
     */
    var isFocusRequested by mutableStateOf(false)
        private set

    /** Focuses the notes, at the end of their text, as soon as the field is there. */
    fun requestFocus() {
        isFocusRequested = true
    }

    fun takeFocusRequest(): Boolean {
        if (!isFocusRequested) return false
        isFocusRequested = false
        return true
    }

    /**
     * The editor's toolbar, for a host that draws it itself (on top of the keyboard) instead of
     * under the editor; null while no field hands it over.
     */
    var hostedToolbar by mutableStateOf<(@Composable (Modifier) -> Unit)?>(null)
        private set

    fun hostToolbar(owner: Any, toolbar: (@Composable (Modifier) -> Unit)?) {
        if (this.owner !== owner) return
        hostedToolbar = toolbar
    }

    fun bind(
        owner: Any,
        flushAction: () -> Unit,
        clearFocusAction: () -> Unit,
        isFocused: Boolean,
    ) {
        if (this.owner !== owner) {
            editorBounds = null
            toolbarBounds = null
        }
        this.owner = owner
        this.flushAction = flushAction
        this.clearFocusAction = clearFocusAction
        isEditorFocused = isFocused
        if (!isFocused) toolbarBounds = null
    }

    fun unbind(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        flushAction = null
        clearFocusAction = null
        editorBounds = null
        toolbarBounds = null
        isEditorFocused = false
        hostedToolbar = null
    }

    fun updateEditorBounds(bounds: Rect?) {
        editorBounds = bounds
    }

    fun updateToolbarBounds(bounds: Rect?) {
        toolbarBounds = bounds
    }

    fun flush() {
        flushAction?.invoke()
    }

    fun clearFocusWhenHostTapped(rootPoint: Offset) {
        if (!isEditorFocused) return
        if (editorBounds?.contains(rootPoint) == true || toolbarBounds?.contains(rootPoint) == true) return
        clearFocusAction?.invoke()
        isEditorFocused = false
    }
}

@Composable
internal fun rememberDescriptionEditorController(): DescriptionEditorController =
    remember { DescriptionEditorController() }
