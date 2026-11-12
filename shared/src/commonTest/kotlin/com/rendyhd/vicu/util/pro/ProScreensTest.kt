package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DueDates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The phone's Pro screens: reasons in words, the list views, Flagged, People, a person page and the agenda. */
class ProScreensTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val fmt = DateDisplayFormat("en-GB", hour12 = false)

    /** Thursday 12 November 2026. */
    private val today = LocalDate(2026, 11, 12)
    private val now = Instant.parse("2026-11-12T09:00:00Z")

    private fun due(day: LocalDate): String = DueDates.dateOnlyDue(day, zone).toString()

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // --- Reasons ---------------------------------------------------------------------------------

    @Test
    fun `each reason reads as the desktop says it`() {
        val task = Task(id = 1, title = "Report", startDate = StartDates.dateOnlyStart(LocalDate(2026, 11, 16), zone).toString())
        fun reason(vararg reasons: AvailabilityReason, blockers: List<Long> = emptyList()) =
            ProScreens.reasonText(task, TaskAvailability(false, reasons.toList(), false, blockers), { id -> if (id == 2L) "Budget draft" else null }, today, zone, fmt)

        assertEquals("Starts ${DateDisplay.formatWeekdayDate(LocalDate(2026, 11, 16), today, fmt)}", reason(AvailabilityReason.STARTS))
        assertEquals("On hold", reason(AvailabilityReason.ON_HOLD, AvailabilityReason.WAITING))
        assertEquals("Waiting", reason(AvailabilityReason.WAITING))
        assertEquals("After: Budget draft", reason(AvailabilityReason.AFTER, blockers = listOf(2)))
        assertEquals("After: Budget draft and 2 more", reason(AvailabilityReason.AFTER, blockers = listOf(2, 3, 4)))
        assertEquals("After another task", reason(AvailabilityReason.AFTER, blockers = listOf(9)))
        assertEquals("Later in sequence", reason(AvailabilityReason.SEQUENCE))
        assertEquals("Their promise", reason(AvailabilityReason.THEIRS))
        assertNull(ProScreens.reasonText(task, TaskAvailability(true, emptyList(), false, emptyList()), { null }, today, zone, fmt))
    }

    @Test
    fun `a blocker the device does not hold is named from the relation`() {
        val blocker = Task(id = 7, title = "Sign-off")
        val task = Task(id = 1, title = "Launch", relatedTasks = mapOf("blocked" to listOf(blocker)))
        val text = ProScreens.reasonText(task, TaskAvailability(false, listOf(AvailabilityReason.AFTER), false, listOf(7)), { null }, today, zone, fmt)
        assertEquals("After: Sign-off", text)
    }

    // --- Lists -----------------------------------------------------------------------------------

    @Test
    fun `views keep what they show and count what they leave out`() {
        val first = Task(id = 1, title = "First", projectId = 5, position = 1.0)
        val second = Task(id = 2, title = "Second", projectId = 5, position = 2.0)
        val done = Task(id = 3, title = "Done", projectId = 5, done = true)
        val prefs = mapOf(ProPrefs.projectModeKey(5) to JsonPrimitive("sequential"), "task_order.project.5" to JsonPrimitive("manual"))
        val context = Availability.context(now, zone, listOf(first, second), listOf(AreaProject(5)), prefs)
        val tasks = listOf(first, second, done)

        val available = ProScreens.filter(tasks, AvailabilityView.AVAILABLE, context::of)
        assertEquals(listOf(1L), available.tasks.map { it.id })
        assertEquals(2, available.hidden)
        assertTrue(context.of(first).next)
        assertEquals(listOf(AvailabilityReason.SEQUENCE), context.of(second).reasons)

        val remaining = ProScreens.filter(tasks, AvailabilityView.REMAINING, context::of)
        assertEquals(listOf(1L, 2L), remaining.tasks.map { it.id })
        assertEquals(1, remaining.hidden)

        val all = ProScreens.filter(tasks, AvailabilityView.ALL, context::of)
        assertEquals(3, all.tasks.size)
        assertEquals(0, all.hidden)

        // A task the rules do not judge counts as available.
        assertEquals(2, ProScreens.filter(listOf(first, second), AvailabilityView.AVAILABLE) { null }.tasks.size)
        assertEquals("1 task is not available yet.", ProScreens.hiddenSummary(1))
        assertEquals("4 tasks are not available yet.", ProScreens.hiddenSummary(4))
    }

    @Test
    fun `flagged keeps open flagged tasks of active projects`() {
        val parent = Task(id = 1, title = "Parent", projectId = 5, isFavorite = true)
        val child = Task(
            id = 2,
            title = "Child",
            projectId = 5,
            isFavorite = true,
            relatedTasks = mapOf("parenttask" to listOf(Task(id = 1, title = "Parent", projectId = 5))),
        )
        val parentWithChild = parent.copy(relatedTasks = mapOf("subtask" to listOf(child)))
        val archived = Task(id = 3, title = "Archived", projectId = 9, isFavorite = true)
        val plain = Task(id = 4, title = "Plain", projectId = 5)
        val doneFlag = Task(id = 5, title = "Done", projectId = 5, isFavorite = true, done = true)

        val flagged = ProScreens.flagged(listOf(parentWithChild, child, archived, plain, doneFlag), setOf(5L))
        assertEquals(listOf(1L), flagged.map { it.id })
    }

    // --- People ----------------------------------------------------------------------------------

    private val jan = Label(id = 31, title = "Jan de Vries", hexColor = "e8a400")
    private val maya = Label(id = 32, title = "Maya")
    private val sam = Label(id = 33, title = "Sam")

    @Test
    fun `people come by their next due day, then by name`() {
        val tasks = listOf(
            Task(id = 1, title = "Deck", dueDate = due(LocalDate(2026, 11, 10))),
            Task(id = 2, title = "Notes", dueDate = due(LocalDate(2026, 11, 20))),
            Task(id = 3, title = "Budget", dueDate = due(LocalDate(2026, 11, 14))),
            Task(id = 4, title = "Closed", dueDate = due(LocalDate(2026, 11, 1))),
        )
        val promises = mapOf(
            1L to obj("""{"dir":"theirs","person":31}"""),
            2L to obj("""{"dir":"mine","person":31}"""),
            3L to obj("""{"dir":"theirs","person":32}"""),
            4L to obj("""{"dir":"theirs","person":32,"verdicts":[{"id":"a","at":"2026-11-02T10:00:00Z","kind":"delivered"}]}"""),
        )
        val rows = ProScreens.peopleRows(listOf(sam, maya, jan), tasks, { promises[it.id] }, today, zone)

        assertEquals(listOf(31L, 32L, 33L), rows.map { it.id })
        val janRow = rows[0]
        assertEquals(1, janRow.theirs)
        assertEquals(1, janRow.mine)
        assertEquals(LocalDate(2026, 11, 10), janRow.next)
        assertEquals(1, janRow.overdue)
        assertEquals("e8a400", janRow.hexColor)
        // A promise with a final verdict is not open.
        assertEquals(1, rows[1].theirs)
        assertEquals(0, rows[1].overdue)
        assertNull(rows[2].next)
    }

    @Test
    fun `a person page lists theirs, yours and the tasks to confirm with them`() {
        val people = listOf(Person(31, "Jan de Vries"), Person(32, "Maya"))
        val tasks = listOf(
            Task(id = 1, title = "Deck"),
            Task(id = 2, title = "Notes"),
            Task(id = 3, title = "Confirm with Jan: the pilot region"),
            Task(id = 4, title = "Confirm with Maya: budget"),
            Task(id = 5, title = "Something else"),
        )
        val promises = mapOf(
            1L to obj("""{"dir":"theirs","person":31}"""),
            2L to obj("""{"dir":"mine","person":31}"""),
        )
        val sections = ProScreens.personSections(31, tasks, { promises[it.id] }, people)

        assertEquals(listOf(1L), sections.theirs.map { it.id })
        assertEquals(listOf(2L), sections.mine.map { it.id })
        assertEquals(listOf(3L), sections.confirm.map { it.id })
    }

    @Test
    fun `the agenda pins the open question, then their promises, yours and decisions they owe`() {
        val late = Task(id = 1, title = "Deck ", dueDate = due(LocalDate(2026, 11, 10)))
        val todayTask = Task(id = 2, title = "Figures", dueDate = due(today))
        val mine = Task(id = 3, title = "Review the plan")
        val promises = mapOf(
            1L to obj("""{"dir":"theirs","person":31,"verdicts":[{"id":"a","at":"2026-11-05T10:00:00Z","kind":"not_yet","new_due":"2026-11-10"},{"id":"b","at":"2026-11-06T10:00:00Z","kind":"not_yet","new_due":"2026-11-10"}]}"""),
            2L to obj("""{"dir":"theirs","person":31}"""),
            3L to obj("""{"dir":"mine","person":31}"""),
        )
        val ledger = ProLedger(
            obj(
                """{"v":1,"owed":{
                    "o1":{"value":{"what":"Pilot region: North or Central","from":31,"resolved":null}},
                    "o2":{"value":{"what":"Settled","from":31,"resolved":"2026-11-01"}},
                    "o3":{"value":{"what":"Not hers","from":32}}}}""",
            ),
        )
        val agenda = ProScreens.agenda("Jan", 31, listOf(todayTask, late), listOf(mine), { promises[it.id] }, ledger, today, zone)

        assertEquals(
            listOf(
                ProScreens.UNCLEAR_ITEM,
                "Deck (overdue), missed 2 times",
                "Figures (due today)",
                "Mine: Review the plan",
                "Decision needed: Pilot region: North or Central",
            ),
            agenda.items,
        )
        assertTrue(agenda.text.startsWith("1:1 with Jan\n\n- What's still unclear?\n"))
    }

    // --- Small words -----------------------------------------------------------------------------

    @Test
    fun `initials, the After chip, promise words and the start choices`() {
        assertEquals("JV", ProScreens.initials("Jan de Vries"))
        assertEquals("M", ProScreens.initials(" maya "))
        assertEquals("?", ProScreens.initials("  "))

        assertNull(ProScreens.afterValue(emptyList()))
        assertEquals("After: Budget draft", ProScreens.afterValue(listOf("Budget draft")))
        assertEquals("After: a task", ProScreens.afterValue(listOf("")))
        assertEquals("After 3 tasks", ProScreens.afterValue(listOf("a", "b", "c")))

        assertEquals("From Jan", ProScreens.promiseMeta("theirs", "Jan"))
        assertEquals("For Jan", ProScreens.promiseMeta("mine", "Jan"))
        assertNull(ProScreens.promiseMeta(null, "Jan"))

        assertEquals("New date", ProScreens.verdictLabel("renegotiated"))
        assertEquals("I did it", ProScreens.verdictLabel("taken_over"))

        // Thursday: tomorrow is Friday, next week starts on Monday the 16th.
        assertEquals(
            listOf(
                "Tomorrow" to LocalDate(2026, 11, 13),
                "Next week" to LocalDate(2026, 11, 16),
                "In two weeks" to LocalDate(2026, 11, 26),
            ),
            ProScreens.startChoices(today),
        )
        // On a Monday, next week is the Monday after.
        assertEquals(LocalDate(2026, 11, 23), ProScreens.startChoices(LocalDate(2026, 11, 16))[1].second)
        assertFalse(ProScreens.ESTIMATE_PRESETS.isEmpty())
    }
}
