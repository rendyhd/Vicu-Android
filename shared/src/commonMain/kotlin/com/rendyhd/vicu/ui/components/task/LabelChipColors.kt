package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The label chip colours of the contract (test-fixtures/design-tokens-v1.json `labelChip`,
// docs/design-system-v1.md): the fill is the label colour at 12% over bg.page; the text is the label
// colour kept in hue and saturation (HSL) and moved in lightness, one percent at a time, until it has
// 4.5:1 against that fill. The desktop runs the same steps (src/renderer/lib/label-style.ts); both
// apps pass `labelChip.vectors`. Colours here are 0xRRGGBB ints.

/** bg.page of each theme (tokens `roles`), the surface the tint is computed over. */
private const val BG_PAGE_LIGHT = 0xFFFFFF
private const val BG_PAGE_DARK = 0x1C1C1E

internal const val LABEL_TINT_ALPHA = 0.12
internal const val LABEL_MIN_CONTRAST = 4.5

/** Round half up, the same on every platform (the epsilon makes exact ties round up). */
private fun round8(v: Double): Int = floor(v + 0.5 + 1e-9).toInt()

private fun red(rgb: Int) = (rgb shr 16) and 0xFF
private fun green(rgb: Int) = (rgb shr 8) and 0xFF
private fun blue(rgb: Int) = rgb and 0xFF
private fun rgbOf(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

private fun linear(channel: Int): Double {
    val v = channel / 255.0
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private fun luminance(rgb: Int): Double =
    0.2126 * linear(red(rgb)) + 0.7152 * linear(green(rgb)) + 0.0722 * linear(blue(rgb))

/** WCAG 2.x contrast ratio of two opaque colours. */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private class Hsl(val h: Double, val s: Double, val l: Double)

private fun toHsl(rgb: Int): Hsl {
    val r = red(rgb) / 255.0
    val g = green(rgb) / 255.0
    val b = blue(rgb) / 255.0
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    val d = mx - mn
    if (d == 0.0) return Hsl(0.0, 0.0, l)
    val s = d / (1 - abs(2 * l - 1))
    var h = when (mx) {
        r -> ((g - b) / d) % 6
        g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    } * 60
    if (h < 0) h += 360
    return Hsl(h, s, l)
}

private fun fromHsl(h: Double, s: Double, l: Double): Int {
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0.0)
        h < 120 -> Triple(x, c, 0.0)
        h < 180 -> Triple(0.0, c, x)
        h < 240 -> Triple(0.0, x, c)
        h < 300 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    return rgbOf(round8((r + m) * 255), round8((g + m) * 255), round8((b + m) * 255))
}

private fun bgPage(dark: Boolean) = if (dark) BG_PAGE_DARK else BG_PAGE_LIGHT

/** The fill of a chip over bg.page as the contract defines it (0xRRGGBB, opaque). */
internal fun labelChipTint(labelRgb: Int, dark: Boolean): Int {
    val bg = bgPage(dark)
    fun mix(label: Int, back: Int) = round8(LABEL_TINT_ALPHA * label + (1 - LABEL_TINT_ALPHA) * back)
    return rgbOf(
        mix(red(labelRgb), red(bg)),
        mix(green(labelRgb), green(bg)),
        mix(blue(labelRgb), blue(bg)),
    )
}

/** The text colour of a chip: the label colour moved in lightness until it passes 4.5:1 on the tint (0xRRGGBB). */
internal fun labelChipText(labelRgb: Int, dark: Boolean): Int {
    val tint = labelChipTint(labelRgb, dark)
    val hsl = toHsl(labelRgb)
    var candidate = labelRgb
    for (n in 0..100) {
        val lightness = (if (dark) hsl.l + n / 100.0 else hsl.l - n / 100.0).coerceIn(0.0, 1.0)
        candidate = if (n == 0) labelRgb else fromHsl(hsl.h, hsl.s, lightness)
        if (contrastRatio(candidate, tint) >= LABEL_MIN_CONTRAST) break
    }
    return candidate
}

/** The colours a label chip is drawn with. [fill] stays translucent so a selected row shows through it. */
internal class LabelChipColors(val fill: Color, val text: Color)

/** The chip colours of [labelRgb] (0xRRGGBB, alpha ignored) in a light or dark theme. */
internal fun labelChipColors(labelRgb: Int, dark: Boolean): LabelChipColors {
    val rgb = labelRgb and 0xFFFFFF
    return LabelChipColors(
        fill = Color(red(rgb), green(rgb), blue(rgb)).copy(alpha = LABEL_TINT_ALPHA.toFloat()),
        text = Color(0xFF000000.toInt() or labelChipText(rgb, dark)),
    )
}
