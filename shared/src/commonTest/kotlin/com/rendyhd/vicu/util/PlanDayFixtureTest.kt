package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Every `planSession` vector of cross-app-semantics-v1.json (section 14.10), in three zones. The
 * desktop runs the same (`plan-day-suite.ts`).
 */
class PlanDayFixtureTest {
    private val vectors = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .jsonObject.getValue("planSession").jsonObject

    private val zones = listOf("Pacific/Auckland", "America/New_York", "Europe/Amsterdam").map(TimeZone::of)

    private val hours = vectors.getValue("hours").jsonArray.let {
        PlannerHours(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content)
    }
    private val defaultLength = vectors.getValue("defaultLength").jsonPrimitive.int
    private val inboxId = vectors.getValue("inboxProjectId").jsonPrimitive.long
    private val nowText = vectors.getValue("now").jsonPrimitive.content

    private fun list(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.day(): LocalDate = LocalDate.parse(getValue("day").jsonPrimitive.content)
    private fun JsonObject.ids(key: String): List<Long> = get(key)?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty()

    private fun stored(value: String?, zone: TimeZone): String =
        value?.let { CrossAppFixture.local(it, zone).toString() } ?: Constants.NULL_DATE_STRING

    private fun wall(value: String?, zone: TimeZone): String? =
        DateUtils.parseIsoDate(value?.takeIf { it.isNotBlank() })?.let { CrossAppFixture.wall(it, zone) }

    private fun tasks(zone: TimeZone): List<Task> = list("tasks").map { o ->
        Task(
            id = o.getValue("id").jsonPrimitive.long,
            title = o.getValue("title").jsonPrimitive.content,
            projectId = o["project"]?.jsonPrimitive?.long ?: 2L,
            done = o["done"]?.jsonPrimitive?.content == "true",
            priority = o["priority"]?.jsonPrimitive?.int ?: 0,
            dueDate = stored(o.text("due"), zone),
            startDate = stored(o.text("start"), zone),
            endDate = stored(o.text("end"), zone),
        )
    }

    private fun byId(id: Long, zone: TimeZone) = tasks(zone).first { it.id == id }

    private fun forZones(block: (TimeZone) -> Unit) = zones.forEach(block)

    private fun choices(vector: JsonObject) = DraftChoices(
        lengths = vector["lengths"]?.jsonObject.orEmpty().entries.associate { it.key.toLong() to it.value.jsonPrimitive.int },
        pins = vector["pins"]?.jsonObject.orEmpty().entries.associate { (key, value) ->
            val range = value.jsonArray
            key.toLong() to MinuteRange(range[0].jsonPrimitive.int, range[1].jsonPrimitive.int)
        },
        keep = vector.ids("keep").toSet(),
        starts = vector["starts"]?.jsonArray.orEmpty().map { pair ->
            val p = pair.jsonArray
            p[0].jsonPrimitive.long to p[1].jsonPrimitive.int
        },
    )

    @Test
    fun theLeftoversQueueOldestFirst() = forZones { zone ->
        val now = CrossAppFixture.local(nowText, zone)
        assertEquals(vectors.getValue("leftovers").jsonArray.map { it.jsonPrimitive.long }, PlanDay.leftoversQueue(tasks(zone), now, zone).map { it.id }, zone.id)
    }

    @Test
    fun leftoverActions() = forZones { zone ->
        val now = CrossAppFixture.local(nowText, zone)
        for (vector in list("leftoverActions")) {
            val name = "${vector.getValue("name").jsonPrimitive.content} in ${zone.id}"
            val action = assertNotNull(LeftoverAction.of(vector.getValue("action").jsonPrimitive.content), name)
            val task = byId(vector.getValue("task").jsonPrimitive.long, zone)
            val changes = PlanDay.leftoverChanges(task, action, now, zone)
            val expect = vector.getValue("expect").jsonObject
            if (expect["done"]?.jsonPrimitive?.content == "true") {
                // Done is the completion and nothing else: the view model sends only that.
                assertNull(changes, name)
                continue
            }
            val written = assertNotNull(changes, name)
            assertEquals(expect.text("due"), wall(written.dueDate, zone), name)
            assertEquals(TimeBlocks.CLEARED, written.startDate, name)
            assertEquals(TimeBlocks.CLEARED, written.endDate, name)
        }
    }

    @Test
    fun theGroups() = forZones { zone ->
        val now = CrossAppFixture.local(nowText, zone)
        for (vector in list("groups")) {
            val name = "${vector.getValue("name").jsonPrimitive.content} in ${zone.id}"
            val day = vector.day()
            val expect = vector.getValue("expect").jsonObject
            val onDay = PlanDay.onDayTasks(tasks(zone), day, now, zone)
            assertEquals(expect.ids("onDay"), onDay.map { it.id }, name)
            val inbox = PlanDay.withoutTasks(PlanDay.inboxCandidates(tasks(zone), inboxId, zone), onDay)
            assertEquals(expect.ids("inbox"), inbox.map { it.id }, name)
            val comingUp = PlanDay.withoutTasks(PlanDay.comingUpTasks(tasks(zone), day, zone), onDay)
            assertEquals(expect.ids("comingUp"), comingUp.map { it.id }, name)
        }
    }

    @Test
    fun theFreeRanges() = forZones { zone ->
        val now = CrossAppFixture.local(nowText, zone)
        for (vector in list("freeRanges")) {
            val name = "${vector.getValue("name").jsonPrimitive.content} in ${zone.id}"
            val day = vector.day()
            val expect = vector.getValue("expect").jsonArray.map { range ->
                MinuteRange(range.jsonArray[0].jsonPrimitive.int, range.jsonArray[1].jsonPrimitive.int)
            }
            assertEquals(expect, TimeBlocks.freeRanges(now, day, hours, TimeBlocks.busyOfDay(tasks(zone), day, zone), zone), name)
        }
    }

    @Test
    fun theMeter() = forZones { zone ->
        val now = CrossAppFixture.local(nowText, zone)
        for (vector in list("meter")) {
            val name = "${vector.getValue("name").jsonPrimitive.content} in ${zone.id}"
            val ticked = vector.ids("ticked").map { byId(it, zone) }
            val meter = PlanDay.planMeter(ticked, tasks(zone), now, vector.day(), hours, defaultLength, zone, choices(vector))
            val expect = vector.getValue("expect").jsonObject
            assertEquals(
                PlanMeter(
                    expect.getValue("tasks").jsonPrimitive.int,
                    expect.getValue("toPlace").jsonPrimitive.int,
                    expect.getValue("free").jsonPrimitive.int,
                    expect.getValue("over").jsonPrimitive.int,
                ),
                meter,
                name,
            )
        }
    }

    @Test
    fun theDraft() = forZones { zone ->
        val now = CrossAppFixture.local(nowText, zone)
        for (vector in list("draft")) {
            val name = "${vector.getValue("name").jsonPrimitive.content} in ${zone.id}"
            val ticked = vector.ids("ticked").map { byId(it, zone) }
            val draft = PlanDay.draftBlocks(ticked, tasks(zone), now, vector.day(), hours, defaultLength, zone, choices(vector))
            val expect = vector.getValue("expect").jsonObject
            val placed = expect.getValue("placed").jsonArray.map { row ->
                val r = row.jsonArray
                Triple(r[0].jsonPrimitive.long, r[1].jsonPrimitive.int, r[2].jsonPrimitive.int)
            }
            assertEquals(placed, draft.placed.map { Triple(it.id, it.start, it.end) }, name)
            assertEquals(expect.ids("noSlot"), draft.noSlot, name)
        }
    }

    @Test
    fun accepting() = forZones { zone ->
        for (vector in list("accept")) {
            val name = "${vector.getValue("name").jsonPrimitive.content} in ${zone.id}"
            val task = byId(vector.getValue("task").jsonPrimitive.long, zone)
            val range = vector["draft"]?.jsonArray
            val draft = range?.let { DraftBlock(task.id, it[0].jsonPrimitive.int, it[1].jsonPrimitive.int, pinned = false) }
            val changes = PlanDay.acceptChanges(task, vector.day(), draft, zone)
            val expect = vector["expect"]
            if (expect == null || expect is JsonNull) {
                assertNull(changes, name)
                continue
            }
            val written = assertNotNull(changes, name)
            val after = written.applyTo(task)
            assertEquals(expect.jsonObject.text("due"), wall(after.dueDate, zone), name)
            assertEquals(expect.jsonObject.text("start"), wall(after.startDate, zone), name)
            assertEquals(expect.jsonObject.text("end"), wall(after.endDate, zone), name)
        }
    }

    private fun reminderOf(o: JsonObject) = TaskReminder(
        relativePeriod = o.getValue("relative_period").jsonPrimitive.long,
        relativeTo = o.getValue("relative_to").jsonPrimitive.content,
    )

    @Test
    fun theStartReminderIsAddedOnce() {
        for (vector in list("reminders")) {
            val name = vector.getValue("name").jsonPrimitive.content
            val before = vector.getValue("before").jsonArray.map { reminderOf(it.jsonObject) }
            val expect = vector.getValue("expect").jsonArray.map { reminderOf(it.jsonObject) }
            val added = PlanDay.withStartReminder(before)
            assertEquals(expect, added, name)
            assertEquals(expect, PlanDay.withStartReminder(added), name)
        }
    }

    @Test
    fun turningTheRemindersOffTakesOnlyThoseAway() {
        val kept = TaskReminder(relativePeriod = -3600, relativeTo = "due_date")
        assertEquals(listOf(kept), PlanDay.withoutStartReminder(listOf(kept, TaskReminder(relativePeriod = 0, relativeTo = "start_date"))))
    }

    @Test
    fun undoWritesBackWhatTheSessionChanged() {
        val zone = TimeZone.of("Europe/Amsterdam")
        val before = Task(
            id = 1,
            title = "Task",
            projectId = 2,
            dueDate = stored("2026-10-25T23:59:59", zone),
            reminders = listOf(TaskReminder(relativePeriod = -3600, relativeTo = "due_date")),
        )
        val log = PlanSessionLog()
        // Two changes of one task: the first state is the one that comes back.
        log.changed(before, TimeBlocks.planForDay(before.reminders, LocalDate.parse("2026-10-27"), zone))
        val moved = before.copy(dueDate = stored("2026-10-27T23:59:59", zone))
        log.changed(moved, BlockChanges(reminders = emptyList()))
        val entry = log.entries.single()
        assertEquals(before, entry.before)
        // Someone changed the title elsewhere in the meantime: that stays.
        val current = moved.copy(reminders = emptyList(), title = "Renamed")
        val undone = entry.undoneOn(current)
        assertEquals(before.dueDate, undone.dueDate)
        assertEquals(before.reminders, undone.reminders)
        assertEquals("Renamed", undone.title)
    }

    @Test
    fun aCompletionAlsoBringsBackWhatRepeatingMoved() {
        val zone = TimeZone.of("Europe/Amsterdam")
        val before = Task(id = 1, title = "Task", projectId = 2, dueDate = stored("2026-10-25T23:59:59", zone))
        val log = PlanSessionLog()
        log.completed(before)
        val repeated = before.copy(dueDate = stored("2026-11-01T23:59:59", zone))
        assertEquals(before.dueDate, log.entries.single().undoneOn(repeated).dueDate)
    }

    @Test
    fun theNudgeIsOnlyThereWhileThePlannerIsOn() {
        assertEquals("21:30", PlannerNudge.timeOf(true, "21:30"))
        assertNull(PlannerNudge.timeOf(false, "21:30"))
        assertNull(PlannerNudge.timeOf(true, null))
        assertNull(PlannerNudge.timeOf(true, "25:00"))
    }

    @Test
    fun theNudgeComesRoundTheSameEveningOrTheNext() {
        val zone = TimeZone.of("Europe/Amsterdam")
        assertEquals(
            CrossAppFixture.local("2026-10-27T21:30:00", zone),
            PlannerNudge.nextAfter(CrossAppFixture.local("2026-10-27T09:10:00", zone), zone, "21:30"),
        )
        assertEquals(
            CrossAppFixture.local("2026-10-28T21:30:00", zone),
            PlannerNudge.nextAfter(CrossAppFixture.local("2026-10-27T21:30:00", zone), zone, "21:30"),
        )
    }
}
