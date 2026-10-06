package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.MAX_SQL_ID_PARAMS
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A full refresh deletes cached tasks the server no longer has, however many there are. */
class TaskRepositoryRefreshAllTest {

    private fun localTasks(count: Int) = (1L..count).map { TaskEntity(id = it, title = "Task $it", projectId = 7) }

    /** Serves GET /tasks for task ids 1..[serverCount], 100 per page. */
    private fun serverWith(serverCount: Int, local: List<TaskEntity>, pendingDao: FakePendingActionDao = FakePendingActionDao()) =
        TaskRepositoryHarness(taskDao = FakeTaskDao(local), pendingActionDao = pendingDao) { request ->
            val page = request.url.parameters["page"]?.toInt() ?: 1
            val first = (page - 1) * 100 + 1
            val last = minOf(page * 100, serverCount)
            val items = (first..last).joinToString(",") { """{"id":$it,"title":"Task $it","project_id":7}""" }
            val totalPages = maxOf(1, (serverCount + 99) / 100)
            jsonOk("""{"items":[$items],"total":$serverCount,"page":$page,"per_page":100,"total_pages":$totalPages}""")
        }

    @Test
    fun `deleting 2400 vanished tasks binds at most one chunk per statement`() = runTest {
        val h = serverWith(serverCount = 100, local = localTasks(2_500))

        val result = h.repository.refreshAll()

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals((1L..100L).toList(), h.taskDao.snapshot().map { it.id })
        assertTrue(h.taskDao.boundIdListSizes.all { it <= MAX_SQL_ID_PARAMS }, "bound ${h.taskDao.boundIdListSizes}")
        assertEquals(2_400, h.taskDao.boundIdListSizes.sum())
    }

    @Test
    fun `a large server set is not bound as one list either`() = runTest {
        // The old sweep bound every server id (here 1,500) in a single NOT IN statement.
        val h = serverWith(serverCount = 1_500, local = localTasks(2_500))

        h.repository.refreshAll()

        assertEquals(1_500, h.taskDao.snapshot().size)
        assertTrue(h.taskDao.boundIdListSizes.all { it <= MAX_SQL_ID_PARAMS }, "bound ${h.taskDao.boundIdListSizes}")
    }

    @Test
    fun `tasks with queued actions survive the sweep`() = runTest {
        val pending = FakePendingActionDao()
        pending.insert(
            PendingActionEntity(
                entityType = "task", entityId = 2_000, actionType = "update", payload = "{}",
            ),
        )
        val h = serverWith(serverCount = 10, local = localTasks(2_500), pendingDao = pending)

        h.repository.refreshAll()

        val remaining = h.taskDao.snapshot().map { it.id }.toSet()
        assertEquals((1L..10L).toSet() + 2_000L, remaining)
    }

    @Test
    fun `a filtered refresh never deletes anything`() = runTest {
        val h = serverWith(serverCount = 5, local = localTasks(50))

        h.repository.refreshAll(mapOf("q" to "task"))

        assertEquals(50, h.taskDao.snapshot().size)
        assertTrue(h.taskDao.boundIdListSizes.isEmpty())
    }
}
