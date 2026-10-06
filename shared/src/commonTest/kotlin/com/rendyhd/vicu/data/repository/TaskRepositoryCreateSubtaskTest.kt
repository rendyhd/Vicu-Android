package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RelationKind
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Creating a subtask works offline: it is stored and queued, and linked to its parent on sync. */
class TaskRepositoryCreateSubtaskTest {

    private val child = Task(id = 0, title = "Write the outline", description = "<p>by Friday</p>", projectId = 7)

    private fun MockRequestHandleScope.createdTask(): HttpResponseData = respond(
        content = """{"id":501,"title":"Write the outline","description":"<p>by Friday</p>","project_id":7}""",
        status = HttpStatusCode.Created,
        headers = com.rendyhd.vicu.auth.authTestJsonHeaders,
    )

    private fun parentIdsOf(task: Task) = task.relatedTasks[RelationKind.PARENTTASK].orEmpty().map { it.id }

    @Test
    fun `offline, the subtask is stored under its parent and a create is queued`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(cachedTaskEntity(id = 42)))) { serviceUnavailable() }

        val result = h.repository.createSubtask(42, child)

        val created = (result as NetworkResult.Success).data
        assertTrue(created.id < 0, "a temporary id until the server has it")
        assertEquals(listOf(42L), parentIdsOf(created))

        val stored = assertNotNull(h.taskDao.snapshot().firstOrNull { it.id == created.id })
        assertEquals("Write the outline", stored.title)
        val parent = with(h.mapper) { h.taskDao.snapshot().first { it.id == 42L }.toDomain() }
        assertTrue(
            created.id in parent.relatedTasks[RelationKind.SUBTASK].orEmpty().map { it.id },
            "the parent lists it as a subtask, so the UI shows it there",
        )

        val action = h.pendingActionDao.snapshot().single()
        assertEquals("create", action.actionType)
        assertEquals(created.id, action.entityId)
        val queued = authTestJson.decodeFromString(Task.serializer(), action.payload)
        assertEquals(listOf(42L), parentIdsOf(queued), "the queued create names the parent")
        assertEquals("<p>by Friday</p>", queued.description)
    }

    @Test
    fun `a task created on the server that cannot be linked is queued again instead of lost`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(cachedTaskEntity(id = 42)))) { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" -> createdTask()
                else -> serviceUnavailable() // the relation request
            }
        }

        val result = h.repository.createSubtask(42, child)

        assertTrue((result as NetworkResult.Success).data.id < 0)
        assertEquals(1, h.pendingActionDao.snapshot().size)
        assertTrue(h.taskDao.snapshot().none { it.id == 501L }, "no second local copy next to the queued one")
    }

    @Test
    fun `a parent that only exists offline gets its subtask queued without any request`() = runTest {
        val h = TaskRepositoryHarness(
            taskDao = FakeTaskDao(listOf(cachedTaskEntity(id = -5, projectId = 7))),
        ) { error("no request expected, got ${it.url.encodedPath}") }

        val result = h.repository.createSubtask(-5, child)

        val created = (result as NetworkResult.Success).data
        assertEquals(listOf(-5L), parentIdsOf(created))
        assertTrue(h.sent.isEmpty())
        assertEquals("create", h.pendingActionDao.snapshot().single().actionType)
    }

    @Test
    fun `a request the server rejects is an error and nothing is queued`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(cachedTaskEntity(id = 42)))) {
            respond("", HttpStatusCode.BadRequest)
        }

        val result = h.repository.createSubtask(42, child)

        assertTrue(result is NetworkResult.Error)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `online, the subtask is created and linked on the server as before`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(cachedTaskEntity(id = 42)))) { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" -> createdTask()
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/42/relations" ->
                    respond("{}", HttpStatusCode.Created, com.rendyhd.vicu.auth.authTestJsonHeaders)
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }

        val result = h.repository.createSubtask(42, child)

        assertEquals(501L, (result as NetworkResult.Success).data.id)
        assertEquals(listOf(42L), parentIdsOf(result.data))
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertEquals(listOf("POST /projects/7/tasks", "POST /tasks/42/relations"), h.sent.map { "${it.method} ${it.path}" })
    }
}
