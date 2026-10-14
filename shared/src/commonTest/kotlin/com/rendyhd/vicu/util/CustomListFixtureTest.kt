package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListWire
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.model.toDomain
import com.rendyhd.vicu.domain.model.toWire
import com.rendyhd.vicu.util.CrossAppFixture.date
import com.rendyhd.vicu.util.CrossAppFixture.fixture
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.CrossAppFixture.zones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the `customLists` vectors of `test-fixtures/cross-app-semantics-v1.json` through the one
 * custom-list evaluator, in three time zones and at three times of day, so the result cannot
 * depend on the zone or on when "now" is read. Each vector's filter is decoded from its synced
 * JSON form (`include_overdue`, `project_ids`, ...), the way a carrier from desktop arrives.
 *
 * Every accepted task must also pass the server filter built for the same day: the server may
 * send more than the window, never less.
 */
class CustomListFixtureTest {

    /** Early, midday and late local time on the vector's day. */
    private val clocks = listOf("00:00:30", "10:00:00", "23:59:30")

    private fun filterOf(vector: CrossAppFixture.CustomListVector) =
        CustomListWire(id = "vector", name = vector.name, filter = vector.filter).toDomain().filter

    /** The fixture tasks as the app holds them: due dates as UTC instants in [zone]. */
    private fun tasks(zone: TimeZone): List<Task> = fixture.tasks.map {
        Task(
            id = it.id,
            title = "Task ${it.id}",
            done = it.done,
            projectId = it.projectId,
            priority = it.priority,
            dueDate = it.due?.let { due -> local(due, zone).toString() } ?: Constants.NULL_DATE_STRING,
            labels = it.labelIds.map { id -> Label(id = id, title = "Label $id") },
        )
    }

    private fun serverRow(task: Task) = ServerFilterEval.Row(task.done, task.projectId, task.dueDate)

    /** The day the clock reports for [vector] at [clock], the way the app asks for it. */
    private fun dayAt(vector: CrossAppFixture.CustomListVector, clock: String, zone: TimeZone, scope: CoroutineScope): ClockDay {
        val now = local("${vector.today}T$clock", zone)
        return DayClock(scope, FixedTimeSource(now, zone), ticking = false).day.value
    }

    @Test
    fun `the fixture has the custom list vectors`() {
        assertTrue(fixture.customLists.size >= 25, "found ${fixture.customLists.size} vectors")
    }

    @Test
    fun `every customLists vector in every zone at every time of day`() {
        val scope = CoroutineScope(Job())
        for (zone in zones) {
            val all = tasks(zone)
            for (vector in fixture.customLists) for (clock in clocks) {
                val day = dayAt(vector, clock, zone, scope)
                assertEquals(date(vector.today), day.date, "the clock's day for ${vector.name} at $clock in $zone")

                val shown = CustomListFilterBuilder.applyClientSideFilters(all, filterOf(vector), day.date, day.zone)

                assertEquals(vector.expect, shown.map { it.id }, "${vector.name} at $clock in $zone")
            }
        }
        scope.cancel()
    }

    @Test
    fun `the server filter keeps everything the evaluator accepts, in every zone at every time`() {
        val scope = CoroutineScope(Job())
        for (zone in zones) {
            val all = tasks(zone)
            for (vector in fixture.customLists) for (clock in clocks) {
                val day = dayAt(vector, clock, zone, scope)
                val filter = filterOf(vector)
                val serverFilter = CustomListFilterBuilder.buildFilterString(filter, day.date, day.zone)

                for (task in CustomListFilterBuilder.applyClientSideFilters(all, filter, day.date, day.zone)) {
                    assertTrue(
                        ServerFilterEval.matches(serverFilter, serverRow(task)),
                        "task ${task.id} of ${vector.name} at $clock in $zone is dropped by: $serverFilter",
                    )
                }
            }
        }
        scope.cancel()
    }

    @Test
    fun `a blank due date counts as no due date, like the null date`() {
        for (zone in zones) {
            val withBlank = tasks(zone).map { if (it.dueDate == Constants.NULL_DATE_STRING) it.copy(dueDate = "") else it }
            for (vector in fixture.customLists) {
                val shown = CustomListFilterBuilder.applyClientSideFilters(
                    withBlank, filterOf(vector), date(vector.today), zone,
                )
                assertEquals(vector.expect, shown.map { it.id }, "${vector.name} with blank due dates in $zone")
            }
        }
    }

