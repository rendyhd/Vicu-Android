package com.rendyhd.vicu.worker

import com.rendyhd.vicu.data.local.dao.MAX_SQL_ID_PARAMS
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import com.rendyhd.vicu.auth.authTestJsonHeaders
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
        assertEquals(2_500, h.taskDao.deletedIdListSizes.sum())
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

    @Test
    fun `server refresh caches routine carriers but never routine archive parts`() = runTest {
        val tasks = """
            {"items":[
              {"id":5,"title":"Buy milk","project_id":7},
              {"id":6,"title":"Vitamin D","description":"<!-- vicu-routine:v1:e30 -->","done":true,"project_id":7},
              {"id":7,"title":"Vicu routine archive","description":"<!-- vicu-routine:archive:v1:e30 -->","done":true,"project_id":7}
            ],"total":3,"page":1,"per_page":100,"total_pages":1}
        """.trimIndent()
        val stale = TaskEntity(id = 8, title = "Vicu routine archive", description = "<!-- vicu-routine:archive:v1:e30 -->", done = true, projectId = 7)
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(stale))) { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath == "/tasks") {
                respond(tasks, HttpStatusCode.OK, authTestJsonHeaders)
            } else {
                emptyPage()
            }
        }

        h.engine.performSync()

        assertEquals(
            listOf(5L, 6L),
            h.taskDao.snapshot().map { it.id }.sorted(),
            "a part is read on demand; one an older build cached is swept",
        )
        h.close()
    }
}
