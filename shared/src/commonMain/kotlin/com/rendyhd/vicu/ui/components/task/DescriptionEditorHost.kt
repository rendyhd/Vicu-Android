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
