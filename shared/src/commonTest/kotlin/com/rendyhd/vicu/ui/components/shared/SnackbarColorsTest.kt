package com.rendyhd.vicu.ui.components.shared

import androidx.compose.ui.graphics.luminance
import com.rendyhd.vicu.ui.theme.VicuBlackColorScheme
import com.rendyhd.vicu.ui.theme.VicuDarkColorScheme
import com.rendyhd.vicu.ui.theme.VicuLightColorScheme
import com.rendyhd.vicu.ui.theme.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnackbarColorsTest {

    private val schemes = mapOf(
        "light" to VicuLightColorScheme,
        "dark" to VicuDarkColorScheme,
        "black" to VicuBlackColorScheme,
    )

    @Test
    fun `text and buttons are readable on the bar in every theme`() {
        for ((name, scheme) in schemes) {
            val colors = scheme.snackbarColors()
            assertTrue(contrastRatio(colors.content, colors.container) >= 4.5, "$name text")
            assertTrue(contrastRatio(colors.action, colors.container) >= 4.5, "$name action")
        }
    }

    @Test
    fun `the bar is dark in a dark theme and stands off the page`() {
        for (scheme in listOf(VicuDarkColorScheme, VicuBlackColorScheme)) {
            val container = scheme.snackbarColors().container
            assertTrue(container.luminance() < 0.1f)
            assertTrue(container.luminance() > scheme.background.luminance())
            assertTrue(container.luminance() > scheme.surfaceContainerHigh.luminance())
        }
    }

    @Test
    fun `a light theme keeps the inverse bar`() {
        val colors = VicuLightColorScheme.snackbarColors()
        assertEquals(VicuLightColorScheme.inverseSurface, colors.container)
        assertEquals(VicuLightColorScheme.inverseOnSurface, colors.content)
        assertEquals(VicuLightColorScheme.inversePrimary, colors.action)
    }
}
