package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `planEvents`, `eventBusy` and `eventChanges` vectors of cross-app-semantics-v1.json (contract
 * section 20) in the contract's zones. The desktop runs the same (`plan-events-suite.ts`); the
 * `icsImport` block is in [IcsImportFixtureTest].
 */
class PlanEventsFixtureTest {
    private val fixture = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
    private val planEvents = fixture.getValue("planEvents").jsonObject
    private val eventBusy = fixture.getValue("eventBusy").jsonObject
    private val eventChanges = fixture.getValue("eventChanges").jsonObject

    private val zones = listOf("Pacific/Auckland", "America/New_York", "Europe/Amsterdam").map(TimeZone::of)

    private fun JsonObject.list(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun date(value: String) = LocalDate.parse(value)

    private fun eventOf(stored: JsonElement, id: String = "x"): ListedEvent =
        ListedEvent(id, assertNotNull(PlanEvents.read(stored), "not an Event: $stored"))

    @Test
    fun theConstantsMatch() {
        assertEquals(planEvents.text("keyPrefix"), PlanEvents.EVENT_KEY_PREFIX)
        assertEquals(planEvents.text("myEventsCalendarId"), PlanEvents.MY_EVENTS_CALENDAR_ID)
        assertEquals(planEvents.text("myEventsColor"), PlanEvents.MY_EVENTS_COLOR)
        assertEquals(planEvents.getValue("maxMinutes").jsonPrimitive.int, PlanEvents.MAX_EVENT_MINUTES)
        assertTrue(PlanEvents.MY_EVENTS_COLOR in CalendarEvents.CALENDAR_COLORS)
    }

    @Test
    fun read() {
        for (vector in planEvents.list("read")) {
            val name = vector.text("name")
            val event = PlanEvents.read(vector.getValue("value"))
            val expect = vector.getValue("expect")
            if (expect is JsonNull) {
                assertNull(event, name)
                continue
            }
            assertNotNull(event, name)
            assertEquals(expect, PlanEvents.encode(event), name)
            assertEquals(vector.getValue("busy").jsonPrimitive.boolean, PlanEvents.isBusy(event), name)
            // Reading what is written gives the same Event.
            assertEquals(expect, PlanEvents.encode(assertNotNull(PlanEvents.read(PlanEvents.encode(event)))), name)
        }
    }

    @Test
    fun list() {
        val list = planEvents.getValue("list").jsonObject
        val values = list.getValue("values").jsonObject.mapValues { (_, v) -> v.takeUnless { it is JsonNull } }
        assertEquals(list.getValue("expect").jsonArray.map { it.jsonPrimitive.content }, PlanEvents.readAll(values).map { it.id })
    }

    @Test
    fun days() {
        for (vector in planEvents.list("days")) {
            val event = eventOf(vector.getValue("event")).event
            assertEquals(
                vector.getValue("expect").jsonArray.map { date(it.jsonPrimitive.content) },
                PlanEvents.daysBetween(event, date(vector.text("from")), date(vector.text("to"))),
                vector.text("name"),
            )
        }
    }

    @Test
    fun times() {
        for (zone in zones) {
            for (vector in planEvents.list("times")) {
                val name = "${vector.text("name")} in $zone"
                val days = vector.getValue("days").jsonArray.map { date(it.jsonPrimitive.content) }
                val occurrences = PlanEvents.occurrences(listOf(eventOf(vector.getValue("event"), "e")), days, zone)
                val expect = vector.list("expect").map {
                    listOf(it.text("day"), it.text("start"), it.text("end"), it.text("allDay"), it.text("busy"))
                }
                val actual = occurrences.map {
                    listOf(
                        it.day.toString(),
                        CrossAppFixture.wall(it.start, zone),
                        CrossAppFixture.wall(it.end, zone),
                        it.allDay.toString(),
                        it.busy.toString(),
                    )
                }
                assertEquals(expect, actual, name)
                for (o in occurrences) {
                    assertEquals(PlanEvents.MY_EVENTS_CALENDAR_ID, o.calendarId, name)
                    assertEquals("e", o.planEventId, name)
                    assertEquals(false, o.tentative, name)
                }
            }
        }
    }

    @Test
    fun choices() {
        for (vector in planEvents.list("choices")) {
            val day = date(vector.text("day"))
            val choices = PlanEvents.repeatChoices(day)
            val expect = vector.list("expect")
            assertEquals(expect.map { it.text("label") }, choices.map { it.label }, "$day")
            assertEquals(expect.map { it.getValue("repeat") }, choices.map { PlanEvents.encodeRepeat(it.repeat) }, "$day")
            for ((index, choice) in choices.withIndex()) {
                // Each choice happens on the day it was offered for, and reads as its label.
                val event = PlanEvent("x", day, null, 0, choice.repeat)
                assertEquals(listOf(day), PlanEvents.daysBetween(event, day, day), "${choice.label} on $day")
                assertEquals(index, PlanEvents.choiceIndex(choices, choice.repeat), choice.label)
                if (choice.repeat !is EventRepeat.Yearly) assertEquals(choice.label, PlanEvents.repeatText(choice.repeat))
            }
        }
    }

    @Test
    fun choiceDays() {
        for (vector in planEvents.list("choiceDays")) {
            val event = assertNotNull(PlanEvents.read(vector.getValue("event")), vector.text("name"))
            val scope = when (vector.text("scope")) {
                "all" -> EventScope.ALL
                "this" -> EventScope.THIS
                else -> EventScope.FOLLOWING
            }
            val expect = vector["expect"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { date(it) }
            assertEquals(expect, PlanEvents.repeatChoiceDay(event, date(vector.text("day")), scope), vector.text("name"))
        }
    }

    @Test
    fun text() {
        for (vector in planEvents.list("text")) {
            val repeat = assertNotNull(PlanEvents.readRepeat(vector.getValue("repeat")))
            assertEquals(vector.text("text"), PlanEvents.repeatText(repeat))
        }
    }

    // --- eventBusy ---------------------------------------------------------------------------------

    private fun busyEvents(zone: TimeZone): List<CalendarEvent> = eventBusy.list("events").map {
        CalendarEvent(
            id = it.text("id"),
            calendarId = it.text("calendar"),
            title = it.text("id"),
            start = CrossAppFixture.local(it.text("start"), zone),
            end = CrossAppFixture.local(it.text("end"), zone),
            allDay = it.getValue("allDay").jsonPrimitive.boolean,
            busy = it.getValue("busy").jsonPrimitive.boolean,
            tentative = it.getValue("tentative").jsonPrimitive.boolean,
        )
    }

    private fun counts(): (String) -> Boolean = CalendarEvents.calendarCounts(
        eventBusy.list("calendars")
            .filter { it.getValue("shown").jsonPrimitive.boolean && it.getValue("countsAsBusy").jsonPrimitive.boolean }
            .mapTo(HashSet()) { it.text("id") },
    )

    @Test
    fun eventBusyDays() {
        for (zone in zones) {
            val events = busyEvents(zone)
            for (vector in eventBusy.list("days")) {
                val day = date(vector.text("day"))
                val name = "$day in $zone"
                val timed = CalendarEvents.timedEventsOn(events, day, zone)
                    .map { Triple(it.event.id, it.startMinute, it.endMinute) }
                    .sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
                val expectTimed = vector.list("timed").map {
                    Triple(it.text("id"), it.getValue("from").jsonPrimitive.int, it.getValue("to").jsonPrimitive.int)
                }
                assertEquals(expectTimed, timed, name)
                assertEquals(
                    vector.getValue("allDay").jsonArray.map { it.jsonPrimitive.content },
                    CalendarEvents.allDayEventsOn(events, day, zone).map { it.id },
                    name,
                )
                val busy = CalendarEvents.eventBusyOfDay(events, day, zone, counts())
                    .sortedWith(compareBy({ it.start }, { it.end }))
                val expectBusy = vector.getValue("busy").jsonArray.map { pair ->
                    val (a, b) = pair.jsonArray.map { it.jsonPrimitive.int }
                    BusyRange(a, b)
                }
                assertEquals(expectBusy, busy, name)
            }
        }
    }

    @Test
    fun freeTimeCountsEventsAndBlocksOnce() {
        val free = eventBusy.getValue("free").jsonObject
        for (zone in zones) {
            val day = date(free.text("day"))
            val hours = free.getValue("hours").jsonArray.let { PlannerHours(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content) }
            val blocks = free.getValue("blocks").jsonArray.map { pair ->
                val (a, b) = pair.jsonArray.map { it.jsonPrimitive.content }
                BusyRange(TimeBlocks.clockMinutes(a), TimeBlocks.clockMinutes(b))
            }
            val busy = blocks + CalendarEvents.eventBusyOfDay(busyEvents(zone), day, zone, counts())
            val now = CrossAppFixture.local(free.text("now"), zone)
            assertEquals(free.getValue("freeMinutes").jsonPrimitive.int, TimeBlocks.freeMinutes(now, day, hours, busy, zone), "$zone")
            for (vector in free.list("nextFree")) {
                val slot = TimeBlocks.nextFreeSlot(now, day, hours, vector.getValue("minutes").jsonPrimitive.int, busy, zone)
                assertEquals(vector.getValue("expect").jsonPrimitive.contentOrNull, slot?.let(TimeBlocks::clockText), "$zone")
            }
        }
    }

    @Test
    fun busyOnly() {
        val zone = zones.first()
        for (vector in eventBusy.list("busyOnly")) {
            val e = vector.getValue("event").jsonObject
            val event = CalendarEvent(
                id = "x",
                calendarId = "work",
                title = e.text("title"),
                location = e["location"]?.jsonPrimitive?.contentOrNull,
                start = CrossAppFixture.local("2026-11-10T09:00:00", zone),
                end = CrossAppFixture.local("2026-11-10T10:00:00", zone),
                allDay = false,
                busy = e.getValue("busy").jsonPrimitive.boolean,
                tentative = false,
            )
            val shown = CalendarEvents.busyOnly(event)
            assertEquals(vector.getValue("expect").jsonObject.text("title"), shown.title, vector.text("name"))
            assertNull(shown.location, vector.text("name"))
        }
    }

    // --- eventChanges ------------------------------------------------------------------------------

    private fun editOf(value: JsonObject?): EventEdit {
        if (value == null) return EventEdit()
        fun nullableText(key: String): EditValue<String?>? =
            if (!value.containsKey(key)) null else EditValue(value[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content)
        return EventEdit(
            title = value["title"]?.jsonPrimitive?.content,
            time = nullableText("time"),
            minutes = value["minutes"]?.jsonPrimitive?.int,
            busy = value["busy"]?.jsonPrimitive?.booleanOrNull,
            repeat = value["repeat"]?.let { assertNotNull(PlanEvents.readRepeat(it)) },
            until = nullableText("until")?.let { EditValue(it.value?.let(LocalDate::parse)) },
            day = value["day"]?.jsonPrimitive?.content?.let(LocalDate::parse),
        )
    }

    private fun entries(value: JsonElement): Map<String, JsonElement?> =
        value.jsonObject.mapValues { (_, v) -> v.takeUnless { it is JsonNull } }

    @Test
    fun changes() {
        for (vector in eventChanges.list("cases")) {
            val name = vector.text("name")
            val stored = entries(vector.getValue("entries"))
            val id = vector.text("id")
            val day = date(vector.text("day"))
            val listed = eventOf(assertNotNull(stored[PlanEvents.eventKey(id)]), id)
            val writes = when (vector.text("action")) {
                "skip" -> PlanEvents.skip(listed, day)
                "delete" -> PlanEvents.delete(id)
                else -> PlanEvents.change(
                    listed,
                    day,
                    editOf(vector["edit"]?.jsonObject),
                    when (vector["scope"]?.jsonPrimitive?.content) {
                        "this" -> EventScope.THIS
                        "following" -> EventScope.FOLLOWING
                        else -> EventScope.ALL
                    },
                    vector["newId"]?.jsonPrimitive?.content ?: "new",
                )
            }
            assertEquals(entries(vector.getValue("expect")), writes, name)
            assertEquals(entries(vector.getValue("undo")), PlanEvents.undo(writes, stored), name)
            // What is written reads back as an Event.
            for (value in writes.values) if (value != null) assertNotNull(PlanEvents.read(value), name)
        }
    }
}
