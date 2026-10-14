package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.created
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the sync engine does with queued changes the server refuses. */
class SyncEngineFailedActionsTest {

    private val queuedAt = "2020-01-01T00:00:00Z"

    private fun queuedTaskAction(taskId: Long, type: String, createdAt: String = queuedAt) = PendingActionEntity(
        entityType = "task",
        entityId = taskId,
        actionType = type,
        payload = if (type == "delete") "" else """{"priority":3}""",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: Long): HttpResponseData =
        respond(
            content = """{"title":"${status.description}","status":${status.value},"detail":"refused","code":$code}""",
            status = status,
            headers = authTestJsonHeaders,
        )

    private fun localTask(id: Long) = TaskEntity(id = id, title = "Task $id", projectId = 7)

    // ---- 401 --------------------------------------------------------------------------------

    @Test
    fun `a 401 after the token refresh keeps the action pending and stops the run`() = runTest {
        lateinit var h: SyncEngineHarness
        h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11), localTask(12)))) { request ->
            if (request.method == HttpMethod.Patch && request.url.encodedPath == "/tasks/11") {
                // What the HTTP client does when it has no token left to try.
                h.authManager.setNeedsReAuth()
                problem(HttpStatusCode.Unauthorized, code = 0)
            } else {
                error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "update", createdAt = "2020-01-01T00:00:00Z"))
        h.pendingActionDao.insert(queuedTaskAction(12, "update", createdAt = "2020-01-02T00:00:00Z"))

        val finished = h.engine.performSync()

        assertTrue(finished, "no retry: signing in again starts the next run")
        val actions = h.pendingActionDao.snapshot()
        assertEquals(listOf("pending", "pending"), actions.map { it.status })
        assertEquals(listOf(0, 0), actions.map { it.retryCount }, "the 401 is not counted against the action")
        assertEquals(listOf("PATCH /tasks/11"), h.requests(), "nothing else is sent, not even the refresh")
        h.close()
    }

    @Test
    fun `an unexpected 401 keeps the action and asks for a retry later`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            if (request.method == HttpMethod.Patch) {
                problem(HttpStatusCode.Unauthorized, code = 0)
            } else {
                error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "update"))

        val finished = h.engine.performSync()

        assertFalse(finished)
        assertEquals("pending", h.pendingActionDao.snapshot().single().status)
        h.close()
    }

    @Test
    fun `no request is sent while the session needs a new sign-in`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            error("Unexpected ${request.method.value} ${request.url.encodedPath}")
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "update"))
        h.authManager.ensureInitializedAndGetToken()
        h.authManager.setNeedsReAuth()

        val finished = h.engine.performSync()

        assertTrue(finished)
        assertTrue(h.requests().isEmpty())
        assertEquals("pending", h.pendingActionDao.snapshot().single().status)
        assertEquals(listOf(11L), h.taskDao.snapshot().map { it.id })
        h.close()
    }

    // ---- 404 --------------------------------------------------------------------------------

    @Test
    fun `an update to a task deleted on the server is dropped and the local row goes`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11), localTask(12)))) { request ->
            when {
                request.method == HttpMethod.Patch && request.url.encodedPath == "/tasks/11" ->
                    problem(HttpStatusCode.NotFound, code = 4002)
                request.method == HttpMethod.Get -> respond(
                    content = """{"items":[{"id":12,"title":"Task 12","project_id":7}],"total":1,"page":1,"per_page":100,"total_pages":1}""",
                    status = HttpStatusCode.OK,
                    headers = authTestJsonHeaders,
                )
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "update"))

        val finished = h.engine.performSync()

        assertTrue(finished, "nothing to retry")
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "the action is gone, not failed")
        assertEquals(listOf(12L), h.taskDao.snapshot().map { it.id })
        assertEquals(listOf(11L), h.hooks.cancelled, "its reminders are cancelled")
        h.close()
    }

    @Test
    fun `completing a task deleted on the server is dropped too`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when {
                request.method == HttpMethod.Patch -> problem(HttpStatusCode.NotFound, code = 4002)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "toggle_done"))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertTrue(h.taskDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `deleting a task that is already gone from the server is not a failure`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Delete -> problem(HttpStatusCode.NotFound, code = 4002)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "delete"))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `a 404 about another missing resource is a failure the user can see`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when {
                // 3001: the project the task was moved to does not exist.
                request.method == HttpMethod.Patch -> problem(HttpStatusCode.NotFound, code = 3001)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "update"))

        h.engine.performSync()

        assertEquals("failed", h.pendingActionDao.snapshot().single().status)
        assertEquals(listOf(11L), h.taskDao.snapshot().map { it.id }, "the unsynced change is not thrown away")
        h.close()
    }

    // ---- retriable failures -----------------------------------------------------------------

    private fun MockRequestHandleScope.updatedTask(id: Long): HttpResponseData =
        respond(
            content = """{"id":$id,"title":"Task $id","project_id":7,"priority":3}""",
            status = HttpStatusCode.OK,
            headers = authTestJsonHeaders,
        )

    @Test
    fun `a change that already failed many times for a retriable reason is still sent`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when {
                request.method == HttpMethod.Patch && request.url.encodedPath == "/tasks/11" -> updatedTask(11)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        // What an older version left behind: pending, and at the old limit of five attempts.
        h.pendingActionDao.insert(queuedTaskAction(11, "update").copy(retryCount = 5))

        val finished = h.engine.performSync()

        assertTrue(finished)
        assertEquals(1, h.count(HttpMethod.Patch, "/tasks/11"))
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "the change reached the server")
        h.close()
    }

    @Test
    fun `a server that stays away does not make a change give up`() = runTest {
        var serverUp = false
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when {
                request.method == HttpMethod.Patch && request.url.encodedPath == "/tasks/11" ->
                    if (serverUp) updatedTask(11) else respond("", HttpStatusCode.ServiceUnavailable)
                request.method == HttpMethod.Get ->
                    if (serverUp) emptyPage() else respond("", HttpStatusCode.ServiceUnavailable)
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "update"))

        repeat(7) { assertFalse(h.engine.performSync(), "every run asks to be retried") }
        val waiting = h.pendingActionDao.snapshot().single()
        assertEquals("pending", waiting.status, "still waiting, neither dropped nor stuck")
        assertEquals(7, waiting.retryCount)

        serverUp = true
        assertTrue(h.engine.performSync())
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "sent once the server is back")
        assertEquals(8, h.count(HttpMethod.Patch, "/tasks/11"))
        h.close()
    }

    // ---- failed actions ---------------------------------------------------------------------

    // ---- nothing to change ------------------------------------------------------------------

    @Test
    fun `a change the server already has is done, not failed`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when {
                // Vikunja's answer to a merge patch that changes nothing.
                request.method == HttpMethod.Patch && request.url.encodedPath == "/tasks/11" ->
                    respond("", HttpStatusCode.NotModified)
                request.method == HttpMethod.Get && request.url.encodedPath == "/tasks/11" -> updatedTask(11)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        // For example completed offline and reopened online behind it: the queue holds a change
        // the server never needed.
        h.pendingActionDao.insert(queuedTaskAction(11, "toggle_done"))

        assertTrue(h.engine.performSync())

        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "nothing waits and nothing failed")
        assertEquals(1, h.count(HttpMethod.Get, "/tasks/11"), "the task is read back")
        h.close()
    }

    @Test
    fun `an action that fails for good is stamped with the time it failed`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when {
                request.method == HttpMethod.Patch -> problem(HttpStatusCode.BadRequest, code = 2002)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedTaskAction(11, "update"))

        h.engine.performSync()

        val action = h.pendingActionDao.snapshot().single()
        assertEquals("failed", action.status)
        assertTrue(action.updatedAt > "2025", "updatedAt now says when it failed, not when it was queued")
        h.close()
    }

    @Test
    fun `failed actions older than the retention period are dropped and recent ones stay`() = runTest {
        val h = SyncEngineHarness { emptyPage() }
        val old = DateUtils.isoDaysAgo(SyncEngine.FAILED_ACTION_RETENTION_DAYS + 1)
        val recent = DateUtils.isoDaysAgo(SyncEngine.FAILED_ACTION_RETENTION_DAYS - 1)
        h.pendingActionDao.insert(queuedTaskAction(11, "update").copy(status = "failed", updatedAt = old))
        h.pendingActionDao.insert(queuedTaskAction(12, "update").copy(status = "failed", updatedAt = recent))

        h.engine.performSync()

        assertEquals(listOf(12L), h.pendingActionDao.snapshot().map { it.entityId })
        h.close()
    }

    // ---- duplicate detection ----------------------------------------------------------------

    private fun queuedCreateWithDescription(description: String): PendingActionEntity {
        val createdAt = "2026-10-06T10:00:00Z"
        return PendingActionEntity(
            entityType = "task",
            entityId = -1,
            actionType = "create",
            payload = authTestJson.encodeToString(
                Task.serializer(),
                Task(id = -1, title = "Buy milk", description = description, projectId = 7, created = createdAt, updated = createdAt),
            ),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun duplicateHarness(serverDescription: String) = SyncEngineHarness { request ->
        when {
            request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" ->
                created("""{"id":501,"title":"Buy milk","description":"mine","project_id":7}""")
            request.method == HttpMethod.Get -> respond(
                content = """{"items":[{"id":900,"title":"Buy milk","description":"$serverDescription","project_id":7,""" +
                    """"created":"2026-10-06T10:01:00Z"}],"total":1,"page":1,"per_page":100,"total_pages":1}""",
                status = HttpStatusCode.OK,
                headers = authTestJsonHeaders,
            )
            else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
        }
    }

    @Test
    fun `a task with the same title but another description is not taken for the queued one`() = runTest {
        val h = duplicateHarness(serverDescription = "something else")
        h.pendingActionDao.insert(queuedCreateWithDescription("mine"))
        h.tempIds.markCreateMaybeSent(-1)

        h.engine.performSync()

        assertEquals(1, h.count(HttpMethod.Post, "/projects/7/tasks"), "the queued task is created")
        h.close()
    }

    @Test
    fun `an earlier attempt of the same create is still recognised`() = runTest {
        val h = duplicateHarness(serverDescription = "mine")
        h.pendingActionDao.insert(queuedCreateWithDescription("mine"))
        // That attempt timed out: it may have reached the server.
        h.tempIds.markCreateMaybeSent(-1)

        h.engine.performSync()

        assertEquals(0, h.count(HttpMethod.Post, "/projects/7/tasks"), "the task that is already there is adopted")
        assertTrue(h.taskDao.snapshot().any { it.id == 900L })
        h.close()
    }
}
