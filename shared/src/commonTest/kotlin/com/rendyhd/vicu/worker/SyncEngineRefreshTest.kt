package com.rendyhd.vicu.worker

import com.rendyhd.vicu.data.local.dao.MAX_SQL_ID_PARAMS
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineRefreshTest {

    @Test
    fun `server refresh removes 2500 vanished tasks without binding more than one chunk`() = runTest {
        val local = (1L..2_500L).map { TaskEntity(id = it, title = "Task $it", projectId = 7) }
        val h = SyncEngineHarness(taskDao = FakeTaskDao(local)) { request ->
            if (request.method == HttpMethod.Get) emptyPage() else error("Unexpected ${request.url.encodedPath}")
        }

        h.engine.performSync()

        assertTrue(h.taskDao.snapshot().isEmpty())
        assertTrue(h.taskDao.boundIdListSizes.all { it <= MAX_SQL_ID_PARAMS }, "bound ${h.taskDao.boundIdListSizes}")
        assertEquals(2_500, h.taskDao.boundIdListSizes.sum())
        h.close()
    }

    @Test
    fun `server refresh keeps tasks that still have queued actions`() = runTest {
        val local = listOf(
            TaskEntity(id = -3, title = "Offline create", projectId = 7),
            TaskEntity(id = 11, title = "Gone on server", projectId = 7),
        )
        val h = SyncEngineHarness(taskDao = FakeTaskDao(local)) { emptyPage() }
        // A failed action keeps its task out of the sweep (it is not retried by this run).
        h.pendingActionDao.insert(
            queuedCreate(tempId = -3, title = "Offline create").copy(status = "failed"),
        )

        h.engine.performSync()

        assertEquals(listOf(-3L), h.taskDao.snapshot().map { it.id })
        h.close()
    }
}
