package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

internal actual fun Modifier.excludeSystemEdgeGestures(): Modifier = systemGestureExclusion()
