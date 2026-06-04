package com.rendyhd.vicu.ui.components.shared

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the FAB should sit at the bottom-start (left) instead of bottom-end.
 * Provided once at the app root from the user's preference; read by each list Scaffold.
 */
val LocalFabAlignStart = compositionLocalOf { false }
