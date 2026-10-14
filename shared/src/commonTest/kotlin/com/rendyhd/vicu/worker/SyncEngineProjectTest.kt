package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replaying queued project changes: they go out as one merge patch per project, a transient failure
 * is retried, a refusal is kept as a failed change, and a 404 only drops the change when the
 * project is really gone (the same rules as for tasks and labels).
 */
class SyncEngineProjectTest {

    private val home = ProjectEntity(id = 5, title = "Home", position = 1.0)

    private val HttpRequestData.route get() = "${method.value} ${url.encodedPath}"

    private fun projectUpdate(projectId: Long, payload: String, retryCount: Int = 0) = PendingActionEntity(
        entityType = "project", entityId = projectId, actionType = "update", payload = payload,
        retryCount = retryCount, createdAt = "2020-01-01T00:00:00Z", updatedAt = "2020-01-01T00:00:00Z",
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(content = body, status = status, headers = authTestJsonHeaders)

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: Long, detail: String): HttpResponseData =
        json("""{"title":"${status.description}","status":${status.value},"detail":"$detail","code":$code}""", status)

    private fun MockRequestHandleScope.bare404(): HttpResponseData =
        respond(content = "404 page not found", status = HttpStatusCode.NotFound)

    private fun projectResponse(title: String, description: String = "") =
        """{"id":5,"title":"$title","description":"$description","position":1.0,"updated":"2026-10-07T10:00:00Z"}"""

    private val projectsPage = """{"items":[{"id":5,"title":"Home","position":1.0}],"total":1,"page":1,"per_page":100,"total_pages":1}"""

    /** Everything but the project route is an empty list, so the refresh after the run is harmless. */
    private fun MockRequestHandleScope.otherwise(request: HttpRequestData): HttpResponseData =
        if (request.route == "GET /projects") json(projectsPage) else emptyPage()

    @Test
    fun `a queued rename is sent as one merge patch and stored from the response`() = runTest {
        val bodies = mutableListOf<String?>()
        val h = SyncEngineHarness(projectDao = FakeProjectDao(listOf(home))) { request ->
            when (request.route) {
                "PATCH /projects/5" -> {
                    bodies += (request.body as? TextContent)?.text
                    json(projectResponse("Renamed"))
                }
                else -> otherwise(request)
            }
        }
        h.pendingActionDao.insert(projectUpdate(5, """{"title":"Renamed"}"""))

        h.engine.performSync()

        assertEquals(listOf<String?>("""{"title":"Renamed"}"""), bodies)
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "completed")
        h.close()
    }

    @Test
    fun `a failure that is worth retrying keeps the change queued`() = runTest {
        val h = SyncEngineHarness(projectDao = FakeProjectDao(listOf(home))) { request ->
            when (request.route) {
                "PATCH /projects/5" -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> otherwise(request)
            }
        }
        h.pendingActionDao.insert(projectUpdate(5, """{"title":"Renamed"}"""))

        val finished = h.engine.performSync()

        val action = h.pendingActionDao.snapshot().single()
        assertEquals("pending", action.status)
        assertEquals(1, action.retryCount)
        assertEquals(false, finished, "the run asks to be retried")
        h.close()
    }

    @Test
    fun `a change the server refuses is kept as a failed change instead of vanishing`() = runTest {
        val h = SyncEngineHarness(projectDao = FakeProjectDao(listOf(home))) { request ->
            when (request.route) {
                "PATCH /projects/5" -> problem(HttpStatusCode.BadRequest, 3003, "The project title is invalid")
                else -> otherwise(request)
            }
        }
        h.pendingActionDao.insert(projectUpdate(5, """{"title":""}"""))

        h.engine.performSync()

        assertEquals("failed", h.pendingActionDao.snapshot().single().status)
        h.close()
    }

    @Test
    fun `a 404 that names the project as missing drops the change, the project and its cached tasks`() = runTest {
        val cachedTask = TaskEntity(id = 21, title = "In the gone project", projectId = 5)
        val h = SyncEngineHarness(
            projectDao = FakeProjectDao(listOf(home)),
            taskDao = FakeTaskDao(listOf(cachedTask)),
        ) { request ->
            when (request.route) {
                "PATCH /projects/5" -> problem(HttpStatusCode.NotFound, 3001, "This project does not exist")
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(projectUpdate(5, """{"title":"Renamed"}"""))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "dropped, not failed")
        assertTrue(h.projectDao.snapshot().isEmpty(), "the project is gone locally too")
        assertNull(h.taskDao.entity(21), "the full reconcile that follows sweeps what the project held")
        h.close()
    }

    @Test
    fun `a bare 404 is a gone project when asking for it again says so and the API still answers`() = runTest {
        val h = SyncEngineHarness(projectDao = FakeProjectDao(listOf(home))) { request ->
            when (request.route) {
                "PATCH /projects/5" -> bare404()
                "GET /projects/5" -> problem(HttpStatusCode.NotFound, 3001, "This project does not exist")
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(projectUpdate(5, """{"title":"Renamed"}"""))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertTrue(h.projectDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `a proxy that answers every request with 404 never costs the user a change`() = runTest {
        val h = SyncEngineHarness(projectDao = FakeProjectDao(listOf(home))) { request ->
            when (request.route) {
                "PATCH /projects/5", "GET /projects/5", "GET /user" -> bare404()
                else -> emptyPage()
            }
        }
        h.pendingActionDao.insert(projectUpdate(5, """{"title":"Renamed"}"""))

        h.engine.performSync()

        assertEquals("failed", h.pendingActionDao.snapshot().single().status, "kept where the user can retry or discard it")
        assertNotNull(h.projectDao.snapshot().singleOrNull(), "the local project stays")
        h.close()
    }

    @Test
    fun `the local row is not overwritten by a refresh while its change is waiting`() = runTest {
        val renamed = home.copy(title = "Renamed offline")
        val h = SyncEngineHarness(projectDao = FakeProjectDao(listOf(renamed))) { request ->
            when (request.route) {
                // The change cannot be sent now, and the server still lists the old title.
                "PATCH /projects/5" -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> otherwise(request)
            }
        }
        h.pendingActionDao.insert(projectUpdate(5, """{"title":"Renamed offline"}"""))

        h.engine.performSync()

        assertEquals("Renamed offline", h.projectDao.snapshot().single().title)
        h.close()
    }
}
