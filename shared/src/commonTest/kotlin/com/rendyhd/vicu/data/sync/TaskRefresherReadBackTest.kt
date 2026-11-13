package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.entity.TaskEntity
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A task a refresh skipped while an assignee or relation change of it waited in the queue is read
 * back once that change has been sent (review C-7): the cursor moved on meanwhile, so a rename
 * made elsewhere would otherwise not show until the next full reconcile.
 */
class TaskRefresherReadBackTest {

    private fun row(id: Long, title: String, dueDate: String = "") =
        TaskEntity(id = id, title = title, projectId = 7, dueDate = dueDate, updated = "2026-10-01T00:00:00Z")

    @Test
    fun `a task read back takes the server's title and date and its reminder follows`() = runTest {
        val h = RefresherHarness()
        h.taskDao.upsert(row(5, "Old title", dueDate = "2026-10-08T21:59:59Z"))
        h.server.put(5, title = "Renamed elsewhere", dueDate = "2026-10-09T21:59:59Z")

        assertTrue(h.refresher.refreshTasks(listOf(5)))

        val stored = h.taskDao.entity(5)!!
        assertEquals("Renamed elsewhere", stored.title)
        assertEquals("2026-10-09T21:59:59Z", stored.dueDate)
        assertEquals(listOf(5L), h.hooks.scheduled, "the reminder follows the new date")
    }

    @Test
    fun `a task with another change still queued is not overwritten`() = runTest {
        val h = RefresherHarness()
        h.taskDao.upsert(row(5, "Edited here"))
        h.server.put(5, title = "Server title")
        h.queueTaskAction(5)

        h.refresher.refreshTasks(listOf(5))

        assertEquals("Edited here", h.taskDao.entity(5)!!.title)
    }

    @Test
    fun `a task deleted on the server leaves the cache, and an unreadable one says so`() = runTest {
        val h = RefresherHarness()
        h.taskDao.upsert(row(5, "Gone"))

        assertTrue(h.refresher.refreshTasks(listOf(5)))
        assertNull(h.taskDao.entity(5))

        h.taskDao.upsert(row(6, "Unreachable"))
        h.server.override = { req ->
            if (req.path == "/tasks/6") respond("", HttpStatusCode.ServiceUnavailable) else null
        }
        assertFalse(h.refresher.refreshTasks(listOf(6)))
        assertEquals("Unreachable", h.taskDao.entity(6)!!.title)
    }
}
