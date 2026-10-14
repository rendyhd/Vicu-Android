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

    private val themes = listOf(
        Triple("light", VicuLightColorScheme, VicuLightColors),
        Triple("dark", VicuDarkColorScheme, VicuDarkColors),
    )

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

    private fun resolve(ref: String, theme: String, scheme: ColorScheme, colors: VicuColors): Color = when {
        ref.startsWith("colorScheme.") ->
            scheme.roles()[ref.removePrefix("colorScheme.")] ?: error("unknown colour scheme role in the fixture: $ref")
        ref.startsWith("custom.") -> {
            val parts = ref.removePrefix("custom.").split('.')
            val role = when (parts[0]) {
                "dueToday" -> colors.dueToday
                "done" -> colors.done
                "swipeComplete" -> colors.swipeComplete
                "swipeSchedule" -> colors.swipeSchedule
                else -> error("unknown custom colour in the fixture: $ref")
            }
            when (parts.getOrNull(1)) {
                null -> role.color
                "on" -> role.onColor
                "container" -> role.container
                "onContainer" -> role.onContainer
                else -> error("unknown custom colour part: $ref")
            }
        }
        ref.startsWith("role.priority.") -> when (ref.removePrefix("role.priority.")) {
            "low" -> colors.priorityLow
            "medium" -> colors.priorityMedium
            "high" -> colors.priorityHigh
            "urgent" -> colors.priorityUrgent
            else -> error("unknown priority role: $ref")
        }
        ref.startsWith("role.") -> {
            val roles = DesignTokensFixture.root.getValue("roles").jsonObject
            themeColor(roles.getValue(ref.removePrefix("role.")).jsonObject, theme)
        }
        else -> error("unknown contrast reference: $ref")
    }

    private fun background(element: JsonElement, theme: String, scheme: ColorScheme, colors: VicuColors): Pair<String, Color> =
        when (element) {
            is JsonPrimitive -> element.content to resolve(element.content, theme, scheme, colors)
            is JsonObject -> {
                val tintRef = element.getValue("tint").jsonPrimitive.content
                val tint = resolve(tintRef, theme, scheme, colors)
                val over = resolve(element.getValue("over").jsonPrimitive.content, theme, scheme, colors)
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
            for ((theme, scheme, colors) in themes) {
                val fg = resolve(p.getValue("fg").jsonPrimitive.content, theme, scheme, colors)
                for (entry in p.getValue("bg").jsonArray) {
                    val (label, bg) = background(entry, theme, scheme, colors)
                    val r = ratio(fg, bg)
                    assertTrue(r >= min, "$name, $theme, on $label: $r < $min")
                    checked++
                }
            }
        }
        assertTrue(checked > 100, "only $checked pairs were checked")
    }

    @Test
    fun `due today, done, swipe and priority colours are readable where they are used`() {
        for ((theme, scheme, colors) in themes) {
            val surfaces = listOf(scheme.surface, scheme.surfaceContainerLowest, scheme.surfaceContainerLow)
            for (surface in surfaces) {
                assertTrue(ratio(colors.dueToday.color, surface) >= 4.5, "$theme dueToday on surface")
                assertTrue(ratio(colors.done.color, surface) >= 4.5, "$theme done on surface")
            }
            val roles = listOf(
                "dueToday" to colors.dueToday,
                "done" to colors.done,
                "swipeComplete" to colors.swipeComplete,
                "swipeSchedule" to colors.swipeSchedule,
            )
            for ((name, role) in roles) {
                assertTrue(ratio(role.onColor, role.color) >= 4.5, "$theme $name onColor")
                assertTrue(ratio(role.onContainer, role.container) >= 4.5, "$theme $name onContainer")
            }
            val priorities = listOf(
                "low" to colors.priorityLow,
                "medium" to colors.priorityMedium,
                "high" to colors.priorityHigh,
                "urgent" to colors.priorityUrgent,
            )
            for ((name, color) in priorities) {
                for (surface in surfaces + scheme.surfaceContainer) {
                    assertTrue(ratio(color, surface) >= 4.5, "$theme priority $name on a surface")
                }
            }
        }
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
