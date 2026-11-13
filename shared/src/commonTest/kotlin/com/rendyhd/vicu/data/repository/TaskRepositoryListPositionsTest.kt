package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrder
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.POSITION_STEP
import com.rendyhd.vicu.util.PositionUpdate
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
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
        var failViewTasksOnCall: Set<Int> = emptySet()
        var failViews = false
        var positionGate: CompletableDeferred<Unit>? = null
        var hasListView = true
        var positionCalls = 0
        var nextId = 55L
        val viewTaskQueries = mutableListOf<Map<String, String>>()

        private fun requestPosition(request: HttpRequestData): Double =
            Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("position").jsonPrimitive.double

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
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
                request.method.value == "GET" && path == "/projects/7/views" && failViews ->
                    respond("", HttpStatusCode.InternalServerError)
                request.method.value == "GET" && path == "/projects/7/views" -> {
                    val view = if (hasListView) """{"id":70,"project_id":7,"title":"List","view_kind":"list"}""" else ""
                    val items = listOf(view).filter { it.isNotEmpty() }
                    jsonOk("""{"items":[${items.joinToString(",")}],"total":${items.size},"page":1,"per_page":100,"total_pages":1}""")
                }
                request.method.value == "GET" && path == "/projects/7/views/70/tasks" -> {
                    viewTaskQueries += request.url.parameters.entries().associate { it.key to it.value.first() }
                    if (failViewTasks || viewTaskQueries.size in failViewTasksOnCall) {
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
                    positionGate?.await()
                    if (positionCalls in failPositionOnCall) {
                        respond("", HttpStatusCode.InternalServerError)
                    } else {
                        // What arrived is what the list view reports from now on.
                        val id = path.removeSuffix("/position").substringAfterLast('/').toLong()
                        val position = requestPosition(request)
                        viewTasks = viewTasks.map { if (it.first == id) id to position else it }
                        jsonOk("{}")
                    }
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
    fun `a drag the server refuses is reported - and the server's order shows again`() = runTest {
        val server = Server().apply {
            failPositionOnCall = setOf(1)
            viewTasks = listOf(3L to 10.0)
        }
        val h = harness(server, cached = listOf(Task(id = 3, title = "T", position = 10.0)))

        val result = h.repository.applyPositions(7, listOf(PositionUpdate(3, 500.0)))

        assertIs<NetworkResult.Error>(result)
        assertEquals(10.0, h.taskDao.entity(3)!!.position)
    }

    @Test
    fun `a refused drag whose list cannot be read puts the order from before back`() = runTest {
        val server = Server().apply {
            failPositionOnCall = setOf(1)
            failViewTasks = true
        }
        val h = harness(server, cached = listOf(Task(id = 3, title = "T", position = 10.0)))

        val result = h.repository.applyPositions(7, listOf(PositionUpdate(3, 500.0)))

        assertIs<NetworkResult.Error>(result)
        assertEquals(10.0, h.taskDao.entity(3)!!.position)
    }

    @Test
    fun `a renumbering drag sends the updates in the order given and stops at the first refusal`() = runTest {
        val server = Server().apply {
            failPositionOnCall = setOf(2)
            viewTasks = listOf(1L to 0.0, 2L to 0.0, 3L to 0.0)
        }
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
        // What reached the server shows; the rest of the order is the server's again.
        assertEquals(listOf(65_536.0, 0.0, 0.0), (1L..3L).map { h.taskDao.entity(it)!!.position })
    }

    // --- a drop on the project screen: planned from a read made for it ---

    private fun rows(vararg ids: Long) = ids.map { Task(id = it, title = "T$it", projectId = 7) }

    @Test
    fun `a drop is planned from the server's positions - not the stored zeros`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 100.0, 2L to 200.0, 3L to 300.0) }
        val h = harness(server, cached = rows(1, 2, 3))

        // 3 dragged between 1 and 2; every stored position is 0.
        val result = h.repository.dropInListView(7, rows(1, 3, 2), taskId = 3, TaskOrder.DEFAULT)

        assertIs<NetworkResult.Success<Unit>>(result)
        assertEquals(listOf(3L to 150.0), h.putPositions(), "one position, between the real neighbours")
        assertEquals(listOf(100.0, 200.0, 150.0), (1L..3L).map { h.taskDao.entity(it)!!.position })
    }

    @Test
    fun `a drop among tasks really tied at 0 spreads them and never sends 0`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 0.0, 2L to 0.0, 3L to 0.0) }
        val h = harness(server, cached = rows(1, 2, 3))

        h.repository.dropInListView(7, rows(1, 3, 2), taskId = 3, TaskOrder.DEFAULT)

        assertEquals(listOf(1L to 65_536.0, 2L to 196_608.0, 3L to 131_072.0), h.putPositions(), "the dragged task last")
    }

    @Test
    fun `a drop whose read fails sends and stores nothing and says why`() = runTest {
        val server = Server().apply { failViewTasks = true }
        val h = harness(server, cached = rows(1, 2, 3))

        val result = h.repository.dropInListView(7, rows(1, 3, 2), taskId = 3, TaskOrder.DEFAULT)

        assertEquals("Could not read the order of this list, so the task stayed where it was", assertIs<NetworkResult.Error>(result).message)
        assertTrue(h.putPositions().isEmpty(), "no position worked out from values nobody read")
        assertEquals(listOf(0.0, 0.0, 0.0), (1L..3L).map { h.taskDao.entity(it)!!.position })
    }

    @Test
    fun `a drop while the views cannot be fetched is refused the same way`() = runTest {
        val server = Server().apply { failViews = true }
        val h = harness(server, cached = rows(1, 2))

        val result = h.repository.dropInListView(7, rows(2, 1), taskId = 2, TaskOrder.DEFAULT)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.putPositions().isEmpty())
    }

    @Test
    fun `a drop that fails partway shows what the server holds afterwards`() = runTest {
        val server = Server().apply {
            viewTasks = listOf(1L to 0.0, 2L to 0.0, 3L to 0.0)
            failPositionOnCall = setOf(2)
        }
        val h = harness(server, cached = rows(1, 2, 3))

        val result = h.repository.dropInListView(7, rows(1, 3, 2), taskId = 3, TaskOrder.DEFAULT)

        assertEquals("Could not save the new order", assertIs<NetworkResult.Error>(result).message)
        assertEquals(listOf(1L to 65_536.0, 2L to 196_608.0), h.putPositions(), "the dragged task was never sent")
        assertEquals(2, server.viewTaskQueries.size, "read once to plan and once to show the outcome")
        // The first update reached the server; the second did not.
        assertEquals(listOf(65_536.0, 0.0, 0.0), (1L..3L).map { h.taskDao.entity(it)!!.position })
    }

    @Test
    fun `a drop that fails and cannot be read back goes back to the order before it`() = runTest {
        val server = Server().apply {
            viewTasks = listOf(1L to 100.0, 2L to 200.0, 3L to 300.0)
            failPositionOnCall = setOf(1)
            failViewTasksOnCall = setOf(2)
        }
        val h = harness(server, cached = rows(1, 2, 3))

        val result = h.repository.dropInListView(7, rows(1, 3, 2), taskId = 3, TaskOrder.DEFAULT)

        assertIs<NetworkResult.Error>(result)
        assertEquals(listOf(100.0, 200.0, 300.0), (1L..3L).map { h.taskDao.entity(it)!!.position })
    }

    @Test
    fun `a task the list view no longer holds gives a drop nothing to plan from`() = runTest {
        // 2 was completed or moved away on another device.
        val server = Server().apply { viewTasks = listOf(1L to 100.0, 3L to 300.0) }
        val h = harness(server, cached = rows(1, 2, 3))

        h.repository.dropInListView(7, rows(1, 2, 3), taskId = 3, TaskOrder.DEFAULT)

        assertEquals(listOf(3L to 100.0 + 65_536.0), h.putPositions(), "one step past 1, the row above that is really there")
    }

    @Test
    fun `dropping a task the list view no longer holds is an error and sends nothing`() = runTest {
        val server = Server().apply { viewTasks = listOf(1L to 100.0) }
        val h = harness(server, cached = rows(1, 2))

        val result = h.repository.dropInListView(7, rows(2, 1), taskId = 2, TaskOrder.DEFAULT)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.putPositions().isEmpty())
    }

    @Test
    fun `a project without a list view orders its rows here and sends nothing`() = runTest {
        val server = Server().apply { hasListView = false }
        val h = harness(server, cached = rows(1, 2))

        val result = h.repository.dropInListView(7, rows(2, 1), taskId = 2, TaskOrder.DEFAULT)

        assertIs<NetworkResult.Success<Unit>>(result)
        assertTrue(h.putPositions().isEmpty())
        assertEquals(POSITION_STEP, h.taskDao.entity(2)!!.position)
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
    fun `a read waits while a new task is put at the end - so it cannot store the older position over it`() = runTest {
        val server = Server().apply {
            viewTasks = listOf(1L to 1_000.0)
            positionGate = CompletableDeferred()
        }
        val h = harness(server)
        h.repository.create(Task(id = 0, title = "New", projectId = 7))
        val anchored = 1_000.0 + 65_536.0
        fun reads() = h.sent.count { it.method == "GET" && it.path == "/projects/7/views/70/tasks" }
        // The task's place is being set: its request is at the server, held by the gate.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (h.putPositions().isEmpty()) delay(5) }
        }
        val readsToPlaceIt = reads()
        // The server still has the new task at the top, where it first put it.
        server.viewTasks = listOf(55L to 500.0, 1L to 1_000.0)

        val read = launch(Dispatchers.Unconfined) { h.repository.refreshListPositions(7) }
        withContext(Dispatchers.Default) { delay(100) }
        assertEquals(readsToPlaceIt, reads(), "no read goes out while the task's place is being set")

        server.positionGate!!.complete(Unit)
        h.positioner.awaitIdle()
        read.join()

        assertEquals(readsToPlaceIt + 1, reads())
        assertEquals(anchored, h.taskDao.entity(55)!!.position)
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
