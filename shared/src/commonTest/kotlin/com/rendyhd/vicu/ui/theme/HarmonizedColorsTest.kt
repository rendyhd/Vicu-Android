package com.rendyhd.vicu.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.rendyhd.vicu.ui.theme.color.Blend
import com.rendyhd.vicu.ui.theme.color.Hct
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What harmonising the custom colours towards a device primary may and may not change. */
class HarmonizedColorsTest {

    /** Wallpaper-like primaries: one per 30 degrees of hue at tone 40 and 80. */
    private val sources: List<Color> = (0 until 360 step 30).flatMap { hue ->
        listOf(40.0, 80.0).map { tone -> Color(Hct.from(hue.toDouble(), 48.0, tone).toInt()) }
    }

    private fun lin(c: Float): Double =
        if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun roles(colors: VicuColors) = listOf(
        "dueToday" to colors.dueToday,
        "done" to colors.done,
        "swipeComplete" to colors.swipeComplete,
        "swipeSchedule" to colors.swipeSchedule,
    )

    @Test
    fun `tone is kept so the on and container pairs stay readable`() {
        for ((theme, base) in listOf("light" to VicuLightColors, "dark" to VicuDarkColors)) {
            for (source in sources) {
                val colors = base.harmonizedWith(source)
                for ((name, role) in roles(colors)) {
                    assertTrue(ratio(role.onColor, role.color) >= 4.5, "$theme $name onColor, source ${source.toArgb().toString(16)}")
                    assertTrue(ratio(role.onContainer, role.container) >= 4.5, "$theme $name onContainer, source ${source.toArgb().toString(16)}")
                }
            }
        }
    }

    @Test
    fun `status colours stay readable on the Vicu surfaces`() {
        val surfaces = mapOf(
            "light" to listOf(VicuLightColorScheme.surface, VicuLightColorScheme.surfaceContainerLow),
            "dark" to listOf(VicuDarkColorScheme.surface, VicuDarkColorScheme.surfaceContainerLow),
        )
        for ((theme, base) in listOf("light" to VicuLightColors, "dark" to VicuDarkColors)) {
            for (source in sources) {
                val colors = base.harmonizedWith(source)
                for (surface in surfaces.getValue(theme)) {
                    assertTrue(ratio(colors.dueToday.color, surface) >= 4.5, "$theme dueToday")
                    assertTrue(ratio(colors.done.color, surface) >= 4.5, "$theme done")
                }
            }
        }
    }

    @Test
    fun `hue moves at most 15 degrees and tone barely moves`() {
        for (design in listOf(VicuLightColors.dueToday.color, VicuLightColors.done.color, VicuDarkColors.swipeSchedule.color)) {
            for (source in sources) {
                val before = Hct.fromInt(design.toArgb())
                val after = Hct.fromInt(Blend.harmonize(design, source).toArgb())
                val diff = 180.0 - abs(abs(before.hue - after.hue) - 180.0)
                assertTrue(diff <= 15.5, "hue moved $diff degrees")
                assertTrue(abs(before.tone - after.tone) < 1.5, "tone moved ${abs(before.tone - after.tone)}")
            }
        }
    }

    @Test
    fun `a source of the same hue leaves a colour as it was`() {
        val design = VicuLightColors.done.color
        assertEquals(design.toArgb(), Blend.harmonize(design, design).toArgb())
    }

    @Test
    fun `priority and identity colours keep their hue`() {
        val harmonized = VicuLightColors.harmonizedWith(sources.first())
        assertEquals(VicuLightColors.priorityUrgent, harmonized.priorityUrgent)
        assertEquals(VicuLightColors.priorityLow, harmonized.priorityLow)
        assertEquals(VicuLightColors.identity, harmonized.identity)
    }
}
