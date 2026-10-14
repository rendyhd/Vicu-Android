package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.entity.TaskEntity
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Frequent refreshes fetch only what changed since the last one; a full reconcile (the only
 * thing that can notice a deletion) runs on request, on first use and at most once a day.
 */
class TaskRefresherTest {

    private val carrierDescription = "<!-- vicu-routine:v1:e30 -->"
    private val archiveDescription = "<!-- vicu-routine:archive:v1:e30 -->"

    private fun harness(configure: TaskListServer.() -> Unit = {}): RefresherHarness {
        val server = TaskListServer().apply {
            put(1, "A", updated = "2026-10-06T08:00:00Z")
            put(2, "B", updated = "2026-10-06T09:00:00Z")
            put(3, "C", done = true, updated = "2026-10-05T10:00:00Z")
            put(4, "Vitamin D", done = true, updated = "2026-10-01T00:00:00Z", description = carrierDescription)
            configure()
        }
        return RefresherHarness(server)
    }

    private suspend fun RefresherHarness.localIds() = taskDao.snapshot().map { it.id }.sorted()

    // ---- the first refresh is a full reconcile -----------------------------------------------

    @Test
    fun `the first refresh reconciles the open tasks and does not download completed history`() = runTest {
        val h = harness()

        val outcome = h.refresher.refresh()

        assertTrue(outcome.full)
        assertEquals(listOf(1L, 2L, 4L), h.localIds(), "open tasks and the routine carrier, not the completed task 3")
        val filters = h.server.lists().map { it.filter }
        assertTrue("done = false" in filters)
        assertTrue(
            h.server.lists().none { it.filter == "done = true" && it.query == null },
            "no unfiltered scan of completed tasks: ${h.server.lists()}",
        )
        assertTrue(h.server.lists().any { it.query == "vicu-routine" && it.filter == "done = true" })
    }

    @Test
    fun `the first refresh keeps the newest change anywhere as the cursor`() = runTest {
        val h = harness { put(3, "C", done = true, updated = "2026-10-06T11:30:00Z") }

        h.refresher.refresh()

        // Task 3 is completed, so it is not downloaded, but it is the newest change on the server.
        assertEquals("2026-10-06T11:30:00Z", h.cursorStore.begin().cursor.tasksUpdatedSince)
    }

    @Test
    fun `refresh requests are flat so a filter applies to every task`() = runTest {
        val h = harness()

        h.refresher.refresh()
        h.refresher.refresh()

        assertTrue(h.server.lists().none { "expand" in it.params }, "expand=subtasks hides subtasks of tasks that do not match")
    }

    // ---- incremental ---------------------------------------------------------------------------

    @Test
    fun `the next refresh asks only for tasks updated since the cursor`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.requests.clear()
        h.server.put(1, "A edited", updated = "2026-10-06T10:00:00Z")
        h.server.put(3, "C reopened elsewhere and edited", done = true, updated = "2026-10-06T10:30:00Z")

        val outcome = h.refresher.refresh()

