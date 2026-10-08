package com.rendyhd.vicu.ui.theme.color

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Blend.harmonize against vectors generated once with @material/material-color-utilities 0.4.0
 * (src/androidHostTest/resources/harmonize-vectors-v1.json): the port must give the same colour.
 */
class HarmonizeVectorsTest {

    private fun argb(hex: String): Int = 0xFF000000.toInt() or hex.removePrefix("#").toInt(16)

    private fun hex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    private val vectors by lazy {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("harmonize-vectors-v1.json")) {
            "harmonize-vectors-v1.json is not on the test classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject.getValue("vectors").jsonArray.map { it.jsonObject }
    }

    @Test
    fun `the port gives the reference colour for every vector`() {
        assertTrue(vectors.size > 500, "only ${vectors.size} vectors")
        val failures = vectors.mapNotNull { v ->
            val design = v.getValue("design").jsonPrimitive.content
            val source = v.getValue("source").jsonPrimitive.content
            val expected = v.getValue("expected").jsonPrimitive.content
            val actual = hex(Blend.harmonize(argb(design), argb(source)))
            if (actual == expected) null else "${v.getValue("name").jsonPrimitive.content}: expected $expected, got $actual"
        }
        assertEquals(emptyList(), failures.take(10), "${failures.size} of ${vectors.size} vectors differ")
    }

    @Test
    fun `the Compose overload agrees with the Int one and keeps alpha`() {
        val design = Color(0xFF9A4600)
        val source = Color(0xFF984061)
        val out = Blend.harmonize(design, source)
        assertEquals(hex(Blend.harmonize(argb("#9A4600"), argb("#984061"))), hex(out.toArgb()))
        assertEquals(1f, out.alpha)
        assertEquals(0.5f, Blend.harmonize(design.copy(alpha = 0.5f), source).alpha, 0.01f)
    }
}