    @Test
    fun `a list written by this app keeps the meaning of every vector`() {
        // toWire writes include_overdue = true as an absent key; the meaning must not change.
        val zone = zones.first()
        val all = tasks(zone)
        for (vector in fixture.customLists) {
            val original = CustomListWire(id = "v", name = "v", filter = vector.filter).toDomain()

            val roundTripped = original.toWire().toDomain()

            val shown = CustomListFilterBuilder.applyClientSideFilters(all, roundTripped.filter, date(vector.today), zone)
            assertEquals(vector.expect, shown.map { it.id }, vector.name)
        }
    }

    // --- customListActivity (section 3.3) -----------------------------------------------------

    private val activity get() = fixture.customListActivity

    /** The activity tasks as the app holds them, with their signals beside them. */
    private fun activityTasks(zone: TimeZone): List<Task> = activity.tasks.map {
        Task(
            id = it.id,
            title = "Task ${it.id}",
            done = it.done,
            projectId = it.projectId,
            dueDate = it.due?.let { due -> local(due, zone).toString() } ?: Constants.NULL_DATE_STRING,
            updated = local(it.updated, zone).toString(),
            assignees = it.assignees.map { id -> User(id = id, username = "user$id", name = "") },
        )
    }

    private fun contextOf(vector: CrossAppFixture.ActivityVector) = CustomListContext(
        me = if (vector.signedOut) null else activity.me,
        sharedProjectIds = activity.shared.toSet(),
        signals = activity.tasks.associate { it.id to TaskSignal(it.commentCount, it.unread) },
    )

    private fun filterOf(vector: CrossAppFixture.ActivityVector) =
        CustomListWire(id = "vector", name = vector.name, filter = vector.filter).toDomain().filter

    @Test
    fun `the fixture has the activity vectors`() {
        assertTrue(activity.vectors.size >= 20, "found ${activity.vectors.size} vectors")
    }

    @Test
    fun `every customListActivity vector in every zone at every time of day`() {
        val scope = CoroutineScope(Job())
        for (zone in zones) {
            val all = activityTasks(zone)
            for (vector in activity.vectors) for (clock in clocks) {
                val now = local("${vector.today}T$clock", zone)
                val day = DayClock(scope, FixedTimeSource(now, zone), ticking = false).day.value
                val filter = filterOf(vector)
                val context = contextOf(vector)

                val shown = CustomListFilterBuilder.applyClientSideFilters(all, filter, day.date, day.zone, context)
                assertEquals(vector.expect, shown.map { it.id }, "${vector.name} at $clock in $zone")

                val serverFilter = CustomListFilterBuilder.buildFilterString(filter, day.date, day.zone)
                for (task in shown) {
                    assertTrue(
                        ServerFilterEval.matches(serverFilter, ServerFilterEval.Row(task.done, task.projectId, task.dueDate, task.updated)),
                        "task ${task.id} of ${vector.name} at $clock in $zone is dropped by: $serverFilter",
                    )
                }
                // Without the client-only conditions the "changed" clause is exact.
                if (!filter.hasCollaborationCondition && filter.projectIds.isEmpty() && filter.dueDateFilter == "all") {
                    val fromServer = all.filter {
                        ServerFilterEval.matches(serverFilter, ServerFilterEval.Row(it.done, it.projectId, it.dueDate, it.updated))
                    }
                    assertEquals(vector.expect, fromServer.map { it.id }, "server: ${vector.name} at $clock in $zone")
                }
            }
        }
        scope.cancel()
    }

    @Test
    fun `a list written by this app keeps the meaning of every activity vector`() {
        val zone = zones.first()
        val all = activityTasks(zone)
        for (vector in activity.vectors) {
            val roundTripped = CustomListWire(id = "v", name = "v", filter = vector.filter).toDomain().toWire().toDomain()
            val shown = CustomListFilterBuilder.applyClientSideFilters(
                all, roundTripped.filter, date(vector.today), zone, contextOf(vector),
            )
            assertEquals(vector.expect, shown.map { it.id }, vector.name)
        }
    }
}
