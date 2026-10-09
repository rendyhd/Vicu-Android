package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.graphics.Color
import com.rendyhd.vicu.ui.theme.VicuColorRole
import com.rendyhd.vicu.ui.theme.VicuColors
import com.rendyhd.vicu.ui.theme.VicuDarkColorScheme
import com.rendyhd.vicu.ui.theme.VicuDarkColors
import com.rendyhd.vicu.ui.theme.VicuLightColorScheme
import com.rendyhd.vicu.ui.theme.VicuLightColors
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeTintTest {

    private class Case(val name: String, val role: VicuColorRole, val surface: Color, val onSurface: Color)

    private fun cases(): List<Case> = listOf(
        Triple("light", VicuLightColorScheme, VicuLightColors),
        Triple("dark", VicuDarkColorScheme, VicuDarkColors),
    ).flatMap { (theme, scheme, colors: VicuColors) ->
        listOf("complete" to colors.swipeComplete, "schedule" to colors.swipeSchedule).map { (action, role) ->
            Case("$theme $action", role, scheme.background, scheme.onSurface)
        }
    }

    // Written out here (not taken from SwipeTint) so the check is independent of the code under test.
    private fun linear(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = 0.2126 * linear(a.red) + 0.7152 * linear(a.green) + 0.0722 * linear(a.blue)
        val lb = 0.2126 * linear(b.red) + 0.7152 * linear(b.green) + 0.0722 * linear(b.blue)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `the tint deepens from 12 to 35 percent up to the commit point`() {
        assertEquals(0.12f, SwipeTint.tintAlpha(0f), 1e-6f)
        assertEquals(0.35f, SwipeTint.tintAlpha(SwipeTint.COMMIT), 1e-6f)
        var last = 0f
        for (i in 0..50) {
            val a = SwipeTint.tintAlpha(i / 100f)
            assertTrue(a >= last, "the tint never gets lighter while the drag grows")
            last = a
        }
        assertEquals(0.35f, SwipeTint.tintAlpha(0.9f), 1e-6f, "past the commit point the row tint stays")
    }

    @Test
    fun `the label reads at 4_5 to 1 on the background at every tint step and when armed`() {
        for (case in cases()) {
            val steps = (0..100).map { it / 100f }
            for (fraction in steps) {
                val bg = SwipeTint.labelBackground(case.role, case.surface, fraction)
                val label = SwipeTint.labelColor(case.role, case.surface, case.onSurface, fraction)
                val r = ratio(label, bg)
                assertTrue(r >= 4.5, "${case.name} at ${(fraction * 100).toInt()} percent: $r")
            }
        }
    }

    @Test
    fun `the row text reads on the deepest tint in both themes`() {
        for (case in cases()) {
            val bg = SwipeTint.background(case.role, case.surface, SwipeTint.COMMIT - 0.001f)
            assertTrue(ratio(case.onSurface, bg) >= 4.5, "${case.name}: row text on the tint")
        }
    }

    @Test
    fun `from the commit point the strip is the full colour with its own on colour`() {
        for (case in cases()) {
            assertEquals(case.role.color, SwipeTint.labelBackground(case.role, case.surface, SwipeTint.COMMIT))
            assertEquals(case.role.onColor, SwipeTint.labelColor(case.role, case.surface, case.onSurface, SwipeTint.COMMIT))
        }
    }
}
