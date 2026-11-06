package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.ui.Modifier

/** Nothing to do: iOS goes back from the left edge only, and the Plan tab is at the right. */
internal actual fun Modifier.excludeSystemEdgeGestures(): Modifier = this
