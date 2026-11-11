package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Every `timeBlocks` vector of cross-app-semantics-v1.json (section 16), in the contract's zones. The
 * desktop runs the same (`time-blocks-suite.ts`).
 */
class TimeBlocksFixtureTest {
    private val vectors = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .jsonObject.getValue("timeBlocks").jsonObject

    private val zones = listOf("Pacific/Auckland", "America/New_York", "Europe/Amsterdam").map(TimeZone::of)

    private fun list(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
    private fun instant(value: String?, zone: TimeZone): Instant? = value?.let { LocalDateTime.parse(it).toInstant(zone) }
    private fun iso(value: String?, zone: TimeZone): String = instant(value, zone)?.toString() ?: ""
    private fun date(value: String) = LocalDate.parse(value.substringBefore('T'))

    /** A stored date back as a wall-clock value in [zone], or null for no date. */
    private fun wall(value: String?, zone: TimeZone): LocalDateTime? =
        DateUtils.parseIsoDate(value)?.toLocalDateTime(zone)

    private fun wallOf(value: String?): LocalDateTime? = value?.let { LocalDateTime.parse(it) }

    private fun busyOf(vector: JsonObject): List<BusyRange> = vector.getValue("busy").jsonArray.map { pair ->
        val (from, to) = pair.jsonArray.map { it.jsonPrimitive.content }
        BusyRange(TimeBlocks.clockMinutes(from), TimeBlocks.clockMinutes(to))
    }

    private fun hoursOf(vector: JsonObject): PlannerHours = vector.getValue("hours").jsonArray.let {
        PlannerHours(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content)
    }

    private fun remindersOf(items: JsonArray): List<TaskReminder> = items.map { item ->
        val o = item.jsonObject
        TaskReminder(
            reminder = o.text("reminder").orEmpty(),
            relativePeriod = o["relative_period"]?.jsonPrimitive?.longOrNull ?: 0,
            relativeTo = o.text("relative_to").orEmpty(),
        )
    }

    @Test
    fun theConstantsMatch() {
        assertEquals(vectors.number("maxBlockMinutes"), TimeBlocks.MAX_BLOCK_MINUTES)
        assertEquals(vectors.number("defaultLengthMinutes"), TimeBlocks.DEFAULT_LENGTH_MINUTES)
        val hours = vectors.getValue("defaultHours").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(hours, listOf(TimeBlocks.DEFAULT_HOURS.start, TimeBlocks.DEFAULT_HOURS.end))
    }

    @Test
    fun isBlock() {
        for (zone in zones) {
            for (vector in list("isBlock")) {
                val name = "${vector.text("name")} in $zone"
                val start = instant(vector.text("start"), zone)
                val end = instant(vector.text("end"), zone)
                val expected = vector.getValue("block").jsonPrimitive.boolean
                assertEquals(expected, TimeBlocks.isBlock(start, end, zone), name)
                assertEquals(expected, TimeBlocks.isBlock(start?.toString(), end?.toString(), zone), "$name as stored")
                val block = TimeBlocks.blockOf(start?.toString(), end?.toString(), zone)
                assertEquals(expected, block != null, "$name blockOf")
                if (block != null) {
                    vector.text("day")?.let { assertEquals(LocalDate.parse(it), block.day, "$name day") }
                    vector["minutes"]?.let { assertEquals(it.jsonPrimitive.int, block.minutes, "$name minutes") }
                }
            }
        }
    }

    @Test
    fun placing() {
        for (zone in zones) {
            for (vector in list("placing")) {
                val name = "${vector.text("name")} in $zone"
                val start = instant(vector.text("start"), zone)!!
                val changes = TimeBlocks.placeBlock(iso(vector.text("due"), zone), start, vector.number("minutes"), zone)
                assertEquals(wallOf(vector.text("expectDue")), wall(changes.dueDate, zone), "$name due")
                assertEquals(wallOf(vector.text("start")), wall(changes.startDate, zone), "$name start")
                assertEquals(wallOf(vector.text("expectEnd")), wall(changes.endDate, zone), "$name end")
            }
        }
    }

    @Test
    fun moveToDay() {
        for (zone in zones) {
            for (vector in list("moveToDay")) {
                val name = "${vector.text("name")} in $zone"
                val block = assertNotNull(TimeBlocks.blockOf(iso(vector.text("start"), zone), iso(vector.text("end"), zone), zone), name)
                val changes = TimeBlocks.moveToDay(iso(vector.text("due"), zone).ifEmpty { null }, block, date(vector.text("day")!!), zone)
                assertEquals(wallOf(vector.text("expectStart")), wall(changes.startDate, zone), "$name start")
                assertEquals(wallOf(vector.text("expectEnd")), wall(changes.endDate, zone), "$name end")
                assertEquals(wallOf(vector.text("expectDue")), wall(changes.dueDate, zone), "$name due")
            }
        }
    }

    @Test
    fun topRow() {
        val block = vectors.getValue("topRow").jsonObject
        val day = date(block.text("day")!!)
        val expected = block.getValue("expect").jsonArray.map { it.jsonPrimitive.long }
        for (zone in zones) {
            val tasks = block.getValue("tasks").jsonArray.map { item ->
                val o = item.jsonObject
                Task(
                    id = o.getValue("id").jsonPrimitive.long,
                    title = "x",
                    done = o["done"]?.jsonPrimitive?.boolean ?: false,
                    dueDate = iso(o.text("due"), zone),
                    startDate = iso(o.text("start"), zone),
                    endDate = iso(o.text("end"), zone),
                )
            }
            assertEquals(expected, PlannerTimeline.dayTopRow(tasks, day, zone).map { it.id }, "top row in $zone")
        }
    }

    @Test
    fun dueChange() {
        for (zone in zones) {
            for (vector in list("dueChange")) {
                val name = "${vector.text("name")} in $zone"
                val today = date(vector.text("today")!!)
                val before = Task(
                    id = 1,
                    title = "x",
                    dueDate = iso(vector.text("oldDue"), zone),
                    startDate = iso(vector.text("start"), zone),
                    endDate = iso(vector.text("end"), zone),
                )
                val after = TimeBlocks.withDueChangeRules(before, before.copy(dueDate = iso(vector.text("newDue"), zone)), today, zone)
                assertEquals(wallOf(vector.text("expectStart")), wall(after.startDate, zone), "$name start")
                assertEquals(wallOf(vector.text("expectEnd")), wall(after.endDate, zone), "$name end")
            }
        }
    }

    @Test
    fun aChangeThatWritesTheBlockItselfIsLeftAlone() {
        val zone = zones.first()
        val today = LocalDate(2026, 10, 6)
        val before = Task(
            id = 1,
            title = "x",
            dueDate = iso("2026-10-06T23:59:59", zone),
            startDate = iso("2026-10-06T15:00:00", zone),
            endDate = iso("2026-10-06T15:30:00", zone),
        )
        val after = before.copy(
            dueDate = iso("2026-10-08T23:59:59", zone),
            startDate = iso("2026-10-08T09:00:00", zone),
            endDate = iso("2026-10-08T09:30:00", zone),
        )
        assertEquals(after, TimeBlocks.withDueChangeRules(before, after, today, zone))
    }

    @Test
    fun removingTheTimeBecauseOfTheDueDateAlsoTakesTheRemindersOnTheBlock() {
        val zone = zones.first()
        val today = LocalDate(2026, 10, 6)
        val kept = TaskReminder(reminder = "2026-10-06T09:00:00Z")
        val before = Task(
            id = 1,
            title = "x",
            dueDate = iso("2026-10-06T23:59:59", zone),
            startDate = iso("2026-10-06T15:00:00", zone),
            endDate = iso("2026-10-06T15:30:00", zone),
            reminders = listOf(TaskReminder(relativePeriod = -3600, relativeTo = "start_date"), kept),
        )
        val after = TimeBlocks.withDueChangeRules(before, before.copy(dueDate = TimeBlocks.CLEARED), today, zone)
        assertEquals(TimeBlocks.CLEARED, after.startDate)
        assertEquals(TimeBlocks.CLEARED, after.endDate)
        assertEquals(listOf(kept), after.reminders)
    }

    @Test
    fun removeTime() {
        for (vector in list("removeTime")) {
            val name = vector.text("name")
            val reminders = remindersOf(vector.getValue("reminders").jsonArray)
            val expected = remindersOf(vector.getValue("expect").jsonArray)
            assertEquals(expected, TimeBlocks.remindersWithoutBlock(reminders), name)
            val changes = TimeBlocks.removeTime(reminders)
            assertEquals(TimeBlocks.CLEARED, changes.startDate, name)
            assertEquals(TimeBlocks.CLEARED, changes.endDate, name)
            assertNull(changes.dueDate, name)
            assertEquals(expected.takeIf { it.size != reminders.size }, changes.reminders, name)
        }
    }

    @Test
    fun today() {
        val block = vectors.getValue("today").jsonObject
        for (zone in zones) {
            val today = date(block.text("now")!!)
            val tasks = block.getValue("tasks").jsonArray.map { item ->
                val t = item.jsonObject
                Task(
                    id = t.getValue("id").jsonPrimitive.long,
                    title = "x",
                    done = t.getValue("done").jsonPrimitive.boolean,
                    dueDate = iso(t.text("due"), zone),
                    startDate = iso(t.text("start"), zone),
                    endDate = iso(t.text("end"), zone),
                )
            }
            fun ids(key: String) = block.getValue(key).jsonArray.map { it.jsonPrimitive.long }
            val open = tasks.filter { !it.done }
            val split = TimeBlocks.splitToday(open, today, zone)
            assertEquals(ids("overdue"), split.overdue.map { it.id }, "overdue in $zone")
            assertEquals(ids("todaySection"), split.today.map { it.id }, "today in $zone")
            assertEquals(
                ids("upcoming"),
                open.filter { DueDates.isUpcoming(it.dueDate, today, zone) }.map { it.id },
                "upcoming in $zone",
            )
            assertEquals(
                ids("overdue").toSet() + ids("todaySection"),
                open.filter { TimeBlocks.isOnToday(it, today, zone) }.map { it.id }.toSet(),
                "the list in $zone",
            )
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
    fun nextFree() {
        for (zone in zones) {
            for (vector in list("nextFree")) {
                val slot = TimeBlocks.nextFreeSlot(
                    now = instant(vector.text("now"), zone)!!,
                    day = date(vector.text("day")!!),
                    hours = hoursOf(vector),
                    minutes = vector.number("minutes"),
                    busy = busyOf(vector),
                    zone = zone,
                )
                assertEquals(vector.text("expect"), slot?.let(TimeBlocks::clockText), "${vector.text("name")} in $zone")
            }
        }
    }

    @Test
    fun freeMinutes() {
        for (zone in zones) {
            for (vector in list("freeMinutes")) {
                val free = TimeBlocks.freeMinutes(
                    now = instant(vector.text("now"), zone)!!,
                    day = date(vector.text("day")!!),
                    hours = hoursOf(vector),
                    busy = busyOf(vector),
                    zone = zone,
                )
                assertEquals(vector.number("expect"), free, "${vector.text("name")} in $zone")
            }
        }
    }

    @Test
    fun fit() {
        for (zone in zones) {
            for (vector in list("fit")) {
                val tasks = vector.getValue("tasks").jsonArray.map { item ->
                    val t = item.jsonObject
                    Task(
                        id = t.getValue("id").jsonPrimitive.long,
                        title = "x",
                        dueDate = iso(t.text("due"), zone),
                        priority = t.number("priority"),
                    )
                }
                val fitted = TimeBlocks.fitTasks(
                    tasks = tasks,
                    now = instant(vector.text("now"), zone)!!,
                    day = date(vector.text("day")!!),
                    hours = hoursOf(vector),
                    busy = busyOf(vector),
                    zone = zone,
                    lengthOf = { vector.number("minutes") },
                )
                val expected = vector.getValue("expect").jsonArray.map { item ->
                    val e = item.jsonObject
                    Triple(e.getValue("id").jsonPrimitive.long, e.text("start"), e.text("end"))
                }
                assertEquals(
                    expected,
                    fitted.map { Triple(it.task.id, TimeBlocks.clockText(it.start), TimeBlocks.clockText(it.end)) },
                    "${vector.text("name")} in $zone",
                )
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

    @Test
    fun thePreferences() {
        val prefs = vectors.getValue("prefs").jsonObject
        for (item in prefs.getValue("defaultLength").jsonArray) {
            val value = item.jsonObject["value"]?.takeUnless { it is JsonNull }
            assertEquals(item.jsonObject.number("expect"), TimeBlocks.readDefaultLength(value), "length $value")
        }
        for (item in prefs.getValue("hours").jsonArray) {
            val value = item.jsonObject["value"]?.takeUnless { it is JsonNull }
            val expected = item.jsonObject.getValue("expect").jsonArray.map { it.jsonPrimitive.content }
            val hours = TimeBlocks.readHours(value)
            assertEquals(expected, listOf(hours.start, hours.end), "hours $value")
        }
    }
}
