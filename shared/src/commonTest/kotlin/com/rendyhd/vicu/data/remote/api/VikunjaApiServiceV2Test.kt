package com.rendyhd.vicu.data.remote.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VikunjaApiServiceV2Test {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonHeaders = Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

    private fun service(engine: MockEngine): VikunjaApiService {
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return VikunjaApiService(client, json)
    }

    @Test
    fun `task create uses POST and accepts 201`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/projects/7/tasks", request.url.encodedPath)
            respond(
                content = """{"id":9,"title":"Created","project_id":7}""",
                status = HttpStatusCode.Created,
                headers = jsonHeaders,
            )
        }

        val created = service(engine).createTask(7, CreateTaskDto(title = "Created"))

        assertEquals(9, created.id)
        assertEquals("Created", created.title)
    }

    @Test
    fun `delete accepts an empty 204 without decoding JSON`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/tasks/9", request.url.encodedPath)
            respond(content = "", status = HttpStatusCode.NoContent)
        }

        service(engine).deleteTask(9)
    }

    @Test
    fun `task update uses PATCH with merge patch content type`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/tasks/9", request.url.encodedPath)
            assertEquals(
                "application/merge-patch+json",
                request.body.contentType?.toString(),
            )
            respond(
                content = """{"id":9,"title":"Kept","description":"Changed"}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        service(engine).updateTask(
            9,
            buildJsonObject { put("description", "Changed") },
        )
    }

    @Test
    fun `a merge patch that changes nothing reads the task back instead of failing`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/tasks/9", request.url.encodedPath)
            when (request.method) {
                // Vikunja's answer when the task already has every value in the patch.
                HttpMethod.Patch -> respond(content = "", status = HttpStatusCode.NotModified)
                HttpMethod.Get -> respond(
                    content = """{"id":9,"title":"Unchanged","project_id":7,"done":false}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
                else -> error("Unexpected ${request.method.value}")
            }
        }

        val task = service(engine).updateTask(9, buildJsonObject { put("done", false) })

        assertEquals(9, task.id)
        assertEquals("Unchanged", task.title)
    }

    @Test
    fun `project and label merge patches that change nothing are read back too`() = runTest {
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Patch -> respond(content = "", status = HttpStatusCode.NotModified)
                HttpMethod.Get -> when (request.url.encodedPath) {
                    "/projects/3" -> respond("""{"id":3,"title":"Work"}""", HttpStatusCode.OK, jsonHeaders)
                    "/labels/4" -> respond("""{"id":4,"title":"Home"}""", HttpStatusCode.OK, jsonHeaders)
                    else -> error("Unexpected GET ${request.url.encodedPath}")
                }
                else -> error("Unexpected ${request.method.value}")
            }
        }
        val api = service(engine)

        assertEquals("Work", api.updateProject(3, buildJsonObject { put("title", "Work") }).title)
        assertEquals("Home", api.updateLabel(4, buildJsonObject { put("title", "Home") }).title)
    }

    @Test
    fun `task search uses q and follows total_pages even for short pages`() = runTest {
        val seenPages = mutableListOf<Int>()
        val engine = MockEngine { request ->
            assertEquals("needle", request.url.parameters["q"])
            assertEquals(listOf("subtasks"), request.url.parameters.getAll("expand"))
            assertFalse(request.url.parameters.contains("s"))
            val page = request.url.parameters["page"]!!.toInt()
            seenPages += page
            respond(
                content = if (page == 1) {
                    """{"items":[{"id":1,"title":"One"}],"total":2,"page":1,"per_page":50,"total_pages":2}"""
                } else {
                    """{"items":[{"id":2,"title":"Two"}],"total":2,"page":2,"per_page":50,"total_pages":2}"""
                },
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val tasks = service(engine).getAllTasks(
            mapOf("q" to "needle", "per_page" to "50"),
        )

        assertEquals(listOf(1, 2), seenPages)
        assertEquals(listOf(1L, 2L), tasks.map { it.id })
    }

    @Test
    fun `project view tasks request complete subtask hierarchies`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/projects/7/views/11/tasks", request.url.encodedPath)
            assertEquals(listOf("subtasks"), request.url.parameters.getAll("expand"))
            respond(
                content = """{"items":[],"total":0,"page":1,"per_page":100,"total_pages":1}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        service(engine).getViewTasksPage(7, 11)
    }

    @Test
    fun `complete project snapshot requests archived projects on every page`() = runTest {
        val seenPages = mutableListOf<Int>()
        val engine = MockEngine { request ->
            assertEquals("true", request.url.parameters["is_archived"])
            val page = request.url.parameters["page"]!!.toInt()
            seenPages += page
            respond(
                content = if (page == 1) {
                    """{"items":[{"id":1,"title":"Active"}],"total":2,"page":1,"per_page":100,"total_pages":2}"""
                } else {
                    """{"items":[{"id":2,"title":"Archived","is_archived":true}],"total":2,"page":2,"per_page":100,"total_pages":2}"""
                },
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val projects = service(engine).getAllProjects(includeArchived = true)

        assertEquals(listOf(1, 2), seenPages)
        assertFalse(projects.first().isArchived)
        assertTrue(projects.last().isArchived)
    }

    @Test
    fun `api token list unwraps pagination items`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/tokens", request.url.encodedPath)
            respond(
                content = """{"items":[{"id":41,"title":"Phone"}],"total":1,"page":1,"per_page":100,"total_pages":1}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val tokens = service(engine).listApiTokens()

        assertEquals(1, tokens.size)
        assertEquals(41, tokens.single().id)
    }

    @Test
    fun `problem json detail and code are exposed for 422 validation`() = runTest {
        val engine = MockEngine {
            assertEquals(listOf("subtasks"), it.url.parameters.getAll("expand"))
            respond(
                content = """
                    {
                      "title":"Unprocessable Entity",
                      "status":422,
                      "detail":"The priority is invalid.",
                      "code":2002,
                      "errors":[{"location":"body.priority","message":"must be at most 5","value":9}]
                    }
                """.trimIndent(),
                status = HttpStatusCode.UnprocessableEntity,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/problem+json")
                },
            )
        }

        val error = assertFailsWith<VikunjaApiException> {
            service(engine).getTask(9)
        }

        assertEquals(422, error.httpStatus)
        assertEquals(2002, error.problem?.code)
        assertEquals("The priority is invalid.", error.message)
        assertTrue(error.problem?.errors?.isNotEmpty() == true)
    }
}
