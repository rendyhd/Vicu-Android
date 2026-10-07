package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionUpdate
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Manual order of a list view: where the positions come from (the list view endpoint, the only
 * place the server states them), where they are stored (the cached rows) and how a drag reaches
 * the server.
 */
class TaskRepositoryListPositionsTest {

    /** A server with project 7, a list view (70), and the tasks the list view reports. */
    private class Server {
        var viewTasks = emptyList<Pair<Long, Double>>()
        var failPositionOnCall: Set<Int> = emptySet()
        var failViewTasks = false
        var hasListView = true
        var positionCalls = 0
        var nextId = 55L
        val viewTaskQueries = mutableListOf<Map<String, String>>()

        fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
            val path = request.url.encodedPath
            when {
                request.method.value == "POST" && path == "/projects/7/tasks" -> {
                    val id = nextId++
                    respond("""{"id":$id,"title":"Created $id","project_id":7}""", HttpStatusCode.Created, authTestJsonHeaders)
                }
                request.method.value == "PATCH" && path == "/tasks/3" ->
                    jsonOk("""{"id":3,"title":"T","project_id":8}""")
                request.method.value == "POST" && path.endsWith("/relations") ->
                    respond("{}", HttpStatusCode.Created, authTestJsonHeaders)
                request.method.value == "GET" && path == "/projects/7/views" -> {
                    val view = if (hasListView) """{"id":70,"project_id":7,"title":"List","view_kind":"list"}""" else ""
                    val items = listOf(view).filter { it.isNotEmpty() }
                    jsonOk("""{"items":[${items.joinToString(",")}],"total":${items.size},"page":1,"per_page":100,"total_pages":1}""")
                }
                request.method.value == "GET" && path == "/projects/7/views/70/tasks" -> {
                    viewTaskQueries += request.url.parameters.entries().associate { it.key to it.value.first() }
                    if (failViewTasks) {
                        respond("", HttpStatusCode.InternalServerError)
                    } else {
                        val items = viewTasks.joinToString(",") { (id, position) ->
                            """{"id":$id,"title":"T$id","project_id":7,"position":$position}"""
                        }
                        jsonOk("""{"items":[$items],"total":${viewTasks.size},"page":1,"per_page":100,"total_pages":1}""")
                    }
                }
                request.method.value == "PUT" && path.endsWith("/position") -> {
                    positionCalls++
                    if (positionCalls in failPositionOnCall) respond("", HttpStatusCode.InternalServerError) else jsonOk("{}")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun harness(server: Server, cached: List<Task> = emptyList()) = TaskRepositoryHarness(
        taskDao = FakeTaskDao(cached.map { cachedTaskEntity(id = it.id, projectId = 7).copy(position = it.position) }),
    ) { request -> server.handle(this, request) }

    private fun TaskRepositoryHarness.putPositions() =
        sent.filter { it.method == "PUT" && it.path.endsWith("/position") }
            .map { it.path.removeSuffix("/position").substringAfterLast('/').toLong() to it.bodyJson!!.getValue("position").jsonPrimitive.double }

    // --- where a new task goes ---

    @Test
    fun `the end position given to a new task is stored on the cached row`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 1_000.0) }
        val h = harness(server)

        h.repository.create(Task(id = 0, title = "New", projectId = 7))
        h.positioner.awaitIdle()

        assertEquals(1_000.0 + 65_536.0, h.taskDao.entity(55)!!.position)
    }