        assertFalse(outcome.full)
        // The cursor was 09:00:00; five seconds of overlap cover a write that raced the last fetch.
        // After it, a page of one open task tells whether anything was deleted (its total).
        assertEquals(listOf("updated >= '2026-10-06T08:59:55Z'", "done = false"), h.server.lists().map { it.filter })
        assertEquals("1", h.server.lists().last().param("per_page"))
        assertEquals(2, h.server.requests.size, "the count probe and the delta, no label or carrier queries")
        assertEquals("A edited", h.taskDao.entity(1)!!.title)
        assertEquals(true, h.taskDao.entity(3)!!.done, "a task completed elsewhere arrives as completed")
        assertEquals("2026-10-06T10:30:00Z", h.cursorStore.begin().cursor.tasksUpdatedSince)
    }

    @Test
    fun `only rows that changed are written`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.taskDao.upsertedIds.clear()
        h.server.put(1, "A edited", updated = "2026-10-06T10:00:00Z")

        h.refresher.refresh()

        // Task 2 is returned again by the overlap but is identical to the cached row.
        assertEquals(listOf(1L), h.taskDao.upsertedIds)
    }

    @Test
    fun `an incremental refresh does not list anything or delete while the open counts agree`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.requests.clear()
        h.server.put(1, "A edited", updated = "2026-10-06T10:00:00Z")

        val outcome = h.refresher.refresh()

        assertFalse(outcome.full)
        assertEquals(0, outcome.removed)
        assertEquals(listOf(1L, 2L, 4L), h.localIds())
        assertTrue(h.server.lists().none { it.filter == "done = false" && it.param("per_page") != "1" }, "no listing of the open tasks")
    }

    // ---- deletions on the server are noticed by the open count ------------------------------------

    @Test
    fun `a task deleted on the server leaves the cache at the next refresh, not a day later`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.remove(2)
        h.time.advance(5.minutes)

        val outcome = h.refresher.refresh()

        assertTrue(outcome.full, "the count differs, so this refresh reconciled")
        assertEquals(1, outcome.removed)
        assertNull(h.taskDao.entity(2), "no ghost row")
        assertEquals(listOf(1L, 4L), h.localIds())
    }

    @Test
    fun `after the reconcile the next refresh is incremental again`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.remove(2)
        h.refresher.refresh()

        assertFalse(h.refresher.refresh().full)
    }

    @Test
    fun `a task created or completed elsewhere arrives by the delta and does not trigger a reconcile`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.put(5, "New elsewhere", updated = "2026-10-06T12:30:00Z")
        h.server.put(2, "B done elsewhere", done = true, updated = "2026-10-06T12:31:00Z")

        val outcome = h.refresher.refresh()

        assertFalse(outcome.full, "the delta already moved the cached open count to the server's")
        assertNotNull(h.taskDao.entity(5))
        assertEquals(true, h.taskDao.entity(2)!!.done, "a completion elsewhere stays a completed row")
    }

    @Test
    fun `a standing difference the reconcile cannot remove does not make every refresh a reconcile`() = runTest {
        // A task with a queued change is kept although the server no longer lists it: the counts
        // differ by one for as long as the change waits, and the refresh must not loop on that.
        val h = harness()
        h.refresher.refresh()
        h.queueTaskAction(2)
        h.server.remove(2)
        assertTrue(h.refresher.refresh().full, "the first look at the difference reconciles")

        assertFalse(h.refresher.refresh().full)
        assertFalse(h.refresher.refresh().full)
        assertNotNull(h.taskDao.entity(2), "the queued change keeps its row")
    }

    @Test
    fun `a count probe that cannot be answered does not fail or reconcile the refresh`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.override = { req ->
            if (req.filter == "done = false") respond("", HttpStatusCode.ServiceUnavailable) else null
        }

        val outcome = h.refresher.refresh()

        assertFalse(outcome.full)
    }

    @Test
    fun `the cursor stays put when nothing changed`() = runTest {
        val h = harness()
        h.refresher.refresh()
        val before = h.cursorStore.begin().cursor.tasksUpdatedSince

        h.refresher.refresh()
        h.refresher.refresh()

        assertEquals(before, h.cursorStore.begin().cursor.tasksUpdatedSince)
    }

    @Test
    fun `a failed refresh keeps the cursor so the next one covers the gap`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.put(1, "A edited", updated = "2026-10-06T10:00:00Z")
        val cursor = h.cursorStore.begin().cursor.tasksUpdatedSince
        h.server.override = { respond("", HttpStatusCode.ServiceUnavailable) }

        val failure = runCatching { h.refresher.refresh() }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(cursor, h.cursorStore.begin().cursor.tasksUpdatedSince)
        h.server.override = { null }
        h.refresher.refresh()
        assertEquals("A edited", h.taskDao.entity(1)!!.title)
    }

    // ---- full reconcile scheduling -------------------------------------------------------------

    @Test
    fun `a full reconcile runs after a day and not before`() = runTest {
        val h = harness()
        h.refresher.refresh()

        h.time.advance(23.hours + 50.minutes)
        assertFalse(h.refresher.refresh().full)

        h.time.advance(11.minutes)
        assertTrue(h.refresher.refresh().full)
    }

    @Test
    fun `pull to refresh forces a full reconcile`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.remove(2)

        assertTrue(h.refresher.refresh(forceFull = true).full)

        assertNull(h.taskDao.entity(2))
    }

    @Test
    fun `a requested reconcile happens once and then refreshes are incremental again`() = runTest {
        val h = harness()
        h.refresher.refresh()

        h.refresher.requestFullReconcile()

        assertTrue(h.refresher.refresh().full)
        assertFalse(h.refresher.refresh().full)
    }

    @Test
    fun `a clock that moved back counts as due for a full reconcile`() = runTest {
        val h = harness()
        h.refresher.refresh()

        h.time.advance((-3).hours)

        assertTrue(h.refresher.refresh().full)
    }

    @Test
    fun `clearing the cursor makes the next refresh full and drops a refresh that was in flight`() = runTest {
        val h = harness()
        h.server.override = { req ->
            if (req.filter == "done = false") {
                // The account was wiped while this refresh was downloading.
                h.cursorStore.clear()
            }
            null
        }

        h.refresher.refresh()

        assertNull(h.cursorStore.begin().cursor.tasksUpdatedSince, "the stale run did not store a cursor")
        h.server.override = { null }
        assertTrue(h.refresher.refresh().full)
    }

    // ---- the full reconcile sweep ---------------------------------------------------------------

    @Test
    fun `the full reconcile removes open tasks the server no longer has`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = 99, title = "Deleted elsewhere", projectId = 7))

        h.refresher.refresh()

        assertEquals(listOf(1L, 2L, 4L), h.localIds())
    }

    @Test
    fun `tasks with queued changes survive a full reconcile and are not overwritten`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = 1, title = "Edited offline", projectId = 7))
        h.taskDao.upsert(TaskEntity(id = 99, title = "Gone but queued", projectId = 7))
        h.queueTaskAction(1)
        h.queueTaskAction(99, status = "failed")

        h.refresher.refresh()

        assertEquals("Edited offline", h.taskDao.entity(1)!!.title)
        assertNotNull(h.taskDao.entity(99))
    }

    @Test
    fun `tasks with queued changes are not overwritten by an incremental refresh either`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.taskDao.upsert(h.taskDao.entity(1)!!.copy(title = "Edited offline"))
        h.queueTaskAction(1)
        h.server.put(1, "Edited elsewhere", updated = "2026-10-06T10:00:00Z")

        h.refresher.refresh()

        assertEquals("Edited offline", h.taskDao.entity(1)!!.title)
    }

    @Test
    fun `an offline-created row without a queued create is removed by the full reconcile`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = -5, title = "Discarded create", projectId = 7))
        h.taskDao.upsert(TaskEntity(id = -6, title = "Still queued", projectId = 7))
        h.queueTaskAction(-6, type = "create")

        h.refresher.refresh()

        assertNull(h.taskDao.entity(-5))
        assertNotNull(h.taskDao.entity(-6))
    }

    @Test
    fun `completed tasks are not swept by the open-task reconcile`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = 3, title = "C", done = true, projectId = 7))

        h.refresher.refresh()

        assertNotNull(h.taskDao.entity(3), "history is managed by the Logbook paging")
    }

    // ---- routine carriers and archive parts ------------------------------------------------------

    @Test
    fun `a stale routine archive part cached by an older build is swept`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = 8, title = "Archive", description = archiveDescription, done = true, projectId = 7))

        h.refresher.refresh()

        assertNull(h.taskDao.entity(8))
    }

    @Test
    fun `routine archive parts and custom list carriers are never cached`() = runTest {
        val h = harness {
            put(7, "Archive", done = true, description = archiveDescription)
            put(9, "Lists", done = true, description = "<!-- vicu-custom-lists:v1:e30 -->")
        }

        h.refresher.refresh()

        assertNull(h.taskDao.entity(7))
        assertNull(h.taskDao.entity(9))
    }

    @Test
    fun `a cached routine carrier that was deleted on the server is removed`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = 50, title = "Old routine", description = carrierDescription, done = true, projectId = 7))

        h.refresher.refresh()

        assertNull(h.taskDao.entity(50), "the server says it does not exist")
        assertNotNull(h.taskDao.entity(4))
    }

    @Test
    fun `a cached carrier the search missed but the server still has is kept and updated`() = runTest {
        val h = harness {
            put(51, "Routine", done = true, description = "<!-- vicu-routine:v2:e30 -->", updated = "2026-10-02T00:00:00Z")
        }
        h.taskDao.upsert(TaskEntity(id = 51, title = "Stale", description = carrierDescription, done = true, projectId = 7))
        // The search does not find it (say the server does not index descriptions).
        h.server.override = { req ->
            if (req.query == "vicu-routine") {
                respond(
                    """{"items":[],"total":0,"page":1,"per_page":50,"total_pages":1}""",
                    HttpStatusCode.OK,
                    com.rendyhd.vicu.auth.authTestJsonHeaders,
                )
            } else {
                null
            }
        }

        val outcome = h.refresher.refresh()

        assertEquals("Routine", h.taskDao.entity(51)!!.title)
        assertFalse(outcome.carriersAuthoritative, "an empty search proves nothing about the carriers")
    }

    @Test
    fun `with no routines anywhere the full reconcile is authoritative about carriers`() = runTest {
        val h = harness { remove(4) }

        assertTrue(h.refresher.refresh().carriersAuthoritative)
    }

    @Test
    fun `carriers are authoritative only after a full reconcile that found them`() = runTest {
        val h = harness()

        assertTrue(h.refresher.refresh().carriersAuthoritative)
        assertFalse(h.refresher.refresh().carriersAuthoritative, "an incremental refresh says nothing about deletions")
    }

    // ---- reminders -----------------------------------------------------------------------------

    private val reminderJson = """[{"reminder":"2026-10-07T09:00:00Z","relative_period":0}]"""

    @Test
    fun `changing only a title reschedules no alarm`() = runTest {
        val h = harness { put(1, "A", updated = "2026-10-06T08:00:00Z", remindersJson = reminderJson, dueDate = "2026-10-07T09:00:00Z") }
        h.refresher.refresh()
        h.hooks.scheduled.clear()
        val widgets = h.hooks.widgetUpdates
        h.server.put(1, "A renamed", updated = "2026-10-06T10:00:00Z", remindersJson = reminderJson, dueDate = "2026-10-07T09:00:00Z")

        h.refresher.refresh()

        assertTrue(h.hooks.scheduled.isEmpty(), "title is read when the alarm fires")
        assertEquals(0, h.hooks.rescheduleAllCalls)
        assertEquals(widgets + 1, h.hooks.widgetUpdates, "but the widgets show the new title")
    }

    @Test
    fun `a moved due date reschedules that task only`() = runTest {
        val h = harness { put(1, "A", updated = "2026-10-06T08:00:00Z", remindersJson = reminderJson, dueDate = "2026-10-07T09:00:00Z") }
        h.refresher.refresh()
        h.hooks.scheduled.clear()
        h.server.put(1, "A", updated = "2026-10-06T10:00:00Z", remindersJson = reminderJson, dueDate = "2026-10-09T09:00:00Z")
        h.server.put(2, "B changed too", updated = "2026-10-06T10:01:00Z")

        h.refresher.refresh()

        assertEquals(listOf(1L), h.hooks.scheduled)
        assertEquals(0, h.hooks.rescheduleAllCalls, "no full pass over every task")
    }

    @Test
    fun `changed reminders and a completion elsewhere reschedule the task`() = runTest {
        val h = harness { put(1, "A", updated = "2026-10-06T08:00:00Z", remindersJson = reminderJson) }
        h.refresher.refresh()
        h.hooks.scheduled.clear()

        h.server.put(1, "A", updated = "2026-10-06T10:00:00Z", remindersJson = "[]")
        h.refresher.refresh()
        assertEquals(listOf(1L), h.hooks.scheduled, "reminder removed")

        h.hooks.scheduled.clear()
        h.server.put(1, "A", done = true, updated = "2026-10-06T11:00:00Z", remindersJson = "[]")
        h.refresher.refresh()
        assertEquals(listOf(1L), h.hooks.scheduled, "completed elsewhere: its alarms must go")
    }

    @Test
    fun `a new task schedules alarms only when it has reminders`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.hooks.scheduled.clear()
        h.server.put(10, "No reminder", updated = "2026-10-06T10:00:00Z")
        h.server.put(11, "With reminder", updated = "2026-10-06T10:01:00Z", remindersJson = reminderJson)

        h.refresher.refresh()

        assertEquals(listOf(11L), h.hooks.scheduled)
    }

    @Test
    fun `tasks swept by the full reconcile have their alarms cancelled`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = 99, title = "Gone", projectId = 7, remindersJson = reminderJson))

        h.refresher.refresh()

        assertEquals(listOf(99L), h.hooks.cancelled)
    }

    @Test
    fun `a large batch is one full pass instead of one call per task`() = runTest {
        val h = harness {
            for (id in 100L..260L) put(id, "T$id", updated = "2026-10-06T07:00:00Z", remindersJson = reminderJson)
        }

        h.refresher.refresh()

        assertEquals(1, h.hooks.rescheduleAllCalls)
        assertTrue(h.hooks.scheduled.isEmpty())
    }

    @Test
    fun `nothing changed means no alarm work and no widget update`() = runTest {
        val h = harness()
        h.refresher.refresh()
        val widgets = h.hooks.widgetUpdates
        val reschedules = h.hooks.rescheduleAllCalls

        h.refresher.refresh()

        assertEquals(widgets, h.hooks.widgetUpdates)
        assertEquals(reschedules, h.hooks.rescheduleAllCalls)
    }

    // ---- targeted refresh ------------------------------------------------------------------------

    @Test
    fun `a filtered fetch merges what matches and never deletes or moves the cursor`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = 99, title = "Local only", projectId = 7))

        h.refresher.refreshMatching(mapOf("q" to "vitamin"))

        assertNotNull(h.taskDao.entity(4))
        assertNotNull(h.taskDao.entity(99))
        assertNull(h.cursorStore.begin().cursor.tasksUpdatedSince)
    }

    // ---- change detection ---------------------------------------------------------------------------

    @Test
    fun `reminder relevance looks at reminders, dates and completion only`() {
        val base = TaskEntity(id = 1, title = "A", projectId = 7, dueDate = "2026-10-07T09:00:00Z", remindersJson = reminderJson)

        assertFalse(remindersAffected(base, base.copy(title = "B", priority = 3, description = "x")))
        assertTrue(remindersAffected(base, base.copy(dueDate = "2026-10-08T09:00:00Z")))
        assertTrue(remindersAffected(base, base.copy(startDate = "2026-10-08T09:00:00Z")))
        assertTrue(remindersAffected(base, base.copy(endDate = "2026-10-08T09:00:00Z")))
        assertTrue(remindersAffected(base, base.copy(remindersJson = "[]")))
        assertTrue(remindersAffected(base, base.copy(done = true)))
        assertTrue(remindersAffected(null, base))
        assertFalse(remindersAffected(null, base.copy(remindersJson = "[]")))
        assertFalse(remindersAffected(null, base.copy(done = true)))
    }
}
