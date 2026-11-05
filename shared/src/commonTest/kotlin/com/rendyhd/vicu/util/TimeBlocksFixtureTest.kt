package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `timeBlocks` vectors of cross-app-semantics-v1.json (section 16) that this far have code: what
 * a block is, the default reminder and the row text. The desktop runs the same (`time-blocks-suite.ts`).
 */
class TimeBlocksFixtureTest {
    private val vectors = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .jsonObject.getValue("timeBlocks").jsonObject

    private val zones = listOf("Pacific/Auckland", "America/New_York", "Europe/Amsterdam").map(TimeZone::of)

    private fun list(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun instant(value: String?, zone: TimeZone): Instant? = value?.let { LocalDateTime.parse(it).toInstant(zone) }

    @Test
    fun theLimitMatches() {
        assertEquals(vectors.getValue("maxBlockMinutes").jsonPrimitive.int, TimeBlocks.MAX_BLOCK_MINUTES)
    }

    @Test
    fun isBlock() {
        for (zone in zones) {
            for (vector in list("isBlock")) {
                val start = instant(vector.text("start"), zone)
                val end = instant(vector.text("end"), zone)
                assertEquals(
                    vector.getValue("block").jsonPrimitive.boolean,
                    TimeBlocks.isBlock(start, end, zone),
                    "${vector.text("name")} in $zone",
                )
                assertEquals(
                    vector.getValue("block").jsonPrimitive.boolean,
                    TimeBlocks.isBlock(start?.toString(), end?.toString(), zone),
                    "${vector.text("name")} as stored, in $zone",
                )
            }
        }
    }

    @Test
    fun defaultReminder() {
        for (zone in zones) {
            for (vector in list("defaultReminder")) {
                val name = "${vector.text("name")} in $zone"
                val start = instant(vector.text("start"), zone)?.toString()
                val end = instant(vector.text("end"), zone)?.toString()
                val block = TimeBlocks.isBlock(start, end, zone)
                val reminder = DefaultReminder.build(
                    dueDate = instant(vector.text("due"), zone)?.toString(),
                    offsetSeconds = vector.getValue("offset").jsonPrimitive.int,
                    relativeTo = vector.text("relativeTo")!!,
                    blockStart = start.takeIf { block },
                    blockEnd = end.takeIf { block },
                )
                val expected = vector["expect"]?.takeIf { it is JsonObject }?.jsonObject
                if (expected == null) {
                    assertNull(reminder, name)
                } else {
                    assertNotNull(reminder, name)
                    assertEquals(instant(expected.text("reminder"), zone), Instant.parse(reminder.reminder), name)
                    assertEquals(expected.getValue("relative_period").jsonPrimitive.long, reminder.relativePeriod, name)
                    assertEquals(expected.text("relative_to"), reminder.relativeTo, name)
                }
            }
        }
    }

    @Test
    fun display() {
        for (vector in list("display")) {
            val text = DateDisplay.formatBlockRow(
                LocalDateTime.parse(vector.text("start")!!),
                LocalDateTime.parse(vector.text("end")!!),
                LocalDate.parse(vector.text("now")!!.substringBefore('T')),
                DateDisplayFormat(vector.text("locale")!!, hour12 = vector.getValue("hour12").jsonPrimitive.boolean),
            )
            assertEquals(vector.text("text"), text, vector.text("name"))
        }
    }
}
