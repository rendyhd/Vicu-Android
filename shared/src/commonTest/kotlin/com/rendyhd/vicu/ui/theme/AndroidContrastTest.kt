package com.rendyhd.vicu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pairs of `android.contrast` in design-tokens-v1.json, resolved per theme: colour scheme
 * roles come from the Vicu schemes in code, custom colours and role.* from the fixture.
 */
class AndroidContrastTest {

    private val themes = listOf("light" to VicuLightColorScheme, "dark" to VicuDarkColorScheme)

    private fun linear(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun themeColor(obj: JsonObject, theme: String): Color =
        DesignTokensFixture.parseHex(obj.getValue(theme).jsonPrimitive.content)

    private fun resolve(ref: String, theme: String, scheme: ColorScheme): Color = when {
        ref.startsWith("colorScheme.") ->
            scheme.roles()[ref.removePrefix("colorScheme.")] ?: error("unknown colour scheme role in the fixture: $ref")
        ref.startsWith("custom.") -> {
            val parts = ref.removePrefix("custom.").split('.')
            val custom = DesignTokensFixture.android.getValue("custom").jsonObject.getValue(parts[0]).jsonObject
            if (parts.size == 1) themeColor(custom, theme) else themeColor(custom.getValue(parts[1]).jsonObject, theme)
        }
        ref.startsWith("role.") -> {
            val roles = DesignTokensFixture.root.getValue("roles").jsonObject
            themeColor(roles.getValue(ref.removePrefix("role.")).jsonObject, theme)
        }
        else -> error("unknown contrast reference: $ref")
    }

    private fun background(element: JsonElement, theme: String, scheme: ColorScheme): Pair<String, Color> =
        when (element) {
            is JsonPrimitive -> element.content to resolve(element.content, theme, scheme)
            is JsonObject -> {
                val tintRef = element.getValue("tint").jsonPrimitive.content
                val tint = resolve(tintRef, theme, scheme)
                val over = resolve(element.getValue("over").jsonPrimitive.content, theme, scheme)
                val alpha = element.getValue("alpha").jsonPrimitive.double
                "$alpha tint of $tintRef" to tint.copy(alpha = alpha.toFloat()).compositeOver(over)
            }
            else -> error("unexpected background entry: $element")
        }

    @Test
    fun `every contrast pair of the fixture meets its minimum in both themes`() {
        val pairs = DesignTokensFixture.android.getValue("contrast").jsonArray
        assertTrue(pairs.isNotEmpty())
        var checked = 0
        for (pair in pairs) {
            val p = pair.jsonObject
            val name = p.getValue("name").jsonPrimitive.content
            val min = p.getValue("min").jsonPrimitive.double
            for ((theme, scheme) in themes) {
                val fg = resolve(p.getValue("fg").jsonPrimitive.content, theme, scheme)
                for (entry in p.getValue("bg").jsonArray) {
                    val (label, bg) = background(entry, theme, scheme)
                    val r = ratio(fg, bg)
                    assertTrue(r >= min, "$name, $theme, on $label: $r < $min")
                    checked++
                }
            }
        }
        assertTrue(checked > 100, "only $checked pairs were checked")
    }

    @Test
    fun `the required text pairs are in the fixture`() {
        val names = DesignTokensFixture.android.getValue("contrast").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        for (required in listOf("onSurface on surfaces", "onSurfaceVariant on surfaces", "error on surfaces")) {
            assertTrue(required in names, "missing contrast pair: $required")
        }
        assertTrue(names.any { it.startsWith("error on its own 8% tint") })
    }

    @Test
    fun `contrast of black on white is 21`() {
        assertEquals(21.0, ratio(Color.Black, Color.White), 0.001)
    }
}
