package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.repository.SyncedEntries
import com.rendyhd.vicu.data.repository.WeekStartPreferences
import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs the `weekStart` vectors of `test-fixtures/cross-app-semantics-v1.json` (contract section
 * 2.1): reading the stored value, the offer of the Vikunja account's own setting, the month grid
 * and the order of weekdays. The weeks themselves are in [DueDatesFixtureTest].
 */
class WeekStartFixtureTest {

    private val block = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .jsonObject.getValue("weekStart").jsonObject

    private fun vectors(name: String) = block.getValue(name).jsonArray.map { it.jsonObject }

    /** A string of the fixture, or null for JSON null. */
    private fun textOrNull(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    @Test
    fun `a stored value reads as its week start and anything else as Monday`() {
        val read = vectors("read")
        assertTrue(read.size >= 7)
        for (vector in read) {
            val value = vector["value"]?.takeUnless { it is JsonNull }
            assertEquals(vector.getValue("expect").jsonPrimitive.content, WeekStart.read(value).key, "value $value")
        }
    }

    @Test
    fun `only Sunday and Saturday are written and Monday is a reset`() {
        assertNull(WeekStart.write(WeekStart.MONDAY))
        assertEquals(JsonPrimitive("sunday"), WeekStart.write(WeekStart.SUNDAY))
        assertEquals(JsonPrimitive("saturday"), WeekStart.write(WeekStart.SATURDAY))
        for (choice in WeekStart.CHOICES) assertEquals(choice, WeekStart.read(WeekStart.write(choice)))
    }

    @Test
    fun `the Vikunja account's week start is offered only while it is one of the three and differs`() {
        for (vector in vectors("offer")) {
            val vikunja = vector["vikunja"]?.jsonPrimitive?.intOrNull
            val inUse = WeekStart.fromKey(vector.getValue("inUse").jsonPrimitive.content)
            val expect = textOrNull(vector["expect"])
            assertEquals(expect, WeekStart.offer(vikunja, inUse)?.key, "vikunja $vikunja while ${inUse.key}")
        }
    }

    @Test
    fun `the month grid starts on the week start and always has six weeks`() {
        for (vector in vectors("grid")) {
            val weekStart = WeekStart.fromKey(vector.getValue("weekStart").jsonPrimitive.content)
            val grid = WhenLogic.monthGrid(LocalDate.parse(vector.getValue("month").jsonPrimitive.content), weekStart)
            val where = "${vector["month"]} (${weekStart.key})"
            assertEquals(6, grid.size, where)
            assertTrue(grid.all { it.size == 7 }, where)
            assertEquals(vector.getValue("first").jsonPrimitive.content, grid.first().first().date.toString(), "first of $where")
            assertEquals(vector.getValue("last").jsonPrimitive.content, grid.last().last().date.toString(), "last of $where")
            assertTrue(grid.all { it.first().date.dayOfWeek == weekStart.day }, "every row of $where starts on its day")
        }
    }

    @Test
    fun `the weekdays come in the order of the week`() {
        for (vector in vectors("weekdays")) {
            val weekStart = WeekStart.fromKey(vector.getValue("weekStart").jsonPrimitive.content)
            assertEquals(
                vector.getValue("expect").jsonArray.map { it.jsonPrimitive.int },
                weekStart.weekdays.map { it.isoDayNumber },
                weekStart.key,
            )
        }
    }

    @Test
    fun `next week is the Monday in next week whatever the week start`() {
        // Sunday weeks: Wednesday and Saturday give the coming Monday; a Sunday is already in the new week.
        assertEquals(LocalDate(2026, 11, 23), DueDates.nextWeekDay(LocalDate(2026, 11, 18), WeekStart.SUNDAY))
        assertEquals(LocalDate(2026, 11, 23), DueDates.nextWeekDay(LocalDate(2026, 11, 21), WeekStart.SUNDAY))
        assertEquals(LocalDate(2026, 11, 30), DueDates.nextWeekDay(LocalDate(2026, 11, 22), WeekStart.SUNDAY))
        // Monday weeks: unchanged, a Sunday gives the next day.
        assertEquals(LocalDate(2026, 11, 23), DueDates.nextWeekDay(LocalDate(2026, 11, 22), WeekStart.MONDAY))
        for (choice in WhenLogic.quickChoices(LocalDate(2026, 11, 22), WeekStart.SUNDAY)) {
            if (choice.id == WhenQuickChoice.Id.NEXT_WEEK) assertEquals(LocalDate(2026, 11, 30), choice.date)
        }
    }

    @Test
    fun `a rule that rounds to a week is left to the device unless weeks start on Monday`() {
        val filter = com.rendyhd.vicu.domain.model.CustomListFilter(rule = "due_date >= now/w && due_date < now/w+1w")
        val today = LocalDate(2026, 10, 6)
        val zone = TimeZone.UTC
        assertTrue("now/w" in CustomListFilterBuilder.buildFilterString(filter, today, zone, WeekStart.MONDAY))
        assertTrue("now/w" !in CustomListFilterBuilder.buildFilterString(filter, today, zone, WeekStart.SUNDAY))
        val other = com.rendyhd.vicu.domain.model.CustomListFilter(rule = "due_date < now/d+1d")
        assertTrue("now/d" in CustomListFilterBuilder.buildFilterString(other, today, zone, WeekStart.SUNDAY))
    }

    @Test
    fun `the preference reads and writes the registry key`() = runTest {
        val entries = MutableStateFlow<Map<String, JsonElement?>>(emptyMap())
        val synced = object : SyncedEntries {
            override val entries = entries
            override suspend fun set(key: String, value: JsonElement?) {
                entries.value = entries.value + (key to value)
            }
        }
        val preferences = WeekStartPreferences(synced)
        assertEquals(WeekStart.MONDAY, preferences.current())

        preferences.set(WeekStart.SUNDAY)
        assertEquals(JsonPrimitive("sunday"), entries.value[SyncedPrefKeys.WEEK_START])
        assertEquals(WeekStart.SUNDAY, preferences.weekStart.first())

        // Back to Monday is a reset, so it reaches the other devices.
        preferences.set(WeekStart.MONDAY)
        assertTrue(SyncedPrefKeys.WEEK_START in entries.value)
        assertNull(entries.value[SyncedPrefKeys.WEEK_START])
        assertEquals(WeekStart.MONDAY, preferences.current())
    }

    @Test
    fun `the day clock carries the week start and emits the day again when it changes`() = runTest {
        val time = object : TimeSource {
            override fun now(): Instant = Instant.parse("2026-11-18T10:00:00Z")
            override fun zone(): TimeZone = TimeZone.UTC
        }
        val clock = DayClock(backgroundScope, time, ticking = false)
        assertEquals(WeekStart.MONDAY, clock.day.value.weekStart)

        clock.setWeekStart(WeekStart.SUNDAY)
        assertEquals(ClockDay(LocalDate(2026, 11, 18), TimeZone.UTC, WeekStart.SUNDAY), clock.day.value)

        // A re-read of the day keeps it.
        clock.refresh()
        assertEquals(WeekStart.SUNDAY, clock.day.value.weekStart)
    }
}
