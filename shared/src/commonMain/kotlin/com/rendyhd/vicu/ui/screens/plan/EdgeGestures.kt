package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.ui.Modifier

/**
 * Keeps the system's edge swipe off this element. On Android's gesture navigation a swipe in from the
 * edge is Back, which would otherwise take a swipe that starts on the Plan drawer's tab. iOS only has
 * a Back swipe from the leading edge, so there it changes nothing.
 */
internal expect fun Modifier.excludeSystemEdgeGestures(): Modifier
