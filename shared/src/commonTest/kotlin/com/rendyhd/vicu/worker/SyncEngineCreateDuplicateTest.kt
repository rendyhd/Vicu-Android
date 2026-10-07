package com.rendyhd.vicu.worker

import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.FakeTaskServer
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A queued create is only matched with a task already on the server when an earlier attempt of
 * it may have made that task. Two identical tasks made offline, or one identical to a task made
 * online, stay separate tasks.
 */
class SyncEngineCreateDuplicateTest {

    private val server = FakeTaskServer()
    private val h = SyncEngineHarness { server.handle(this, it) }

    private suspend fun queue(tempId: Long, title: String = "Buy milk") {
        val now = DateUtils.nowIso()
        h.pendingActionDao.insert(queuedCreate(tempId, title, createdAt = now))
        h.taskDao.upsert(TaskEntity(id = tempId, title = title, projectId = 7))
    }

    private fun milk() = server.rows.values.filter { it.title == "Buy milk" }

    @Test
    fun `two identical tasks made offline are both created`() = runTest {
        queue(-1)
        queue(-2)

        assertTrue(h.engine.performSync())

        assertEquals(2, milk().size)
        assertEquals(2, server.requestsLike("POST", "/projects/7/tasks").size)
        h.close()
    }

    @Test
    fun `a task made online that looks the same is not taken for a queued create`() = runTest {
        server.seed(900, "Buy milk", "", done = false, projectId = 7, created = DateUtils.nowIso())
        queue(-1)

        h.engine.performSync()

        assertEquals(2, milk().size, "the queued task is created")
        h.close()
    }

    @Test
    fun `a create whose earlier attempt may have reached the server takes the task it made`() = runTest {
        server.seed(900, "Buy milk", "", done = false, projectId = 7, created = DateUtils.nowIso())
        queue(-1)
        h.tempIds.markCreateMaybeSent(-1)

        h.engine.performSync()

        assertEquals(listOf(900L), milk().map { it.id }, "nothing was created twice")
        assertTrue(h.taskDao.snapshot().any { it.id == 900L })
        assertFalse(h.tempIds.createMaybeSent(-1), "forgotten once done")
        h.close()
    }

    @Test
    fun `two creates never take the same task`() = runTest {
        server.seed(900, "Buy milk", "", done = false, projectId = 7, created = DateUtils.nowIso())
        queue(-1)
        queue(-2)
        h.tempIds.markCreateMaybeSent(-1)
        h.tempIds.markCreateMaybeSent(-2)

        h.engine.performSync()

        assertEquals(2, milk().size, "one taken, one created")
        assertEquals(1, server.requestsLike("POST", "/projects/7/tasks").size)
        h.close()
    }

    @Test
    fun `a create that went through before a later step failed is not sent again`() = runTest {
        server.seed(901, "Buy milk", "", done = false, projectId = 7, created = DateUtils.nowIso())
        queue(-1)
        // The create answered with 901; linking it to its parent failed.
        h.tempIds.rememberRealId(-1, 901)

        h.engine.performSync()

        assertEquals(listOf(901L), milk().map { it.id })
        assertTrue(server.requestsLike("POST", "/projects/7/tasks").isEmpty())
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `a subtask that was created but not linked is remembered by its id`() = runTest {
        val repo = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(TaskEntity(id = 42, title = "Parent", projectId = 7)))) { request ->
            when (request.url.encodedPath) {
                "/projects/7/tasks" -> respond(
                    """{"id":777,"title":"Child","project_id":7}""",
                    HttpStatusCode.Created,
                    com.rendyhd.vicu.auth.authTestJsonHeaders,
                )
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        }

        val local = (repo.repository.createSubtask(42, Task(id = 0, title = "Child", projectId = 7)) as NetworkResult.Success).data

        assertTrue(local.id < 0L, "queued to be linked")
        assertEquals(777L, repo.tempIds.realIdFor(local.id), "the sync takes task 777 instead of creating it again")
    }

    @Test
    fun `a create that timed out online is looked for, one refused before it was sent is not`() = runTest {
        val unavailable = TaskRepositoryHarness(taskDao = FakeTaskDao()) { respond("", HttpStatusCode.ServiceUnavailable) }
        val busy = TaskRepositoryHarness(taskDao = FakeTaskDao()) { respond("", HttpStatusCode.TooManyRequests) }

        val maybe = (unavailable.repository.create(Task(id = 0, title = "A", projectId = 7)) as NetworkResult.Success).data.id
        val notSent = (busy.repository.create(Task(id = 0, title = "B", projectId = 7)) as NetworkResult.Success).data.id

        assertTrue(unavailable.tempIds.createMaybeSent(maybe), "a 5xx may have created it")
        assertFalse(busy.tempIds.createMaybeSent(notSent), "a 429 did not")
    }
}
