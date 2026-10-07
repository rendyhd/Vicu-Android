package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.ProjectRefresher
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Project changes (rename, description with the review footer, archive, position, parent) made
 * while the server cannot be reached are kept locally and queued like task changes: folded into
 * one patch per project, replayed in order, and a change the server refuses for good is rolled back.
 */
class ProjectRepositoryOfflineTest {

    private val original = ProjectEntity(
        id = 5, title = "Home", description = "Original", position = 1.0, parentProjectId = 0,
    )

    private class Rig(
        val projectDao: FakeProjectDao,
        val pendingActionDao: FakePendingActionDao = FakePendingActionDao(),
        val hooks: RecordingRepositoryHooks = RecordingRepositoryHooks(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val sent = mutableListOf<Pair<String, String?>>()

        private val client = HttpClient(
            MockEngine { request ->
                sent += "${request.method.value} ${request.url.encodedPath}" to (request.body as? TextContent)?.text
                handler(request)
            },
        ) {
            install(ContentNegotiation) { json(authTestJson) }
        }
        private val api = VikunjaApiService(client, authTestJson)
        val repository = ProjectRepositoryImpl(
            projectDao = projectDao,
            api = api,
            projectMapper = ProjectMapper(),
            projectRefresher = ProjectRefresher(projectDao, pendingActionDao, api, ProjectMapper()),
            pendingActionDao = pendingActionDao,
            platformHooks = hooks,
        )

        fun patches() = sent.filter { it.first.startsWith("PATCH") }
        fun patchBody(index: Int = 0): JsonObject = Json.parseToJsonElement(patches()[index].second!!).jsonObject
    }

    private fun MockRequestHandleScope.unavailable(): HttpResponseData =
        respond(content = "", status = HttpStatusCode.ServiceUnavailable)

    private fun MockRequestHandleScope.rejected(): HttpResponseData = respond(
        content = """{"title":"Bad Request","status":400,"detail":"The project title is invalid","code":3003}""",
        status = HttpStatusCode.BadRequest,
        headers = authTestJsonHeaders,
    )

    private fun MockRequestHandleScope.ok(json: String): HttpResponseData =
        respond(content = json, status = HttpStatusCode.OK, headers = authTestJsonHeaders)

    private fun projectJson(title: String, description: String = "Original") =
        """{"id":5,"title":"$title","description":"$description","position":1.0,"updated":"2026-10-07T10:00:00Z"}"""

    private fun domain(entity: ProjectEntity = original) = with(ProjectMapper()) { entity.toDomain() }

    private suspend fun Rig.queued() = pendingActionDao.snapshot()

    // --- online --------------------------------------------------------------------------------

    @Test
    fun `an update that reaches the server sends only the changed field and queues nothing`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { ok(projectJson("Renamed")) }

        val result = rig.repository.update(domain().copy(title = "Renamed"))

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, rig.patches().size)
        assertEquals(setOf("title"), rig.patchBody().keys)
        assertEquals("Renamed", rig.projectDao.snapshot().single().title)
        assertTrue(rig.queued().isEmpty())
        assertEquals(0, rig.hooks.syncTriggers)
    }

    // --- offline -------------------------------------------------------------------------------

    @Test
    fun `a rename while the server is unreachable stays in the local row and is queued as a patch`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { unavailable() }

        val result = rig.repository.update(domain().copy(title = "Renamed"))

        assertTrue(result is NetworkResult.Success, "the change counts as made")
        assertEquals("Renamed", rig.projectDao.snapshot().single().title)
        val action = rig.queued().single()
        assertEquals("project", action.entityType)
        assertEquals(5L, action.entityId)
        assertEquals("update", action.actionType)
        assertEquals(setOf("title"), Json.parseToJsonElement(action.payload).jsonObject.keys)
        assertEquals(1, rig.hooks.syncTriggers)
    }

    @Test
    fun `a description with the review footer is queued offline too`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { unavailable() }
        val reviewed = domain().copy(description = "Original\n\n<!-- vicu-review: reviewed 2026-10-07 -->")

        rig.repository.update(reviewed)

        val payload = Json.parseToJsonElement(rig.queued().single().payload).jsonObject
        assertEquals(setOf("description"), payload.keys)
        assertTrue(payload["description"]!!.jsonPrimitive.content.contains("vicu-review"))
        assertTrue(rig.projectDao.snapshot().single().description.contains("vicu-review"))
    }

    @Test
    fun `archive, position and parent changes are queued offline`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { unavailable() }

        rig.repository.update(domain().copy(isArchived = true))
        rig.repository.update(domain().copy(isArchived = true, position = 4.5, parentProjectId = 9))

        val payload = Json.parseToJsonElement(rig.queued().single().payload).jsonObject
        assertEquals(setOf("is_archived", "position", "parent_project_id"), payload.keys)
        assertEquals("true", payload["is_archived"]!!.jsonPrimitive.content)
        assertEquals("4.5", payload["position"]!!.jsonPrimitive.content)
        assertEquals("9", payload["parent_project_id"]!!.jsonPrimitive.content)
        val row = rig.projectDao.snapshot().single()
        assertTrue(row.isArchived)
        assertEquals(9L, row.parentProjectId)
    }

    @Test
    fun `several offline changes to one project fold into a single queued patch`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { unavailable() }

        rig.repository.update(domain().copy(title = "First"))
        rig.repository.update(domain().copy(title = "Second"))
        rig.repository.update(domain().copy(title = "Second", description = "Notes"))

        val action = rig.queued().single()
        val payload = Json.parseToJsonElement(action.payload).jsonObject
        assertEquals("Second", payload["title"]!!.jsonPrimitive.content, "the last value of a field wins")
        assertEquals("Notes", payload["description"]!!.jsonPrimitive.content, "and the other field is kept")
    }

    @Test
    fun `projects are queued separately`() = runTest {
        val other = ProjectEntity(id = 6, title = "Work")
        val rig = Rig(FakeProjectDao(listOf(original, other))) { unavailable() }

        rig.repository.update(domain().copy(title = "Home 2"))
        rig.repository.update(domain(other).copy(title = "Work 2"))

        assertEquals(setOf(5L, 6L), rig.queued().map { it.entityId }.toSet())
    }

    @Test
    fun `while a change is queued a later change goes into the queue instead of racing it to the server`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { unavailable() }
        rig.repository.update(domain().copy(title = "Queued"))
        rig.sent.clear()
        // The server is back, but the first change has not been replayed yet.
        val back = Rig(rig.projectDao, rig.pendingActionDao, rig.hooks) { ok(projectJson("Later")) }

        back.repository.update(domain(rig.projectDao.snapshot().single()).copy(description = "Later notes"))

        assertTrue(back.patches().isEmpty(), "no request: replaying in order is the sync engine's job")
        val payload = Json.parseToJsonElement(rig.queued().single().payload).jsonObject
        assertEquals("Queued", payload["title"]!!.jsonPrimitive.content)
        assertEquals("Later notes", payload["description"]!!.jsonPrimitive.content)
    }

    // --- refused for good ----------------------------------------------------------------------

    @Test
    fun `a change the server refuses is rolled back and reported, not queued`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { rejected() }

        val result = rig.repository.update(domain().copy(title = ""))

        assertTrue(result is NetworkResult.Error)
        assertEquals("The project title is invalid", result.message)
        assertEquals("Home", rig.projectDao.snapshot().single().title, "the row is back as it was")
        assertTrue(rig.queued().isEmpty())
        assertFalse(rig.hooks.syncTriggers > 0)
    }

    @Test
    fun `an update with nothing changed sends and queues nothing`() = runTest {
        val rig = Rig(FakeProjectDao(listOf(original))) { unavailable() }

        val result = rig.repository.update(domain())

        assertTrue(result is NetworkResult.Success)
        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.queued().isEmpty())
    }
}
