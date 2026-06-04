package com.rendyhd.vicu.util

import androidx.compose.ui.graphics.Color

/**
 * Parses a hex color string (with or without a leading `#`).
 * Returns null on blank or invalid input — callers apply their own fallback color.
 *
 * Single crash-safe implementation replacing the try/catch copies previously duplicated
 * across the task/detail/review/settings/label screens (one of which was unguarded).
 */
fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (_: Exception) {
        null
    }
}
