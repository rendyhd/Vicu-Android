package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.CrossAppFixture.date
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.CrossAppFixture.zones
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The custom-list evaluator and the server filter built from it (docs/cross-app-semantics-v1.md,
 * sections 2 and 3). The shared vectors are in [CustomListFixtureTest]; these cover the edges the
 * vectors cannot, such as the UTC date of an instant differing from its local date.
 */
class CustomListFilterBuilderTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")
    private val newYork = TimeZone.of("America/New_York")
    private val auckland = TimeZone.of("Pacific/Auckland")
    private val tue = date("2026-10-06")

    private fun task(
        id: Long,
        due: String?,
        zone: TimeZone = amsterdam,
        projectId: Long = 10,
        done: Boolean = false,
        priority: Int = 0,
        labels: List<Long> = emptyList(),
    ) = Task(
        id = id,
        title = "Task $id",
        done = done,
        projectId = projectId,
        priority = priority,
        dueDate = due?.let { local(it, zone).toString() } ?: "",
        labels = labels.map { Label(id = it, title = "Label $it") },
    )

    private fun ids(tasks: List<Task>, filter: CustomListFilter, today: LocalDate = tue, zone: TimeZone = amsterdam) =
        CustomListFilterBuilder.applyClientSideFilters(tasks, filter, today, zone).map { it.id }

    // --- Date windows -------------------------------------------------------------------

    @Test
    fun `a window follows the local date of the due instant, not its UTC date`() {
        // 00:30 on Oct 7 in Amsterdam is still Oct 6 in UTC; 18:30 on Oct 6 in New York is Oct 7 in UTC.
        val amsterdamTask = task(1, "2026-10-07T00:30:00", amsterdam)
        val newYorkTask = task(2, "2026-10-06T18:30:00", newYork)
        val today = CustomListFilter(dueDateFilter = "today", includeOverdue = false)

        assertEquals(emptyList(), ids(listOf(amsterdamTask), today, tue, amsterdam), "Oct 7 locally")
        assertEquals(listOf(2L), ids(listOf(newYorkTask), today, tue, newYork), "Oct 6 locally")
        assertEquals(listOf(1L), ids(listOf(amsterdamTask), today, date("2026-10-07"), amsterdam))
    }

    @Test
    fun `a task due at local midnight belongs to that day, a legacy date-only value`() {
        val midnight = task(1, "2026-10-07T00:00:00")
        val endOfDay = task(2, "2026-10-06T23:59:59")
        val today = CustomListFilter(dueDateFilter = "today")

        assertEquals(listOf(2L), ids(listOf(midnight, endOfDay), today))
        assertEquals(listOf(1L, 2L), ids(listOf(midnight, endOfDay), today, date("2026-10-07")))
    }

    @Test
    fun `a task due earlier today is still today at ten, and overdue tomorrow`() {
        val early = task(1, "2026-10-06T08:00:00")

        assertEquals(listOf(1L), ids(listOf(early), CustomListFilter(dueDateFilter = "today", includeOverdue = false)))
        assertEquals(emptyList(), ids(listOf(early), CustomListFilter(dueDateFilter = "today", includeOverdue = false), date("2026-10-07")))
        assertEquals(listOf(1L), ids(listOf(early), CustomListFilter(dueDateFilter = "overdue"), date("2026-10-07")))
        assertEquals(emptyList(), ids(listOf(early), CustomListFilter(dueDateFilter = "overdue")))
    }

    @Test
    fun `this week ends on Sunday, and on a Sunday it is only that day`() {
        val sat = task(1, "2026-10-10T23:59:59")
        val sun = task(2, "2026-10-11T23:59:59")
        val mon = task(3, "2026-10-12T23:59:59")
        val week = CustomListFilter(dueDateFilter = "this_week", includeOverdue = false)

        assertEquals(listOf(1L, 2L), ids(listOf(sat, sun, mon), week))
        assertEquals(listOf(2L), ids(listOf(sat, sun, mon), week, date("2026-10-11")))
        assertEquals(listOf(3L), ids(listOf(sat, sun, mon), week, date("2026-10-12")))
    }

    @Test
    fun `include_overdue defaults to true and only the today, week and month windows read it`() {
        val overdue = task(1, "2026-10-01T23:59:59")
        val noDate = task(2, null)

        for (window in listOf("today", "this_week", "this_month")) {
            assertEquals(listOf(1L), ids(listOf(overdue), CustomListFilter(dueDateFilter = window)), "$window, absent")
            assertEquals(listOf(1L), ids(listOf(overdue), CustomListFilter(dueDateFilter = window, includeOverdue = true)), "$window, true")
            assertEquals(emptyList(), ids(listOf(overdue), CustomListFilter(dueDateFilter = window, includeOverdue = false)), "$window, false")
        }
        for (window in listOf("overdue", "has_due_date", "all")) {
            assertEquals(listOf(1L), ids(listOf(overdue), CustomListFilter(dueDateFilter = window, includeOverdue = false)), "$window ignores it")
        }
        assertEquals(listOf(2L), ids(listOf(overdue, noDate), CustomListFilter(dueDateFilter = "no_due_date", includeOverdue = false)))
        // A task without a due date is in none of the date windows, overdue or not.
        for (window in listOf("today", "this_week", "this_month", "overdue", "has_due_date")) {
            assertEquals(emptyList(), ids(listOf(noDate), CustomListFilter(dueDateFilter = window)), "$window")
        }
    }

    @Test
    fun `an unknown window from a newer app means no date condition`() {
        val tasks = listOf(task(1, "2026-10-01T23:59:59"), task(2, null), task(3, "2027-01-01T23:59:59"))

        assertEquals(listOf(1L, 2L, 3L), ids(tasks, CustomListFilter(dueDateFilter = "next_quarter")))
    }

    @Test
    fun `completed tasks are only kept when the list includes them`() {
        val open = task(1, "2026-10-06T23:59:59")
        val done = task(2, "2026-10-06T23:59:59", done = true)

        assertEquals(listOf(1L), ids(listOf(open, done), CustomListFilter(dueDateFilter = "today")))
        assertEquals(listOf(1L, 2L), ids(listOf(open, done), CustomListFilter(dueDateFilter = "today", includeDone = true)))
    }

    // --- Projects, priority and labels ----------------------------------------------------

    @Test
    fun `today from all projects widens the project rule in include and in exclude mode`() {
        val dueToday = task(1, "2026-10-06T12:00:00", projectId = 11)
        val overdueOther = task(2, "2026-10-05T12:00:00", projectId = 11)
        val tomorrowOther = task(3, "2026-10-07T12:00:00", projectId = 11)
        val tomorrowListed = task(4, "2026-10-07T12:00:00", projectId = 10)
        val all = listOf(dueToday, overdueOther, tomorrowOther, tomorrowListed)

        val include = CustomListFilter(projectIds = listOf(10), includeTodayAllProjects = true)
        assertEquals(listOf(1L, 2L, 4L), ids(all, include), "include: listed project, or today (overdue counts)")

        val includeNoOverdue = include.copy(includeOverdue = false)
        assertEquals(listOf(1L, 4L), ids(all, includeNoOverdue))

        val exclude = CustomListFilter(projectIds = listOf(11), projectFilterMode = "exclude", includeTodayAllProjects = true)
        assertEquals(listOf(1L, 2L, 4L), ids(all, exclude), "exclude: not the excluded project, or today")
    }

    @Test
    fun `the date window still applies to tasks that only qualified through today from all projects`() {
        val dueToday = task(1, "2026-10-06T12:00:00", projectId = 11)
        val listedLater = task(2, "2026-10-20T12:00:00", projectId = 10)
        val filter = CustomListFilter(
            projectIds = listOf(10),
            includeTodayAllProjects = true,
            dueDateFilter = "this_week",
        )

        assertEquals(listOf(1L), ids(listOf(dueToday, listedLater), filter))
    }

    @Test
    fun `today from all projects does nothing without listed projects`() {
        val other = task(1, "2026-10-20T12:00:00", projectId = 11)

        assertEquals(listOf(1L), ids(listOf(other), CustomListFilter(includeTodayAllProjects = true)))
    }

    @Test
    fun `priority is any of the listed values and labels are any of the listed labels`() {
        val tasks = listOf(
            task(1, null, priority = 1, labels = listOf(5)),
            task(2, null, priority = 3, labels = listOf(6, 7)),
            task(3, null, priority = 4),
        )

        assertEquals(listOf(2L, 3L), ids(tasks, CustomListFilter(priorityFilter = listOf(3, 4))))
        assertEquals(listOf(1L, 2L), ids(tasks, CustomListFilter(labelIds = listOf(5, 7))))
        assertEquals(listOf(2L), ids(tasks, CustomListFilter(priorityFilter = listOf(3, 4), labelIds = listOf(5, 7))))
    }

    // --- Server filter --------------------------------------------------------------------

    private fun serverFilter(filter: CustomListFilter, today: LocalDate = tue, zone: TimeZone = amsterdam) =
        CustomListFilterBuilder.buildFilterString(filter, today, zone)

    private val nullDate = "due_date != '0001-01-01T00:00:00Z'"

    @Test
    fun `the today window ends at the start of the next local day`() {
        // Amsterdam is UTC+2 in October: local midnight is 22:00 UTC the evening before.
        assertEquals(
            "done = false && due_date < '2026-10-06T22:00:00Z' && $nullDate",
            serverFilter(CustomListFilter(dueDateFilter = "today")),
        )
        assertEquals(
            "done = false && due_date >= '2026-10-05T22:00:00Z' && due_date < '2026-10-06T22:00:00Z'",
            serverFilter(CustomListFilter(dueDateFilter = "today", includeOverdue = false)),
        )
    }

    @Test
    fun `the day boundaries follow the zone, not UTC`() {
        val today = CustomListFilter(dueDateFilter = "today", includeOverdue = false)

        assertEquals(
            "done = false && due_date >= '2026-10-06T04:00:00Z' && due_date < '2026-10-07T04:00:00Z'",
            serverFilter(today, zone = newYork),
        )
        assertEquals(
            "done = false && due_date >= '2026-10-05T11:00:00Z' && due_date < '2026-10-06T11:00:00Z'",
            serverFilter(today, zone = auckland),
        )
    }

    @Test
    fun `week and month windows end before the day after their last local day`() {
        // Sunday Oct 11 -> before Monday Oct 12 00:00 local (22:00 UTC the day before).
        assertEquals(
            "done = false && due_date < '2026-10-11T22:00:00Z' && $nullDate",
            serverFilter(CustomListFilter(dueDateFilter = "this_week")),
        )
        // Oct 31 -> before Nov 1 00:00 local; Amsterdam is back on UTC+1 by then.
        assertEquals(
            "done = false && due_date < '2026-10-31T23:00:00Z' && $nullDate",
            serverFilter(CustomListFilter(dueDateFilter = "this_month")),
        )
        assertEquals(
            "done = false && due_date >= '2026-10-05T22:00:00Z' && due_date < '2026-10-31T23:00:00Z'",
            serverFilter(CustomListFilter(dueDateFilter = "this_month", includeOverdue = false)),
        )
    }

    @Test
    fun `overdue, has a due date and no due date`() {
        assertEquals("done = false && due_date < '2026-10-05T22:00:00Z' && $nullDate", serverFilter(CustomListFilter(dueDateFilter = "overdue")))
        assertEquals("done = false && $nullDate", serverFilter(CustomListFilter(dueDateFilter = "has_due_date")))
        assertEquals("done = false && due_date = '0001-01-01T00:00:00Z'", serverFilter(CustomListFilter(dueDateFilter = "no_due_date")))
        assertEquals("done = false", serverFilter(CustomListFilter(dueDateFilter = "all")))
        assertEquals("", serverFilter(CustomListFilter(includeDone = true)))
    }

    @Test
    fun `an include project is sent to the server, an exclude one is not`() {
        assertEquals("done = false && project_id = 10", serverFilter(CustomListFilter(projectIds = listOf(10))))
        assertEquals(
            "done = false && (project_id = 10 || project_id = 11)",
            serverFilter(CustomListFilter(projectIds = listOf(10, 11))),
        )
        assertEquals(
            "done = false",
            serverFilter(CustomListFilter(projectIds = listOf(10), projectFilterMode = "exclude")),
        )
    }

    @Test
    fun `today from all projects widens the server project clause with the today window`() {
        assertEquals(
            "done = false && (project_id = 10 || (due_date < '2026-10-06T22:00:00Z' && $nullDate))",
            serverFilter(CustomListFilter(projectIds = listOf(10), includeTodayAllProjects = true)),
        )
        assertEquals(
            "done = false && (project_id = 10 || (due_date >= '2026-10-05T22:00:00Z' && due_date < '2026-10-06T22:00:00Z')) && " +
                "due_date >= '2026-10-05T22:00:00Z' && due_date < '2026-10-11T22:00:00Z'",
            serverFilter(
                CustomListFilter(
                    projectIds = listOf(10),
                    includeTodayAllProjects = true,
                    dueDateFilter = "this_week",
                    includeOverdue = false,
                ),
            ),
        )
    }

    @Test
    fun `the query carries the filter and the sort`() {
        val params = CustomListFilterBuilder.buildQueryParams(
            CustomListFilter(dueDateFilter = "today", sortBy = "priority", orderBy = "desc"),
            tue,
            amsterdam,
        )

        assertEquals("done = false && due_date < '2026-10-06T22:00:00Z' && $nullDate", params["filter"])
        assertEquals("priority", params["sort_by"])
        assertEquals("desc", params["order_by"])
        assertFalse("filter" in CustomListFilterBuilder.buildQueryParams(CustomListFilter(includeDone = true), tue, amsterdam))
    }

    // --- Server filter is a superset of the evaluator ---------------------------------------

    /** Due instants around every local midnight in a range: just before, at, and just after it. */
    private fun boundaryTasks(zone: TimeZone, first: LocalDate, days: Int): List<Task> {
        val result = mutableListOf<Task>()
        var id = 1L
        for (offset in 0 until days) {
            val day = first.plus(offset, DateTimeUnit.DAY)
            val midnight = DueDates.startOfDay(day, zone)
            val instants = listOf<Instant>(
                midnight - 1.milliseconds,
                midnight,
                midnight + 12.hours,
                DueDates.dateOnlyDue(day, zone),
            )
            for (instant in instants) for (project in listOf(10L, 11L)) for (done in listOf(false, true)) {
                result += Task(id = id++, title = "t", projectId = project, done = done, dueDate = instant.toString())
            }
        }
        result += Task(id = id++, title = "none", projectId = 10, dueDate = Constants.NULL_DATE_STRING)
        result += Task(id = id++, title = "blank", projectId = 11, dueDate = "")
        return result
    }

    private val windows = listOf("all", "overdue", "today", "this_week", "this_month", "has_due_date", "no_due_date")

    private val projectRules = listOf(
        "none" to CustomListFilter(),
        "include 10" to CustomListFilter(projectIds = listOf(10)),
        "include 10, 11" to CustomListFilter(projectIds = listOf(10, 11)),
        "exclude 10" to CustomListFilter(projectIds = listOf(10), projectFilterMode = "exclude"),
        "include 10 + today" to CustomListFilter(projectIds = listOf(10), includeTodayAllProjects = true),
        "exclude 10 + today" to CustomListFilter(
            projectIds = listOf(10),
            projectFilterMode = "exclude",
            includeTodayAllProjects = true,
        ),
    )

    @Test
    fun `the server filter never drops a task the evaluator accepts, and is exact for plain windows`() {
        val todays = listOf("2026-10-06", "2026-10-11", "2026-10-31", "2026-11-01").map(::date)
        for (zone in zones) {
            val tasks = boundaryTasks(zone, date("2026-09-20"), days = 60)
            val rows = tasks.map { ServerFilterEval.Row(it.done, it.projectId, it.dueDate) }
            for (today in todays) for (window in windows) for ((rule, base) in projectRules) {
                for (includeOverdue in listOf(null, false)) for (includeDone in listOf(false, true)) {
                    val filter = base.copy(dueDateFilter = window, includeOverdue = includeOverdue, includeDone = includeDone)
                    val server = ServerFilterEval.compile(CustomListFilterBuilder.buildFilterString(filter, today, zone))
                    val label = "$window, $rule, overdue=$includeOverdue, done=$includeDone on $today in $zone"

                    for ((index, task) in tasks.withIndex()) {
                        val accepted = CustomListFilterBuilder.matches(task, filter, today, zone)
                        val sent = server(rows[index])
                        if (accepted) assertTrue(sent, "dropped task ${task.id} due ${task.dueDate} ($label)")
                        // A window-only list has no project rule to widen: the server sends exactly the window.
                        if (rule == "none") assertEquals(accepted, sent, "task ${task.id} due ${task.dueDate} ($label)")
                    }
                }
            }
        }
    }
}
