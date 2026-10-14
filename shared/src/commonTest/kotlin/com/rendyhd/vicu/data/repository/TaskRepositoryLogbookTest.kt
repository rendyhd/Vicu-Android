package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.sync.Req
import com.rendyhd.vicu.data.sync.TaskListServer
import com.rendyhd.vicu.domain.repository.LogbookPage
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Logbook loads completed tasks a page at a time instead of downloading the history. */
class TaskRepositoryLogbookTest {

    private val server = TaskListServer()

    private fun harness(
        taskDao: FakeTaskDao = FakeTaskDao(),
        pendingActionDao: FakePendingActionDao = FakePendingActionDao(),
    ) = TaskRepositoryHarness(taskDao = taskDao, pendingActionDao = pendingActionDao) { request ->
        server.handle(this, request)
    }

    /** [count] completed tasks, id 1 the oldest, each finished an hour after the previous one. */
    private fun completed(count: Int) {
        for (i in 1..count) {
            val hour = (i % 24).toString().padStart(2, '0')
            val day = (1 + i / 24).toString().padStart(2, '0')
            server.put(i.toLong(), "Done $i", done = true, updated = "2026-09-${day}T${hour}:00:00Z")
        }
    }

    private fun Req.isCompletedQuery() = filter?.startsWith("done = true") == true

    @Test
    fun `the first page asks for completed tasks newest first and says there is more`() = runTest {
        completed(120)
        val h = harness()

        val result = h.repository.loadLogbookPage(1)

        assertEquals(NetworkResult.Success(LogbookPage(1, hasMore = true)), result)
        val request = server.lists().single()
        assertEquals("done = true", request.filter)
        assertEquals("done_at", request.param("sort_by"))
        assertEquals("desc", request.param("order_by"))
        assertEquals("50", request.param("per_page"))
        assertEquals("1", request.param("page"))
        assertEquals(50, h.taskDao.snapshot().size)
        assertTrue(h.taskDao.snapshot().all { it.done })
    }

    @Test
    fun `later pages are fetched on demand and the last one says there is no more`() = runTest {
        completed(120)
        val h = harness()
        h.repository.loadLogbookPage(1)

        val page2 = h.repository.loadLogbookPage(2)
        val page3 = h.repository.loadLogbookPage(3)

        assertEquals(NetworkResult.Success(LogbookPage(2, hasMore = true)), page2)
        assertEquals(NetworkResult.Success(LogbookPage(3, hasMore = false)), page3)
        assertEquals(120, h.taskDao.snapshot().size)
    }

    @Test
    fun `the retention window limits what is requested`() = runTest {
        completed(10)
        val h = harness()
        h.logbookPrefsStore.setEnabled(true)
        h.logbookPrefsStore.setRetentionDays(30)

        h.repository.loadLogbookPage(1)

        val filter = server.lists().single().filter!!
        assertTrue(Regex("^done = true && done_at >= '\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z'$").matches(filter), filter)
    }

    @Test
    fun `with retention off every completed task is in reach`() = runTest {
        completed(10)
        val h = harness()

        h.repository.loadLogbookPage(1)

        assertEquals("done = true", server.lists().single().filter)
    }

    @Test
    fun `page one removes cached completed tasks newer than its oldest row that the server no longer has`() = runTest {
        completed(60)
        // Cached from before: a completed task that was deleted on the server, newer than anything on page one.
        val local = FakeTaskDao(
            listOf(
                TaskEntity(id = 900, title = "Deleted elsewhere", done = true, doneAt = "2026-12-01T00:00:00Z", projectId = 7),
                // Older than page one's oldest row: not certain to be gone, so kept.
                TaskEntity(id = 901, title = "Older", done = true, doneAt = "2026-01-01T00:00:00Z", projectId = 7),
            ),
        )
        val h = harness(taskDao = local)

        h.repository.loadLogbookPage(1)

        assertNull(local.entity(900))
        assertNotNull(local.entity(901))
    }

    @Test
    fun `a first page that is also the last removes every cached completed task the server lacks`() = runTest {
        completed(5)
        val local = FakeTaskDao(
            listOf(
                TaskEntity(id = 900, title = "Deleted elsewhere", done = true, doneAt = "2026-01-01T00:00:00Z", projectId = 7),
                TaskEntity(id = 901, title = "Reopened elsewhere is open now", done = false, projectId = 7),
            ),
        )
        val h = harness(taskDao = local)

        h.repository.loadLogbookPage(1)

        assertNull(local.entity(900))
        assertNotNull(local.entity(901), "open tasks are the open reconcile's business")
    }

    @Test
    fun `routine carriers and rows with queued changes survive the page one reconcile`() = runTest {
        completed(5)
        val pending = FakePendingActionDao()
        pending.insert(PendingActionEntity(entityType = "task", entityId = 902, actionType = "toggle_done", payload = "{}"))
        val local = FakeTaskDao(
            listOf(
                TaskEntity(
                    id = 900, title = "Routine", done = true, doneAt = "2026-12-01T00:00:00Z", projectId = 7,
                    description = "<!-- vicu-routine:v1:e30 -->",
                ),
                TaskEntity(id = 902, title = "Queued", done = true, doneAt = "2026-12-01T00:00:00Z", projectId = 7),
            ),
        )
        val h = harness(taskDao = local, pendingActionDao = pending)

        h.repository.loadLogbookPage(1)

        assertNotNull(local.entity(900))
        assertNotNull(local.entity(902))
    }

    @Test
    fun `a later page never deletes anything`() = runTest {
        completed(120)
        val local = FakeTaskDao(
            listOf(TaskEntity(id = 900, title = "Cached", done = true, doneAt = "2026-12-01T00:00:00Z", projectId = 7)),
        )
        val h = harness(taskDao = local)

        h.repository.loadLogbookPage(2)

        assertNotNull(local.entity(900))
    }

    @Test
    fun `a failed page is an error and leaves the cache alone`() = runTest {
        completed(5)
        server.override = { respond("", HttpStatusCode.ServiceUnavailable) }
        val h = harness()

        val result = h.repository.loadLogbookPage(1)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.taskDao.snapshot().isEmpty())
    }

    @Test
    fun `a normal refresh does not download completed history`() = runTest {
        completed(120)
        server.put(500, "Open", done = false, updated = "2026-09-30T00:00:00Z")
        val h = harness()

        h.repository.refreshAll()

        assertTrue(server.lists().none { it.isCompletedQuery() && it.query == null }, "no scan of completed tasks: ${server.lists()}")
        assertEquals(listOf(500L), h.taskDao.snapshot().map { it.id })
    }
}