    @Test
    fun `a subtask created online is put at the end of its list and gets its alarms`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 1_000.0) }
        val h = harness(server, cached = listOf(Task(id = 9, title = "Parent")))

        val result = h.repository.createSubtask(9, Task(id = 0, title = "Sub", projectId = 7))
        h.positioner.awaitIdle()

        assertIs<NetworkResult.Success<Task>>(result)
        assertEquals(listOf(55L to 1_000.0 + 65_536.0), h.putPositions())
        assertEquals(1_000.0 + 65_536.0, h.taskDao.entity(55)!!.position)
        assertTrue(55L in h.hooks.scheduled, "the subtask's reminders are armed like any new task's")
    }

    // --- a drag ---

    @Test
    fun `a drag stores the position locally and sends it to the list view`() = runTest {
        val server = Server()
        val h = harness(server, cached = listOf(Task(id = 3, title = "T", position = 10.0)))

        val result = h.repository.applyPositions(7, listOf(PositionUpdate(3, 500.0)))

        assertIs<NetworkResult.Success<Unit>>(result)
        assertEquals(500.0, h.taskDao.entity(3)!!.position)
        assertEquals(listOf(3L to 500.0), h.putPositions())
    }

    @Test
    fun `a drag the server refuses is reported, and the local order stays until a refresh`() = runTest {
        val server = Server().apply { failPositionOnCall = setOf(1) }
        val h = harness(server, cached = listOf(Task(id = 3, title = "T", position = 10.0)))

        val result = h.repository.applyPositions(7, listOf(PositionUpdate(3, 500.0)))

        assertIs<NetworkResult.Error>(result)
        assertEquals(500.0, h.taskDao.entity(3)!!.position)
    }

    @Test
    fun `a renumbering drag sends the updates in the order given and stops at the first refusal`() = runTest {
        val server = Server().apply { failPositionOnCall = setOf(2) }
        val h = harness(
            server,
            cached = listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B"), Task(id = 3, title = "C")),
        )

        val result = h.repository.applyPositions(
            7,
            listOf(PositionUpdate(1, 65_536.0), PositionUpdate(3, 196_608.0), PositionUpdate(2, 131_072.0)),
        )

        assertIs<NetworkResult.Error>(result)
        assertEquals(listOf(1L to 65_536.0, 3L to 196_608.0), h.putPositions(), "the third was never sent")
        assertEquals(listOf(65_536.0, 131_072.0, 196_608.0), (1L..3L).map { h.taskDao.entity(it)!!.position })
    }

    @Test
    fun `the single task reorder of the project screen still works`() = runTest {
        val server = Server()
        val h = harness(server, cached = listOf(Task(id = 3, title = "T")))

        val result = h.repository.updatePosition(taskId = 3, projectId = 7, newPosition = 42.0)

        assertIs<NetworkResult.Success<Unit>>(result)
        assertEquals(listOf(3L to 42.0), h.putPositions())
    }

    @Test
    fun `a task moved to another project forgets the position it had in the old one`() = runTest {
        val server = Server()
        val h = harness(server, cached = listOf(Task(id = 3, title = "T", position = 500_000.0)))

        h.repository.update(Task(id = 3, title = "T", projectId = 8))

        assertEquals(0.0, h.taskDao.entity(3)!!.position, "a position only means something in its own list view")
    }

    // --- reading the order from the server ---

    @Test
    fun `refreshing a list view stores the server's positions on the cached tasks`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 300.0, 2L to 100.0, 3L to 200.0) }
        val h = harness(server, cached = listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B"), Task(id = 3, title = "C")))

        val result = h.repository.refreshListPositions(7)

        assertIs<NetworkResult.Success<Unit>>(result)
        assertEquals(listOf(300.0, 100.0, 200.0), (1L..3L).map { h.taskDao.entity(it)!!.position })
        val query = server.viewTaskQueries.first()
        assertEquals("done = false", query["filter"])
        assertEquals("position", query["sort_by"])
    }

    @Test
    fun `a task the cache does not hold is not invented from the list view`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 300.0, 99L to 100.0) }
        val h = harness(server, cached = listOf(Task(id = 1, title = "A")))

        h.repository.refreshListPositions(7)

        assertEquals(null, h.taskDao.entity(99))
        assertEquals(300.0, h.taskDao.entity(1)!!.position)
    }

    @Test
    fun `positions that did not change are not written again`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 300.0, 2L to 100.0) }
        val dao = FakeTaskDao(
            listOf(
                cachedTaskEntity(id = 1, projectId = 7).copy(position = 300.0),
                cachedTaskEntity(id = 2, projectId = 7).copy(position = 0.0),
            ),
        )
        val h = TaskRepositoryHarness(taskDao = dao) { request -> server.handle(this, request) }

        h.repository.refreshListPositions(7)

        assertEquals(listOf(2L), dao.updatedPositionIds, "only the row that differs")
    }

    @Test
    fun `a project without a list view has nothing to refresh`() = runTest {
        val server = Server().apply { hasListView = false }
        val h = harness(server, cached = listOf(Task(id = 1, title = "A", position = 5.0)))

        val result = h.repository.refreshListPositions(7)

        assertIs<NetworkResult.Success<Unit>>(result)
        assertEquals(5.0, h.taskDao.entity(1)!!.position)
        assertTrue(server.viewTaskQueries.isEmpty())
    }

    @Test
    fun `a failed refresh of the order is an error and changes nothing`() = runTest {
        val server = Server().apply { failViewTasks = true }
        val h = harness(server, cached = listOf(Task(id = 1, title = "A", position = 5.0)))

        val result = h.repository.refreshListPositions(7)

        assertIs<NetworkResult.Error>(result)
        assertEquals(5.0, h.taskDao.entity(1)!!.position)
    }
}
