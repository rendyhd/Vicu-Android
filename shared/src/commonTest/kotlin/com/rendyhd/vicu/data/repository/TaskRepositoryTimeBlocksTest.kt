package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PlannerTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The repository is where every due date is written, so the 16.3 rule runs there: a new due date moves
 * or removes a block on its day in the same patch and on the local row, and queues like any change.
 */
class TaskRepositoryTimeBlocksTest {

    private val zone = TimeZone.of("Pacific/Auckland")
    private val today = LocalDate(2026, 10, 6)
    private val tomorrow = LocalDate(2026, 10, 7)
    private val clock = DayClock(
        CoroutineScope(Job()),
        FixedTimeSource(LocalDateTime(2026, 10, 6, 8, 0).toInstant(zone), zone),
        ticking = false,
    )

    private fun at(local: String): String = LocalDateTime.parse(local).toInstant(zone).toString()
    private fun dateOnly(date: LocalDate): String = DueDates.dateOnlyDue(date, zone).toString()

    private val inTheBlock = TaskEntity(
        id = 42,
        title = "Write",
        projectId = 1,
        dueDate = dateOnly(today),
        startDate = at("2026-10-06T09:00:00"),
        endDate = at("2026-10-06T09:30:00"),
    )

    private fun harness(online: Boolean = true, row: TaskEntity = inTheBlock) =
        TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(row)), dayClock = clock) {
            if (online) jsonOk("""{"id":42,"title":"Write","project_id":1}""") else serviceUnavailable()
        }

    private fun TaskRepositoryHarness.patch() = assertNotNull(sent.single { it.method == "PATCH" }.bodyJson)

    @Test
    fun `a new due date moves a block on the due day in the same patch`() = runTest {
        val h = harness()
        val task = with(h.mapper) { inTheBlock.toDomain() }

        h.repository.update(task.copy(dueDate = dateOnly(tomorrow)))

        val patch = h.patch()
        assertEquals(setOf("due_date", "start_date", "end_date"), patch.keys)
        assertEquals(JsonPrimitive(at("2026-10-07T09:00:00")), patch["start_date"])
        assertEquals(JsonPrimitive(at("2026-10-07T09:30:00")), patch["end_date"])
    }

    @Test
    fun `clearing the due date removes a block on its day`() = runTest {
        val h = harness()
        val task = with(h.mapper) { inTheBlock.toDomain() }

        h.repository.update(task.copy(dueDate = ""))

        val patch = h.patch()
        assertEquals(JsonNull, patch["due_date"])
        assertEquals(JsonNull, patch["start_date"])
        assertEquals(JsonNull, patch["end_date"])
    }

    @Test
    fun `removing a block also removes the reminders that followed it`() = runTest {
        val row = inTheBlock.copy(
            remindersJson = """[{"reminder":"","relative_period":-600,"relative_to":"start_date"},""" +
                """{"reminder":"","relative_period":0,"relative_to":"end_date"},""" +
                """{"reminder":"","relative_period":-3600,"relative_to":"due_date"}]""",
        )
        val h = harness(online = false, row = row)
        val task = with(h.mapper) { row.toDomain() }
        assertEquals(3, task.reminders.size)

        h.repository.update(task.copy(dueDate = ""))

        val stored = with(h.mapper) { assertNotNull(h.taskDao.entity(42)).toDomain() }
        assertEquals(listOf(TaskReminder(relativePeriod = -3600, relativeTo = "due_date")), stored.reminders)
        assertTrue(h.pendingActionDao.snapshot().single().payload.contains("reminders"))
    }

    @Test
    fun `a block on another day than the due date stays where it is`() = runTest {
        val row = inTheBlock.copy(dueDate = dateOnly(LocalDate(2026, 10, 9)))
        val h = harness(row = row)
        val task = with(h.mapper) { row.toDomain() }

        h.repository.update(task.copy(dueDate = dateOnly(LocalDate(2026, 10, 10))))

        assertEquals(setOf("due_date"), h.patch().keys)
    }

    @Test
    fun `a write that sets the block itself is left as written`() = runTest {
        val h = harness()
        val task = with(h.mapper) { inTheBlock.toDomain() }
        val start = at("2026-10-07T11:00:00")
        val end = at("2026-10-07T11:45:00")

        h.repository.update(task.copy(dueDate = dateOnly(tomorrow), startDate = start, endDate = end))

        val patch = h.patch()
        assertEquals(JsonPrimitive(start), patch["start_date"])
        assertEquals(JsonPrimitive(end), patch["end_date"])
    }

    @Test
    fun `an edit that leaves the due date alone sends only what changed`() = runTest {
        val h = harness()
        val task = with(h.mapper) { inTheBlock.toDomain() }

        h.repository.update(task.copy(title = "Write more"))

        assertEquals(setOf("title"), h.patch().keys)
    }

    @Test
    fun `offline the moved block is on the row and queued as a merge patch of what changed`() = runTest {
        val h = harness(online = false)
        val task = with(h.mapper) { inTheBlock.toDomain() }

        val result = h.repository.update(task.copy(dueDate = dateOnly(tomorrow)))

        assertIs<NetworkResult.Success<*>>(result)
        val stored = assertNotNull(h.taskDao.entity(42))
        assertEquals(tomorrow, DueDates.localDateOf(stored.startDate, zone))
        assertEquals(tomorrow, DueDates.localDateOf(stored.endDate, zone))
        val queued = h.pendingActionDao.snapshot().single()
        assertTrue(queued.payload.contains("start_date"), queued.payload)
        assertFalse(queued.payload.contains("title"), "only what changed: ${queued.payload}")
    }

    @Test
    fun `today holds a block starting today but not a start date alone or several days`() = runTest {
        val dao = FakeTaskDao().apply {
            todayRows = listOf(
                TaskEntity(id = 1, title = "Block", projectId = 1, startDate = at("2026-10-06T09:00:00"), endDate = at("2026-10-06T10:00:00")),
                TaskEntity(id = 2, title = "Start only", projectId = 1, startDate = at("2026-10-06T09:00:00")),
                TaskEntity(id = 3, title = "Days", projectId = 1, startDate = at("2026-10-06T09:00:00"), endDate = at("2026-10-08T10:00:00")),
                TaskEntity(id = 4, title = "Due", projectId = 1, dueDate = dateOnly(today)),
            )
        }
        val h = TaskRepositoryHarness(taskDao = dao, dayClock = clock) { jsonOk("{}") }

        assertEquals(listOf(1L, 4L), h.repository.getTodayTasks().first().map { it.id })
    }

    @Test
    fun `the plan query covers the local days and asks for overdue only with today on it`() = runTest {
        val dao = FakeTaskDao()
        val h = TaskRepositoryHarness(taskDao = dao, dayClock = clock) { jsonOk("{}") }

        h.repository.getPlanTasks(today, LocalDate(2026, 10, 12)).first()
        h.repository.getPlanTasks(tomorrow, tomorrow).first()

        assertEquals(
            listOf(
                PlanQuery(at("2026-10-06T00:00:00"), at("2026-10-13T00:00:00"), at("2026-10-06T00:00:00"), true),
                PlanQuery(at("2026-10-07T00:00:00"), at("2026-10-08T00:00:00"), at("2026-10-06T00:00:00"), false),
            ),
            dao.planQueries,
        )
    }

    @Test
    fun `a day's request is one filter and brings its tasks into the cache`() = runTest {
        val filters = mutableListOf<String?>()
        val dao = FakeTaskDao()
        val h = TaskRepositoryHarness(taskDao = dao, dayClock = clock) { request ->
            filters += request.url.parameters["filter"]
            jsonOk("""{"items":[{"id":7,"title":"Done block","project_id":1,"done":true}],"total":1,"page":1,"per_page":100,"total_pages":1}""")
        }

        assertIs<NetworkResult.Success<*>>(h.repository.refreshPlanDay(tomorrow))

        assertEquals(listOf<String?>(PlannerTimeline.planRangeFilter(listOf(tomorrow), today, zone)), filters)
        assertTrue(assertNotNull(dao.entity(7)).done)
    }
}
