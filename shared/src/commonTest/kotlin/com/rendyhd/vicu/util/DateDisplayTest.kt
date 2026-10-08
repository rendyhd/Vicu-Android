package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `dateDisplay` vectors of test-fixtures/cross-app-semantics-v1.json (the desktop runs the same ones). */
class DateDisplayFixtureTest {

    @Serializable
    private data class Vector(
        val name: String,
        val context: String,
        val locale: String,
        val hour12: Boolean,
        val zone: String,
        val now: String,
        val due: String,
        val dateOnly: Boolean,
        val expect: String,
    )

    @Serializable
    private data class Block(val vectors: List<Vector>)

    private val json = Json { ignoreUnknownKeys = true }

    private val vectors: List<Vector> by lazy {
        val root = json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
        json.decodeFromJsonElement(Block.serializer(), root["dateDisplay"] as JsonObject).vectors
    }

    private fun contextOf(name: String): DateContext = when (name) {
        "row" -> DateContext.ROW
        "row.inToday" -> DateContext.ROW_IN_TODAY
        "row.inDayGroup" -> DateContext.ROW_IN_DAY_GROUP
        "chip" -> DateContext.CHIP
        "header.day" -> DateContext.HEADER_DAY
        "header.full" -> DateContext.HEADER_FULL
        "logbook.group" -> DateContext.LOGBOOK_GROUP
        "logbook.time" -> DateContext.LOGBOOK_TIME
        else -> error("unknown context $name")
    }

    private fun formatOf(v: Vector) = DateDisplayFormat(Locale.forLanguageTag(v.locale), v.hour12)

    @Test
    fun `the fixture has the vectors`() {
        assertEquals(206, vectors.size)
        assertEquals(DateContext.entries.size, vectors.map { it.context }.distinct().size)
    }

    @Test
    fun `every vector gives the pinned text`() {
        for (v in vectors) {
            val today = LocalDateTime.parse(v.now).date
            assertEquals(
                v.expect,
                DateDisplay.format(contextOf(v.context), LocalDateTime.parse(v.due), today, v.dateOnly, formatOf(v)),
                v.name,
            )
        }
    }

    @Test
    fun `every vector gives the same text from a stored instant read in the vector zone`() {
        for (v in vectors) {
            val zone = TimeZone.of(v.zone)
            val today = LocalDateTime.parse(v.now).date
            val stored = CrossAppFixture.local(v.due, zone).toString()
            assertEquals(
                v.expect,
                DateDisplay.formatDue(contextOf(v.context), stored, today, zone, formatOf(v)),
                v.name,
            )
        }
    }

    @Test
    fun `a whole-day header matches the date-only vectors`() {
        for (v in vectors.filter { it.context == "header.day" || it.context == "header.full" }) {
            val today = LocalDateTime.parse(v.now).date
            assertEquals(
                v.expect,
                DateDisplay.formatDay(contextOf(v.context), LocalDateTime.parse(v.due).date, today, formatOf(v)),
                v.name,
            )
        }
    }
}

class DateDisplayTest {

    private val us = DateDisplayFormat(Locale.US, hour12 = true)
    private val gb = DateDisplayFormat(Locale.UK, hour12 = false)
    private val today = LocalDate(2026, 10, 7)

    @Test
    fun `a stored date with no value, or the null date, shows nothing`() {
        val zone = TimeZone.of("Europe/Amsterdam")
        assertEquals("", DateDisplay.formatDue(DateContext.ROW, null, today, zone, us))
        assertEquals("", DateDisplay.formatDue(DateContext.ROW, "", today, zone, us))
        assertEquals("", DateDisplay.formatDue(DateContext.CHIP, Constants.NULL_DATE_STRING, today, zone, us))
    }

    @Test
    fun `a chip reads like Sat 10 Oct, 15 00`() {
        val value = LocalDateTime(2026, 10, 10, 15, 0)
        assertEquals("Sat 10 Oct, 15:00", DateDisplay.format(DateContext.CHIP, value, today, false, gb))
        assertEquals("Sat, Oct 10, 3:00 PM", DateDisplay.format(DateContext.CHIP, value, today, false, us))
        assertEquals("Sat 10 Oct", DateDisplay.format(DateContext.CHIP, value, today, true, gb))
    }

    @Test
    fun `an English locale without a region follows en-US and other regions follow en-GB`() {
        val value = LocalDateTime(2026, 10, 10, 15, 0)
        fun chip(tag: String) =
            DateDisplay.format(DateContext.CHIP, value, today, false, DateDisplayFormat(Locale.forLanguageTag(tag), true))
        assertEquals("Sat, Oct 10, 3:00 PM", chip("en"))
        assertEquals("Sat, Oct 10, 3:00 PM", chip("en-CA"))
        assertEquals("Sat 10 Oct, 3:00 pm", chip("en-AU"))
        assertEquals("Sat 10 Oct, 3:00 pm", chip("en-NL"))
    }

    @Test
    fun `another language keeps the phrase structure with its own names`() {
        val value = LocalDateTime(2026, 10, 10, 15, 0)
        val de = DateDisplayFormat(Locale.GERMANY, hour12 = false)
        val text = DateDisplay.format(DateContext.CHIP, value, today, false, de)
        assertTrue(text.endsWith(", 15:00"), text)
        assertTrue("10" in text, text)
        assertTrue(!text.contains("Sat"), text)
        // The words are the app's strings, English for now.
        assertEquals("Today", DateDisplay.format(DateContext.HEADER_DAY, LocalDateTime(2026, 10, 7, 0, 0), today, true, de))
    }

    @Test
    fun `an absolute stamp has the year and the time`() {
        val value = LocalDateTime(2030, 1, 1, 9, 0)
        assertEquals("Jan 1, 2030, 9:00 AM", DateDisplay.formatAbsolute(value, us))
        assertEquals("1 Jan 2030, 09:00", DateDisplay.formatAbsolute(value, gb))
        assertEquals("Mar 1, 2026", DateDisplay.formatDayMonthYear(LocalDate(2026, 3, 1), us))
    }
}
