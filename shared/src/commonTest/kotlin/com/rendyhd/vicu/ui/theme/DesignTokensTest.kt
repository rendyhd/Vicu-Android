package com.rendyhd.vicu.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
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

    // ---- custom colours, priority, identity ----------------------------------------------------

    private val customThemes = mapOf("light" to VicuLightColors, "dark" to VicuDarkColors)

    private fun hex(obj: JsonObject, theme: String): Color =
        DesignTokensFixture.parseHex(obj.getValue(theme).jsonPrimitive.content)

    private fun roleOf(colors: VicuColors, name: String): VicuColorRole = when (name) {
        "dueToday" -> colors.dueToday
        "done" -> colors.done
        "swipeComplete" -> colors.swipeComplete
        "swipeSchedule" -> colors.swipeSchedule
        else -> error("unknown custom colour in the fixture: $name")
    }

    @Test
    fun `custom colours match the fixture with their on and container colours`() {
        val custom = DesignTokensFixture.android.getValue("custom").jsonObject
        assertEquals(setOf("dueToday", "done", "swipeComplete", "swipeSchedule"), custom.keys)
        for ((theme, colors) in customThemes) {
            for ((name, value) in custom) {
                val expected = value.jsonObject
                val actual = roleOf(colors, name)
                assertEquals(hex(expected, theme), actual.color, "$theme $name")
                assertEquals(hex(expected.getValue("on").jsonObject, theme), actual.onColor, "$theme $name on")
                assertEquals(hex(expected.getValue("container").jsonObject, theme), actual.container, "$theme $name container")
                assertEquals(hex(expected.getValue("onContainer").jsonObject, theme), actual.onContainer, "$theme $name onContainer")
            }
        }
    }

    @Test
    fun `priority colours match the fixture roles and levels`() {
        val roles = DesignTokensFixture.root.getValue("roles").jsonObject
        for ((theme, colors) in customThemes) {
            assertEquals(hex(roles.getValue("priority.low").jsonObject, theme), colors.priorityLow, "$theme low")
            assertEquals(hex(roles.getValue("priority.medium").jsonObject, theme), colors.priorityMedium, "$theme medium")
            assertEquals(hex(roles.getValue("priority.high").jsonObject, theme), colors.priorityHigh, "$theme high")
            assertEquals(hex(roles.getValue("priority.urgent").jsonObject, theme), colors.priorityUrgent, "$theme urgent")
        }
        // Level to role follows priority.levels: 0 shows nothing, 4 and 5 are urgent.
        val levels = DesignTokensFixture.root.getValue("priority").jsonObject.getValue("levels").jsonObject
        for ((level, entry) in levels) {
            val role = entry.jsonObject.getValue("role").jsonPrimitive.content
            val expected = when (role) {
                "priority.low" -> VicuLightColors.priorityLow
                "priority.medium" -> VicuLightColors.priorityMedium
                "priority.high" -> VicuLightColors.priorityHigh
                "priority.urgent" -> VicuLightColors.priorityUrgent
                else -> error("unknown priority role: $role")
            }
            assertEquals(expected, VicuLightColors.priority(level.toInt()), "level $level")
        }
        assertEquals(null, VicuLightColors.priority(0))
    }

    @Test
    fun `identity colours match the fixture lists`() {
        val lists = DesignTokensFixture.root.getValue("identity").jsonObject.getValue("lists").jsonObject
        assertEquals(setOf("inbox", "today", "upcoming", "anytime", "routines", "review", "logbook"), lists.keys)
        for ((_, colors) in customThemes) {
            val identity = colors.identity
            val actual = mapOf(
                "inbox" to identity.inbox,
                "today" to identity.today,
                "upcoming" to identity.upcoming,
                "anytime" to identity.anytime,
                "routines" to identity.routines,
                "review" to identity.review,
                "logbook" to identity.logbook,
            )
            for ((name, value) in lists) {
                assertEquals(
                    DesignTokensFixture.parseHex(value.jsonObject.getValue("color").jsonPrimitive.content),
                    actual.getValue(name),
                    name,
                )
            }
        }
    }

    // ---- motion --------------------------------------------------------------------------------

    private val motion: JsonObject get() = DesignTokensFixture.root.getValue("motion").jsonObject

    private fun assertSpring(expected: JsonObject, actual: VicuSpring, what: String) {
        assertEquals(expected.getValue("damping").jsonPrimitive.float, actual.damping, "$what damping")
        assertEquals(expected.getValue("stiffness").jsonPrimitive.float, actual.stiffness, "$what stiffness")
    }

    @Test
    fun `motion scheme slots match the fixture and build spring specs`() {
        val scheme = motion.getValue("motionScheme").jsonObject
        val slots = mapOf(
            "fastSpatial" to (VicuMotion.fastSpatial to VicuMotion.fastSpatialSpec<Float>()),
            "defaultSpatial" to (VicuMotion.defaultSpatial to VicuMotion.defaultSpatialSpec<Float>()),
            "slowSpatial" to (VicuMotion.slowSpatial to VicuMotion.slowSpatialSpec<Float>()),
            "fastEffects" to (VicuMotion.fastEffects to VicuMotion.fastEffectsSpec<Float>()),
            "defaultEffects" to (VicuMotion.defaultEffects to VicuMotion.defaultEffectsSpec<Float>()),
            "slowEffects" to (VicuMotion.slowEffects to VicuMotion.slowEffectsSpec<Float>()),
        )
        assertEquals(slots.keys, scheme.keys)
        for ((name, pair) in slots) {
            val expected = scheme.getValue(name).jsonObject
            assertSpring(expected, pair.first, name)
            val spec = pair.second as SpringSpec<*>
            assertEquals(expected.getValue("damping").jsonPrimitive.float, spec.dampingRatio, "$name spec damping")
            assertEquals(expected.getValue("stiffness").jsonPrimitive.float, spec.stiffness, "$name spec stiffness")
        }
    }

    @Test
    fun `named motions match the fixture`() {
        val fade = motion.getValue("fade").jsonObject
        assertEquals(fade.getValue("fast").jsonObject.getValue("ms").jsonPrimitive.int, VicuMotion.fadeFastMs)
        assertEquals(fade.getValue("base").jsonObject.getValue("ms").jsonPrimitive.int, VicuMotion.fadeBaseMs)
        assertSpring(fade.getValue("fast").jsonObject.getValue("spring").jsonObject, VicuMotion.fadeFast, "fade.fast")
        assertSpring(fade.getValue("base").jsonObject.getValue("spring").jsonObject, VicuMotion.fadeBase, "fade.base")
        for ((name, ms, spring) in listOf(
            Triple("move", VicuMotion.moveMs, VicuMotion.move),
            Triple("moveExpressive", VicuMotion.moveExpressiveMs, VicuMotion.moveExpressive),
            Triple("pop", VicuMotion.popMs, VicuMotion.pop),
        )) {
            val expected = motion.getValue(name).jsonObject
            assertEquals(expected.getValue("ms").jsonPrimitive.int, ms, "$name ms")
            assertSpring(expected.getValue("spring").jsonObject, spring, name)
        }
        val page = motion.getValue("page").jsonObject
        assertEquals(page.getValue("outMs").jsonPrimitive.int, VicuMotion.pageOutMs)
        assertEquals(page.getValue("inMs").jsonPrimitive.int, VicuMotion.pageInMs)
        assertEquals(page.getValue("risePx").jsonPrimitive.int, VicuMotion.pageRisePx)
        val stagger = motion.getValue("stagger").jsonObject
        assertEquals(stagger.getValue("ms").jsonPrimitive.int, VicuMotion.staggerMs)
        assertEquals(stagger.getValue("maxItems").jsonPrimitive.int, VicuMotion.staggerMaxItems)
        assertEquals(motion.getValue("keyboardMoveMaxMs").jsonPrimitive.int, VicuMotion.keyboardMoveMaxMs)
        assertEquals(motion.getValue("checkDrawMs").jsonPrimitive.int, VicuMotion.checkDrawMs)
        assertEquals(motion.getValue("strikeDrawMs").jsonPrimitive.int, VicuMotion.strikeDrawMs)
    }
}
