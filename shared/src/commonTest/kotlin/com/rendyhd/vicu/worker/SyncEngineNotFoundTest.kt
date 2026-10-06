package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 404 on a queued change only means "it is gone" when the server says so (a Vikunja problem
 * that names the missing resource) or, for a bare 404, when asking for the resource again also
 * says it is gone and the API still answers other calls. A proxy that answers every request
 * with 404 must never make the engine throw real work away.
 */
class SyncEngineNotFoundTest {

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: Long, detail: String = "refused"): HttpResponseData =
        respond(
            content = """{"title":"${status.description}","status":${status.value},"detail":"$detail","code":$code}""",
            status = status,
            headers = authTestJsonHeaders,
        )

    private fun MockRequestHandleScope.bare404(): HttpResponseData =
        respond(content = "404 page not found", status = HttpStatusCode.NotFound)

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(content = body, status = HttpStatusCode.OK, headers = authTestJsonHeaders)

    private val HttpRequestData.route get() = "${method.value} ${url.encodedPath}"

    private fun taskUpdate(taskId: Long) = PendingActionEntity(
        entityType = "task", entityId = taskId, actionType = "update", payload = """{"priority":3}""",
        createdAt = "2020-01-01T00:00:00Z", updatedAt = "2020-01-01T00:00:00Z",
    )

    private fun labelAction(type: String, labelId: Long, payload: String = "") = PendingActionEntity(
        entityType = "label", entityId = labelId, actionType = type, payload = payload,
        createdAt = "2020-01-01T00:00:00Z", updatedAt = "2020-01-01T00:00:00Z",
    )

    private fun localTask(id: Long, labelsJson: String = "[]") =
        TaskEntity(id = id, title = "Task $id", projectId = 7, labelsJson = labelsJson)

    /** The server's task list: these tasks and nothing else (anything else cached is swept). */
    private fun MockRequestHandleScope.tasksPage(vararg ids: Long): HttpResponseData =
        json(
            ids.joinToString(",") { """{"id":$it,"title":"Task $it","project_id":7}""" }.let {
                """{"items":[$it],"total":${ids.size},"page":1,"per_page":100,"total_pages":1}"""
            },
        )

    private val userJson = """{"id":1,"username":"rendy"}"""

    // ---- tasks ------------------------------------------------------------------------------

    @Test
    fun `a bare 404 is a gone task when asking for the task again names it missing`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11), localTask(12)))) { request ->
            when (request.route) {
                "PATCH /tasks/11" -> bare404()
                "GET /tasks/11" -> problem(HttpStatusCode.NotFound, 4002, "This task does not exist")
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(taskUpdate(11))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "dropped, not failed")
        assertNull(h.taskDao.entity(11))
        assertEquals(listOf(11L), h.hooks.cancelled)
        h.close()
    }

    @Test
    fun `a bare 404 twice is a gone task when the API still answers other calls`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when (request.route) {
                "PATCH /tasks/11", "GET /tasks/11" -> bare404()
                "GET /user" -> json(userJson)
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(taskUpdate(11))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertNull(h.taskDao.entity(11))
        h.close()
    }

    @Test
    fun `a proxy that answers every request with 404 never drops real work`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { bare404() }
        h.pendingActionDao.insert(taskUpdate(11))

        h.engine.performSync()

        assertEquals("failed", h.pendingActionDao.snapshot().single().status, "the user can still retry it")
        assertNotNull(h.taskDao.entity(11), "the unsynced change is not thrown away")
        assertTrue(h.hooks.cancelled.isEmpty())
        h.close()
    }

    @Test
    fun `a bare 404 on a task the server still has is not a gone task`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when (request.route) {
                "PATCH /tasks/11" -> bare404()
                "GET /tasks/11" -> json("""{"id":11,"title":"Task 11","project_id":7}""")
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(taskUpdate(11))

        h.engine.performSync()

        assertEquals("failed", h.pendingActionDao.snapshot().single().status)
        assertNotNull(h.taskDao.entity(11))
        h.close()
    }

    @Test
    fun `a task problem body needs no second request`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when (request.route) {
                "PATCH /tasks/11" -> problem(HttpStatusCode.NotFound, 4002, "This task does not exist")
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(taskUpdate(11))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertEquals(0, h.count(HttpMethod.Get, "/tasks/11"))
        h.close()
    }

    @Test
    fun `a 404 that names another missing resource stays a failure`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when (request.route) {
                "PATCH /tasks/11" -> problem(HttpStatusCode.NotFound, 3001, "This project does not exist")
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(taskUpdate(11))

        h.engine.performSync()

        assertEquals("failed", h.pendingActionDao.snapshot().single().status)
        assertEquals(0, h.count(HttpMethod.Get, "/tasks/11"), "the body already said what is missing")
        h.close()
    }

    // ---- labels -----------------------------------------------------------------------------

    private val labelOnTask =
        """[{"id":5,"title":"home","hex_color":"ff0000"},{"id":6,"title":"work","hex_color":"00ff00"}]"""

    @Test
    fun `adding a label to a task that is gone drops the action and the task`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when (request.route) {
                "POST /tasks/11/labels" -> problem(HttpStatusCode.NotFound, 4002, "This task does not exist")
                else -> emptyPage()
            }
        }
        h.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        h.pendingActionDao.insert(labelAction("add_label", labelId = 5, payload = "11:5"))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertNull(h.taskDao.entity(11))
        h.close()
    }

    @Test
    fun `adding a label that no longer exists drops the action and the label everywhere`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11, labelsJson = labelOnTask)))) { request ->
            when (request.route) {
                "POST /tasks/11/labels" -> problem(HttpStatusCode.NotFound, 7002, "This label does not exist")
                // The refresh after the run is down, so what is checked is the local cleanup.
                "GET /tasks" -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> emptyPage()
            }
        }
        h.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        h.pendingActionDao.insert(labelAction("add_label", labelId = 5, payload = "11:5"))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "no stuck failed action")
        assertNull(h.labelDao.getById(5))
        val remaining = h.taskDao.entity(11)!!.labelsJson
        assertTrue("\"id\":5" !in remaining && "\"id\":6" in remaining, "only the missing label leaves the task: $remaining")
        h.close()
    }

    @Test
    fun `a bare 404 on adding a label is told apart by asking for the task and the label`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when (request.route) {
                "POST /tasks/11/labels" -> bare404()
                "GET /tasks/11" -> json("""{"id":11,"title":"Task 11","project_id":7}""")
                "GET /labels/5" -> problem(HttpStatusCode.NotFound, 7002, "This label does not exist")
                "GET /tasks" -> tasksPage(11)
                else -> emptyPage()
            }
        }
        h.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        h.pendingActionDao.insert(labelAction("add_label", labelId = 5, payload = "11:5"))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertNull(h.labelDao.getById(5))
        assertNotNull(h.taskDao.entity(11))
        h.close()
    }

    @Test
    fun `a label that is already on the task counts as added`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11)))) { request ->
            when (request.route) {
                "POST /tasks/11/labels" -> problem(HttpStatusCode.BadRequest, 7001, "This label already exists on this task")
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(labelAction("add_label", labelId = 5, payload = "11:5"))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `removing a label that is already gone is not a failure`() = runTest {
        val h = SyncEngineHarness(taskDao = FakeTaskDao(listOf(localTask(11, labelsJson = labelOnTask)))) { request ->
            when (request.route) {
                "DELETE /tasks/11/labels/5" -> bare404()
                "GET /tasks" -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(labelAction("remove_label", labelId = 5, payload = "11:5"))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertTrue("\"id\":5" !in h.taskDao.entity(11)!!.labelsJson)
        h.close()
    }

    @Test
    fun `deleting a label that is already gone drops the action and the local label`() = runTest {
        val h = SyncEngineHarness { request ->
            when (request.route) {
                "DELETE /labels/5" -> problem(HttpStatusCode.NotFound, 7002, "This label does not exist")
                else -> emptyPage()
            }
        }
        h.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        h.pendingActionDao.insert(labelAction("delete", labelId = 5))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertNull(h.labelDao.getById(5))
        h.close()
    }

    @Test
    fun `renaming a label that was deleted on the server drops the action and the label`() = runTest {
        val h = SyncEngineHarness { request ->
            when (request.route) {
                "PATCH /labels/5" -> problem(HttpStatusCode.NotFound, 7002, "This label does not exist")
                else -> emptyPage()
            }
        }
        h.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        h.pendingActionDao.insert(labelAction("update", labelId = 5, payload = """{"title":"house"}"""))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertNull(h.labelDao.getById(5))
        h.close()
    }

    @Test
    fun `a proxy that answers every request with 404 keeps a label rename as a failure`() = runTest {
        val h = SyncEngineHarness { bare404() }
        h.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        h.pendingActionDao.insert(labelAction("update", labelId = 5, payload = """{"title":"house"}"""))

        h.engine.performSync()

        assertEquals("failed", h.pendingActionDao.snapshot().single().status)
        assertNotNull(h.labelDao.getById(5))
        h.close()
    }
}
