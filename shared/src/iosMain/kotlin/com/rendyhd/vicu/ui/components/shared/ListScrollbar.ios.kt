package com.rendyhd.vicu.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

/** Nothing to do: iOS goes back from the left edge only, and the thumb is at the right. */
@Composable
internal actual fun rememberEdgeGestureExclusion(): (Rect?) -> Unit = { }
