package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.MutableTimeSource
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Creating a task returns as soon as the server has it (A-DATA-5). Putting the new task at the
 * end of the list (the server prepends new tasks) runs afterwards in the background, with the
 * list view id and the last position of each project remembered between creates.
 */
class TaskRepositoryCreatePositionTest {

    /** A server with one project (7), a list view (70) whose last task sits at [lastPosition]. */
    private class Server(var lastPosition: Double = 1_000.0) {
        var nextId = 55L
        var viewsGate: CompletableDeferred<Unit>? = null
        var failPosition = false
        var views = listOf(
            """{"id":69,"project_id":7,"title":"Gantt","view_kind":"gantt"}""",
            """{"id":70,"project_id":7,"title":"List","view_kind":"list"}""",
        )

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
            val path = request.url.encodedPath
            when {
                request.method.value == "POST" && path == "/projects/7/tasks" -> {
                    val id = nextId++
                    respond("""{"id":$id,"title":"Created $id","project_id":7}""", HttpStatusCode.Created, authTestJsonHeaders)
                }
                request.method.value == "GET" && path == "/projects/7/views" -> {
                    viewsGate?.await()
                    jsonOk("""{"items":[${views.joinToString(",")}],"total":${views.size},"page":1,"per_page":100,"total_pages":1}""")
                }
                request.method.value == "GET" && path == "/projects/7/views/70/tasks" ->
                    jsonOk("""{"items":[{"id":1,"title":"Last","project_id":7,"position":$lastPosition}],"total":1,"page":1,"per_page":1,"total_pages":1}""")
                request.method.value == "PATCH" && path == "/tasks/42" ->
                    jsonOk("""{"id":42,"title":"Original","project_id":8}""")
                request.method.value == "PUT" && path.endsWith("/position") ->
                    if (failPosition) respond("", HttpStatusCode.InternalServerError) else jsonOk("{}")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun harness(
        server: Server,
        time: MutableTimeSource = MutableTimeSource(Instant.parse("2026-10-06T12:00:00Z")),
    ) = TaskRepositoryHarness(positionerTime = time) { request -> server.handle(this, request) }

    private fun TaskRepositoryHarness.positionPuts() = sent.filter { it.method == "PUT" && it.path.endsWith("/position") }
    private fun TaskRepositoryHarness.count(method: String, path: String) = sent.count { it.method == method && it.path == path }
    private fun TaskRepositoryHarness.positions() = positionPuts().map { it.bodyJson!!.getValue("position").jsonPrimitive.double }

    private suspend fun TaskRepositoryHarness.createIn(projectId: Long = 7) =
        repository.create(Task(id = 0, title = "New", projectId = projectId))

    @Test
    fun `create returns before the position requests have finished`() = runTest {
        val server = Server().apply { viewsGate = CompletableDeferred() }
        val h = harness(server)

        val result = h.createIn()

        assertIs<NetworkResult.Success<Task>>(result)
        assertEquals(55L, result.data.id)
        assertTrue(h.positionPuts().isEmpty(), "the position is not set yet")
        assertTrue(h.taskDao.entity(55) != null, "the task is stored and can be shown")

        server.viewsGate!!.complete(Unit)
        h.positioner.awaitIdle()

        assertEquals(listOf(1_000.0 + 65_536.0), h.positions())
        val put = h.positionPuts().single()
        assertEquals("/tasks/55/position", put.path)
        assertEquals(70L, put.bodyJson!!.getValue("project_view_id").jsonPrimitive.content.toLong(), "the list view, not the gantt view")
    }

    @Test
    fun `a failing position request does not fail the create`() = runTest {
        val server = Server().apply { failPosition = true }
        val h = harness(server)

        val result = h.createIn()
        h.positioner.awaitIdle()

        assertIs<NetworkResult.Success<Task>>(result)
        assertEquals(1, h.positionPuts().size)
    }

    @Test
    fun `the list view and the last position are remembered between creates`() = runTest {
        val h = harness(Server())

        repeat(3) { h.createIn() }
        h.positioner.awaitIdle()

        assertEquals(1, h.count("GET", "/projects/7/views"), "views are fetched once")
        assertEquals(1, h.count("GET", "/projects/7/views/70/tasks"), "the last position is fetched once")
        assertEquals(listOf(66_536.0, 132_072.0, 197_608.0), h.positions(), "each one after the previous")
    }

    @Test
    fun `quick creates in a row get increasing positions`() = runTest {
        val server = Server().apply { viewsGate = CompletableDeferred() }
        val h = harness(server)

        h.createIn()
        h.createIn()
        h.createIn()
        server.viewsGate!!.complete(Unit)
        h.positioner.awaitIdle()

        assertEquals(listOf(66_536.0, 132_072.0, 197_608.0), h.positions())
    }

    @Test
    fun `the remembered last position expires but the view id stays`() = runTest {
        val time = MutableTimeSource(Instant.parse("2026-10-06T12:00:00Z"))
        val server = Server()
        val h = harness(server, time)
        h.createIn()
        h.positioner.awaitIdle()

        // Another device added a task further down in the meantime.
        server.lastPosition = 900_000.0
        time.advance(6.minutes)
        h.createIn()
        h.positioner.awaitIdle()

        assertEquals(1, h.count("GET", "/projects/7/views"))
        assertEquals(2, h.count("GET", "/projects/7/views/70/tasks"))
        assertEquals(900_000.0 + 65_536.0, h.positions().last())
    }

    @Test
    fun `a failed position request forgets what was remembered about the project`() = runTest {
        val server = Server()
        val h = harness(server)
        h.createIn()
        h.positioner.awaitIdle()
        server.failPosition = true
        h.createIn()
        h.positioner.awaitIdle()
        server.failPosition = false

        h.createIn()
        h.positioner.awaitIdle()

        assertEquals(2, h.count("GET", "/projects/7/views"), "fetched again after the failure")
        assertEquals(2, h.count("GET", "/projects/7/views/70/tasks"))
    }

    @Test
    fun `moving a task to another project forgets both projects`() = runTest {
        val server = Server()
        val h = harness(server)
        h.taskDao.upsert(cachedTaskEntity(id = 42, projectId = 7))
        h.createIn()
        h.positioner.awaitIdle()
        val topFetchesBefore = h.count("GET", "/projects/7/views/70/tasks")

        // The move itself fails to reach the server and is queued; the project still changed here.
        h.repository.moveToProject(42, 8)
        h.createIn()
        h.positioner.awaitIdle()

        assertEquals(topFetchesBefore + 1, h.count("GET", "/projects/7/views/70/tasks"), "the last position of project 7 was looked up again")
    }

    @Test
    fun `a project without a list view is remembered too`() = runTest {
        val server = Server().apply { views = listOf("""{"id":69,"project_id":7,"title":"Gantt","view_kind":"gantt"}""") }
        val h = harness(server)

        repeat(3) { h.createIn() }
        h.positioner.awaitIdle()

        assertEquals(1, h.count("GET", "/projects/7/views"))
        assertTrue(h.positionPuts().isEmpty())
    }

    @Test
    fun `a manual reorder uses the remembered view and raises the remembered last position`() = runTest {
        val h = harness(Server())
        h.createIn()
        h.positioner.awaitIdle()
        h.clearSent()

        h.repository.updatePosition(taskId = 3, projectId = 7, newPosition = 500_000.0)
        h.createIn()
        h.positioner.awaitIdle()

        assertEquals(0, h.count("GET", "/projects/7/views"), "no views request for the reorder or the create")
        assertEquals(listOf(500_000.0, 500_000.0 + 65_536.0), h.positions())
    }

    @Test
    fun `the cache is dropped when the account's data is wiped`() = runTest {
        val h = harness(Server())
        h.createIn()
        h.positioner.awaitIdle()

        h.positioner.clear()
        h.createIn()
        h.positioner.awaitIdle()

        assertEquals(2, h.count("GET", "/projects/7/views"))
    }
}
