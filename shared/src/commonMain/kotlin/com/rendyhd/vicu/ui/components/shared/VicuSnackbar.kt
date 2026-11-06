package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal data class SnackbarColors(val container: Color, val content: Color, val action: Color)

/**
 * Material's inverse roles make the bar nearly white in a dark theme, the brightest thing on the
 * screen at night. There it is the highest container instead, which still stands off the page, a
 * sheet and pure black. A light theme keeps the dark bar.
 */
internal fun ColorScheme.snackbarColors(): SnackbarColors =
    if (background.luminance() < 0.5f) {
        SnackbarColors(container = surfaceContainerHighest, content = onSurface, action = primary)
    } else {
        SnackbarColors(container = inverseSurface, content = inverseOnSurface, action = inversePrimary)
    }

/** The app's snackbar; use it instead of Material's. */
@Composable
internal fun VicuSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme.snackbarColors()
    Snackbar(
        snackbarData = data,
        modifier = modifier,
        containerColor = colors.container,
        contentColor = colors.content,
        actionColor = colors.action,
        actionContentColor = colors.action,
        dismissActionContentColor = colors.content,
    )
}

@Composable
internal fun VicuSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { VicuSnackbar(it) }
}
