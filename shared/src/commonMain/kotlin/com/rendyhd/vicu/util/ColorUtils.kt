package com.rendyhd.vicu.util

import androidx.compose.ui.graphics.Color

/** The colours the project and label dialogs offer, as "#rrggbb". */
val PRESET_COLORS = listOf(
    "#e8384f", "#fd612c", "#fd9a00", "#eec300",
    "#a4cf30", "#37c5ab", "#20aaea", "#4186e0",
    "#7a6ff0", "#aa62e3",
)

/**
 * The ARGB value of a hex colour: "RRGGBB" (as Vikunja stores it), "AARRGGBB", either with a
 * leading `#`. Null for anything else; callers apply their own fallback colour.
 *
 * Plain Kotlin, so it is the same on every platform and testable. The one parser for the
 * project, label and picker UI: the copies built on android.graphics.Color.parseColor are gone.
 */
fun parseHexArgb(hex: String): Int? {
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = digits.toLong(16)
    return if (digits.length == 6) (0xFF000000L or value).toInt() else value.toInt()
}

/** [parseHexArgb] as a Compose [Color]; null on blank or invalid input. */
fun parseHexColor(hex: String): Color? = parseHexArgb(hex)?.let { Color(it) }
