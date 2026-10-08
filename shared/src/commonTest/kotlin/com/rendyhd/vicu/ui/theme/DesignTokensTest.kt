package com.rendyhd.vicu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Vicu theme (schemes, type, shapes) equals design-tokens-v1.json, the cross-app contract. */
class DesignTokensTest {

    private val schemes = mapOf("light" to VicuLightColorScheme, "dark" to VicuDarkColorScheme)

    @Test
    fun `fixture is contract version 1`() {
        assertEquals(1, DesignTokensFixture.root.getValue("contractVersion").jsonPrimitive.int)
    }

    @Test
    fun `colour schemes carry every role of the fixture`() {
        for ((theme, scheme) in schemes) {
            val expected = DesignTokensFixture.schemeFor(theme)
            val actual = scheme.roles()
            assertEquals(expected.keys, actual.keys, "$theme: the role lists differ")
            for ((role, color) in expected) {
                assertEquals(color, actual.getValue(role), "$theme.$role")
            }
        }
    }

    @Test
    fun `primary container is the seed in both themes`() {
        val seed = DesignTokensFixture.parseHex(DesignTokensFixture.android.getValue("seed").jsonPrimitive.content)
        for ((theme, scheme) in schemes) {
            assertEquals(seed, scheme.primaryContainer, theme)
        }
    }

    private fun styleOf(name: String): TextStyle = when (name) {
        "large top app bar" -> Typography.headlineMedium
        "displayLarge" -> Typography.displayLarge
        "displayMedium" -> Typography.displayMedium
        "displaySmall" -> Typography.displaySmall
        "headlineLarge" -> Typography.headlineLarge
        "headlineMedium" -> Typography.headlineMedium
        "headlineSmall" -> Typography.headlineSmall
        "titleLarge" -> Typography.titleLarge
        "titleMedium" -> Typography.titleMedium
        "titleSmall" -> Typography.titleSmall
        "bodyLarge" -> Typography.bodyLarge
        "bodyMedium" -> Typography.bodyMedium
        "bodySmall" -> Typography.bodySmall
        "labelLarge" -> Typography.labelLarge
        "labelMedium" -> Typography.labelMedium
        "labelSmall" -> Typography.labelSmall
        else -> error("unknown Android style in the fixture: $name")
    }

    private data class TypeRole(val name: String, val style: String, val sp: Int?, val weight: Int, val bold: Boolean)

    private fun typeRoles(): List<TypeRole> {
        val type = DesignTokensFixture.root.getValue("type").jsonObject
        return type.entries.filter { it.key != "about" }.mapNotNull { (name, value) ->
            val role = value.jsonObject
            val android = role["android"]?.jsonObject ?: return@mapNotNull null
            TypeRole(
                name = name,
                style = android.getValue("style").jsonPrimitive.content,
                sp = android["sp"]?.jsonPrimitive?.intOrNull,
                weight = role.getValue("weight").jsonPrimitive.int,
                bold = android["bold"]?.jsonPrimitive?.content == "true",
            )
        }
    }

    @Test
    fun `typography matches the fixture sizes`() {
        val roles = typeRoles()
        assertTrue(roles.isNotEmpty())
        for (role in roles) {
            val sp = role.sp ?: continue
            assertEquals(sp.toFloat(), styleOf(role.style).fontSize.value, "${role.name} (${role.style}) size")
        }
    }

    @Test
    fun `typography weights match the fixture where a style has one weight`() {
        val roles = typeRoles()
        val weightsByStyle = roles.groupBy { styleOf(it.style) }.mapValues { (_, rs) -> rs.map { it.weight }.toSet() }
        for (role in roles) {
            val style = styleOf(role.style)
            // A style shared by roles of different weights (labelMedium: meta 500, group 600) takes
            // the weight of the role that fixes a size; the other role sets its weight on its text.
            val pinned = role.sp != null || weightsByStyle.getValue(style).size == 1
            if (!pinned) continue
            assertEquals(FontWeight(role.weight), style.fontWeight, "${role.name} (${role.style}) weight")
            if (role.bold) assertEquals(FontWeight.Bold, style.fontWeight, "${role.name} bold")
        }
    }

    @Test
    fun `no text style is below 11 sp`() {
        val all = listOf(
            Typography.displayLarge, Typography.displayMedium, Typography.displaySmall,
            Typography.headlineLarge, Typography.headlineMedium, Typography.headlineSmall,
            Typography.titleLarge, Typography.titleMedium, Typography.titleSmall,
            Typography.bodyLarge, Typography.bodyMedium, Typography.bodySmall,
            Typography.labelLarge, Typography.labelMedium, Typography.labelSmall,
        )
        for (style in all) assertTrue(style.fontSize.value >= 11f, "style below 11 sp: ${style.fontSize}")
    }

    @Test
    fun `shapes match the fixture radii`() {
        val radius = DesignTokensFixture.root.getValue("radius").jsonObject
        fun dp(role: String): Float {
            val value = radius.getValue(role).jsonObject.getValue("dp")
            assertTrue(value !is JsonNull, "$role has no dp radius")
            return (value as JsonPrimitive).content.toFloat()
        }
        assertEquals(RoundedCornerShape(dp("control").dp), VicuShapes.small, "control = small")
        assertEquals(RoundedCornerShape(dp("popover").dp), VicuShapes.medium, "popover = medium")
        assertEquals(RoundedCornerShape(dp("card").dp), VicuShapes.large, "card = large")
        assertEquals(RoundedCornerShape(dp("sheet").dp), VicuShapes.extraLarge, "sheet = extraLarge")
        assertEquals("full", radius.getValue("chip").jsonPrimitive.contentOrNull)
        assertEquals(RoundedCornerShape(percent = 50), VicuChipShape)
    }
}
