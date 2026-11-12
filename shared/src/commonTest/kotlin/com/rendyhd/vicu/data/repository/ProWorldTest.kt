package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.ProListView
import com.rendyhd.vicu.data.local.VerdictSnooze
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.pro.AreaProject
import com.rendyhd.vicu.util.pro.Availability
import com.rendyhd.vicu.util.pro.AvailabilityView
import com.rendyhd.vicu.util.pro.LedgerRules
import com.rendyhd.vicu.util.pro.ProLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What the rows and screens read of Pro: nothing while it is off, the area for promises, the row's parts. */
class ProWorldTest {
    private val zone = TimeZone.of("America/New_York")
    private val fmt = DateDisplayFormat("en-US", hour12 = true)
    private val today = LocalDate(2026, 11, 12)
    private val now = Instant.parse("2026-11-12T15:00:00Z")

    private val work = Project(id = 10, title = "Work")
    private val pilot = Project(id = 11, title = "Pilot", parentProjectId = 10)
    private val home = Project(id = 20, title = "Home")
    private val jan = Label(id = 31, title = "Jan de Vries", hexColor = "e8a400")

    /** A ledger as the rows read it (the sync adds the revisions). */
    private fun ledger(promises: String): ProLedger = ProLedger(Json.parseToJsonElement("""{"version":1,"promises":$promises}""").jsonObject)

    private fun world(
        tasks: List<Task>,
        availability: Boolean = true,
        promises: Boolean = true,
        prefs: Map<String, JsonElement?> = emptyMap(),
        ledger: ProLedger = LedgerRules.empty(),
        listViews: Map<String, ProListView> = emptyMap(),
        snoozes: Map<String, VerdictSnooze> = emptyMap(),
    ): ProWorld {
        val projects = listOf(work, pilot, home)
        val allPrefs = prefs + ("label_kind.31" to JsonPrimitive("person"))
        return ProWorld(
            availabilityOn = availability,
            promisesOn = promises,
            now = now,
            today = today,
            zone = zone,
            open = tasks,
            donePromiseTasks = emptyList(),
            ledger = ledger,
            prefs = allPrefs,
            labels = listOf(jan),
            projects = projects,
            listViews = listViews,
            snoozes = snoozes,
            onboardingSeen = false,
            context = if (availability) {
                Availability.context(now, zone, tasks, projects.map { AreaProject(it.id, it.parentProjectId.takeIf { p -> p > 0 }) }, allPrefs, ledger)
            } else {
                null
            },
        )
    }

    private val promise = """{"1":{"value":{"dir":"theirs","person":31,"words":"Friday at the latest","sent":["2026-11-11T10:00:00Z"]}}}"""

    @Test
    fun `off answers as without Pro`() {
        val task = Task(id = 1, title = "Deck", projectId = 11, isFavorite = true)
        val off = ProWorld.OFF
        assertFalse(off.on)
        assertNull(off.rowOf(task, fmt))
        assertNull(off.promiseFor(task))
        assertNull(off.listView("anytime"))
        assertNull(off.availabilityOf(task))
        assertEquals(listOf(task), ProListState.OFF.filter(listOf(task)).tasks)
    }

    @Test
    fun `a promise of theirs shows the person's mark, their words and the sent mark`() {
        val task = Task(id = 1, title = "Deck", projectId = 11, dueDate = DueDates.dateOnlyDue(today, zone).toString(), labels = listOf(jan))
        val w = world(listOf(task), ledger = ledger(promise))
        val row = assertNotNull(w.rowOf(task, fmt))

        assertTrue(row.theirs)
        assertFalse(row.mine)
        assertEquals("From Jan de Vries", row.promiseText)
        assertEquals(31L, row.personId)
        assertEquals("Friday at the latest", row.words)
        assertTrue(row.sent)
        // A promise of theirs is a reason too, but the row shows the person instead.
        assertEquals("Their promise", row.reason)
        assertTrue(w.needsVerdict(task))
    }

    @Test
    fun `promises count only inside the area, availability everywhere`() {
        val inWork = Task(id = 1, title = "Deck", projectId = 11)
        val atHome = Task(id = 2, title = "Fence", projectId = 20)
        val promises = """{"1":{"value":{"dir":"theirs","person":31}},"2":{"value":{"dir":"theirs","person":31}}}"""
        val prefs = mapOf(
            "pro.area" to Json.parseToJsonElement("""["c1"]"""),
            "category.project.10" to JsonPrimitive("c1"),
            "category.project.20" to JsonPrimitive("c2"),
        )
        val w = world(listOf(inWork, atHome), prefs = prefs, ledger = ledger(promises))

        assertNotNull(w.promiseFor(inWork))
        assertNull(w.promiseFor(atHome))
        // No area: every project.
        assertNotNull(world(listOf(atHome), ledger = ledger(promises)).promiseFor(atHome))
    }

    @Test
    fun `a flag shows only with the availability layer, and the list view defaults to Available`() {
        val task = Task(id = 1, title = "Deck", projectId = 20, isFavorite = true)
        assertTrue(assertNotNull(world(listOf(task)).rowOf(task, fmt)).flagged)
        assertFalse(assertNotNull(world(listOf(task), availability = false).rowOf(task, fmt)).flagged)

        val w = world(listOf(task), listViews = mapOf("tag:4" to ProListView.ALL))
        assertEquals(AvailabilityView.AVAILABLE, w.listView("anytime"))
        assertEquals(AvailabilityView.ALL, w.listView("tag:4"))
        assertNull(world(listOf(task), availability = false).listView("anytime"))
    }

    @Test
    fun `ask me tomorrow holds the question until the next day`() {
        val task = Task(id = 1, title = "Deck", projectId = 11, dueDate = DueDates.dateOnlyDue(today, zone).toString())
        val snoozed = world(listOf(task), ledger = ledger(promise), snoozes = mapOf("1" to VerdictSnooze("2026-11-12", "2026-11-13")))
        assertFalse(snoozed.needsVerdict(task))
        assertFalse(snoozed.canSnooze(task))
        assertTrue(world(listOf(task), ledger = ledger(promise)).canSnooze(task))
    }
}
