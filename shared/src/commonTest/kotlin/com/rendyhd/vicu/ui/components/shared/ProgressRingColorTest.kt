package com.rendyhd.vicu.ui.components.shared

import androidx.compose.ui.graphics.Color
import com.rendyhd.vicu.ui.theme.VicuDarkColorScheme
import com.rendyhd.vicu.ui.theme.VicuLightColorScheme
import com.rendyhd.vicu.ui.theme.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressRingColorTest {

    private val projectColors = listOf(
        Color(0xFFFFEB3B), Color(0xFFE0E0E0), Color(0xFF263238), Color(0xFF8BC34A), Color(0xFF0066CC), Color(0xFFFFFFFF),
    )

    @Test
    fun `the fill reads at 3 to 1 on the drawer in both themes whatever the project colour`() {
        for ((name, scheme) in listOf("light" to VicuLightColorScheme, "dark" to VicuDarkColorScheme)) {
            val surfaces = listOf(scheme.surfaceContainerLow, scheme.secondaryContainer)
            for (color in projectColors) {
                val fill = ringFillColor(color, scheme.onSurface, surfaces)
                for (surface in surfaces) {
                    assertTrue(contrastRatio(fill, surface) >= 3.0, "$name $color on $surface: ${contrastRatio(fill, surface)}")
                }
            }
        }
    }

    @Test
    fun `a colour that already reads is left alone`() {
        val scheme = VicuLightColorScheme
        val blue = Color(0xFF0066CC)
        assertEquals(blue, ringFillColor(blue, scheme.onSurface, listOf(scheme.surfaceContainerLow)))
    }
}
