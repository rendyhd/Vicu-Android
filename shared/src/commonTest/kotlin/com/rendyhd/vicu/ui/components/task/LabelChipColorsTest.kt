package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.graphics.Color
import com.rendyhd.vicu.ui.theme.DesignTokensFixture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The label chip colours equal design-tokens-v1.json `labelChip` (the same vectors the desktop runs). */
class LabelChipColorsTest {

    private val chip: JsonObject get() = DesignTokensFixture.root.getValue("labelChip").jsonObject

    private fun rgb(hex: String): Int {
        require(Regex("#[0-9A-Fa-f]{6}").matches(hex)) { "not #RRGGBB: $hex" }
        return hex.substring(1).toInt(16)
    }

    private fun hex(rgb: Int): String = "#" + rgb.toString(16).padStart(6, '0').uppercase()

    @Test
    fun `constants are the fixture's`() {
        assertEquals(chip.getValue("tintAlpha").jsonPrimitive.content.toDouble(), LABEL_TINT_ALPHA)
        assertEquals(chip.getValue("minContrast").jsonPrimitive.content.toDouble(), LABEL_MIN_CONTRAST)
    }

    @Test
    fun `every vector gives the fixture's tint and text in both themes`() {
        val vectors = chip.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 20, "the fixture has its vectors")
        for (entry in vectors) {
            val vector = entry.jsonObject
            val label = vector.getValue("label").jsonPrimitive.content
            for ((theme, dark) in listOf("light" to false, "dark" to true)) {
                val expected = vector.getValue(theme).jsonObject
                assertEquals(expected.getValue("tint").jsonPrimitive.content, hex(labelChipTint(rgb(label), dark)), "$label $theme tint")
                assertEquals(expected.getValue("text").jsonPrimitive.content, hex(labelChipText(rgb(label), dark)), "$label $theme text")
            }
        }
    }

    @Test
    fun `text reaches the minimum contrast on its tint unless the label cannot`() {
        for (entry in chip.getValue("vectors").jsonArray) {
            val label = rgb(entry.jsonObject.getValue("label").jsonPrimitive.content)
            for (dark in listOf(false, true)) {
                val ratio = contrastRatio(labelChipText(label, dark), labelChipTint(label, dark))
                assertTrue(ratio >= LABEL_MIN_CONTRAST, "${hex(label)} dark=$dark ratio $ratio")
            }
        }
    }

    @Test
    fun `the fill is the opaque composite of 12 percent over bg page and the alpha of a stored colour is ignored`() {
        for (dark in listOf(false, true)) {
            val colors = labelChipColors(0x80FF4136.toInt(), dark = dark)
            assertEquals(Color(0xFF000000.toInt() or labelChipTint(0xFF4136, dark)), colors.fill, "dark=$dark")
            assertEquals(1f, colors.fill.alpha)
        }
        // 12% of FF4136 over white: the vector of the contract.
        assertEquals(Color(0xFFFFE8E7), labelChipColors(0xFF4136, dark = false).fill)
    }
}
