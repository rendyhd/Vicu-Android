package com.rendyhd.vicu.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** WCAG contrast ratio of two opaque colours (1 to 21). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * [color], moved towards [towards] in steps of a tenth until it reads at [minimum]:1 on every one
 * of [backgrounds] (or [towards] itself is reached). A colour that already does is returned as is.
 */
fun ensureContrast(color: Color, towards: Color, backgrounds: List<Color>, minimum: Double): Color {
    var current = color
    for (step in 0..10) {
        if (backgrounds.all { contrastRatio(current, it) >= minimum }) return current
        current = lerp(color, towards, (step + 1) / 10f)
    }
    return current
}
