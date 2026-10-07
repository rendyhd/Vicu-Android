package com.rendyhd.vicu.util

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorUtilsTest {

    @Test
    fun `reads the six digit form Vikunja stores, with or without the hash`() {
        assertEquals(0xFFE8384F.toInt(), parseHexArgb("e8384f"))
        assertEquals(0xFFE8384F.toInt(), parseHexArgb("#e8384f"))
        assertEquals(0xFFE8384F.toInt(), parseHexArgb("#E8384F"))
        assertEquals(0xFF000000.toInt(), parseHexArgb("000000"))
        assertEquals(0xFFFFFFFF.toInt(), parseHexArgb("#ffffff"))
    }

    @Test
    fun `reads the eight digit form with the alpha first`() {
        assertEquals(0x80E8384F.toInt(), parseHexArgb("#80e8384f"))
        assertEquals(0x00FFFFFF, parseHexArgb("00ffffff"))
    }

    @Test
    fun `anything else is no colour instead of an exception`() {
        listOf("", "   ", "#", "red", "#red", "e8384", "e8384ff", "#12345g", "#12 456", "0x123456", "##e8384f")
            .forEach { assertNull(parseHexArgb(it), "'$it'") }
    }

    @Test
    fun `surrounding spaces are ignored`() {
        assertEquals(0xFFE8384F.toInt(), parseHexArgb("  #e8384f "))
    }

    @Test
    fun `parseHexColor gives a Color for a valid value and null otherwise`() {
        assertEquals(Color(0xFFE8384F.toInt()), parseHexColor("#e8384f"))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("not a colour"))
    }

    @Test
    fun `every preset is a valid colour`() {
        assertTrue(PRESET_COLORS.isNotEmpty())
        PRESET_COLORS.forEach { assertNotNull(parseHexColor(it), it) }
        assertEquals(PRESET_COLORS.size, PRESET_COLORS.toSet().size, "no duplicates")
    }
}
