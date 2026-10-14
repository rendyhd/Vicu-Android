package com.rendyhd.vicu.ui.components.shared

import com.rendyhd.vicu.ui.theme.DesignTokensFixture
import com.rendyhd.vicu.ui.theme.VicuDarkColors
import com.rendyhd.vicu.ui.theme.VicuLightColors
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The code's smart list identity table equals design-tokens-v1.json `identity.lists`. */
class SmartListIdentityTest {

    private val lists get() = DesignTokensFixture.root.getValue("identity").jsonObject.getValue("lists").jsonObject

    @Test
    fun `the lists are the fixture's`() {
        assertEquals(lists.keys, SmartListIdentity.entries.map { it.key }.toSet())
    }

    @Test
    fun `icons are the fixture's androidIcon`() {
        for (identity in SmartListIdentity.entries) {
            val expected = lists.getValue(identity.key).jsonObject.getValue("androidIcon").jsonPrimitive.content
            // An ImageVector built by material-icons is named after its property: "Outlined.Inbox".
            assertEquals(expected, identity.icon.name, identity.key)
        }
    }

    @Test
    fun `colours are the fixture's in both themes`() {
        for ((theme, colors) in listOf("light" to VicuLightColors, "dark" to VicuDarkColors)) {
            for (identity in SmartListIdentity.entries) {
                val expected = DesignTokensFixture.parseHex(lists.getValue(identity.key).jsonObject.getValue("color").jsonPrimitive.content)
                assertEquals(expected, identity.color(colors.identity), "$theme ${identity.key}")
            }
        }
    }
}
