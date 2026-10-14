package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the phone does with changes made on the server behind its back: a task deleted there
 * leaves no ghost row once a write to it answers 404, and a repeating task's completion that the
 * server advanced is undone by moving its dates back.
 */
class TaskRepositoryServerSideChangesTest {

    private val open = cachedTaskEntity(id = 42).copy(done = false, relatedTasksJson = "{}")

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: Int?): HttpResponseData =
        respond(
            content = if (code == null) "" else """{"title":"x","status":${status.value},"detail":"d","code":$code}""",
            status = status,
            headers = authTestJsonHeaders,
        )

    private fun harness(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(open)), handler = handler)

    // ---- ghost rows ------------------------------------------------------------------------------

    @Test
    fun `saving an edit of a task deleted on the server removes the ghost row`() = runTest {
        val h = harness { problem(HttpStatusCode.NotFound, 4002) }

        val result = h.repository.update(Task(id = 42, title = "Edited", projectId = 7, priority = 2, dueDate = open.dueDate))

        assertIs<NetworkResult.Error>(result)
        assertNull(h.taskDao.entity(42), "the row is gone, so it cannot be tapped and fail again")
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `completing a task deleted on the server removes the ghost row`() = runTest {
        val h = harness { problem(HttpStatusCode.NotFound, 4002) }

        val result = h.repository.setDone(42, true)

        assertIs<NetworkResult.Error>(result)
        assertNull(h.taskDao.entity(42))
        assertTrue(42L in h.hooks.cancelled, "its reminders go with it")
    }

    @Test
    fun `a bare 404 counts once asking for the task again says it is gone`() = runTest {
        val h = harness { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/user" -> jsonOk("""{"id":1,"username":"u"}""")
                else -> problem(HttpStatusCode.NotFound, null)
            }
        }

        h.repository.setDone(42, true)

        assertNull(h.taskDao.entity(42))
    }

    @Test
    fun `a bare 404 from a server that answers nothing else keeps the row`() = runTest {
        val h = harness { problem(HttpStatusCode.NotFound, null) }

        h.repository.setDone(42, true)

        assertNotNull(h.taskDao.entity(42), "a proxy or a wrong base path answers 404 to everything")
    }

    @Test
    fun `a 404 about another resource keeps the row`() = runTest {
        // Moving the task into a project that no longer exists.
        val h = harness { problem(HttpStatusCode.NotFound, 3001) }

        val result = h.repository.update(Task(id = 42, title = "Original", projectId = 99, priority = 2, dueDate = open.dueDate))

        assertIs<NetworkResult.Error>(result)
        assertNotNull(h.taskDao.entity(42))
        assertEquals(7L, h.taskDao.entity(42)!!.projectId, "the failed move is rolled back")
    }

    // ---- undo of a repeating task's completion ---------------------------------------------------------

    private val advancedDue = "2026-10-15T21:59:59Z"

    private fun repeating(): TaskRepositoryHarness {
        var call = 0
        return harness { request ->
            call++
            if (call == 1) {
                // Completing a repeating task: the server answers open, with the next due date.
                jsonOk("""{"id":42,"title":"Original","project_id":7,"done":false,"due_date":"$advancedDue","repeat_after":604800}""")
            } else {
                jsonOk("""{"id":42,"title":"Original","project_id":7,"done":false,"due_date":"${open.dueDate}","repeat_after":604800}""")
            }
        }
    }

    @Test
    fun `undoing the completion of a repeating task moves its due date back`() = runTest {
        val h = repeating()

        h.repository.setDone(42, true)
        assertEquals(advancedDue, h.taskDao.entity(42)!!.dueDate, "the server moved it on")
        val undo = h.repository.setDone(42, false)

        assertIs<NetworkResult.Success<*>>(undo)
        val restore = h.patches().last()
        assertEquals(2, h.patches().size)
        assertEquals(JsonObject(mapOf("due_date" to JsonPrimitive(open.dueDate))), restore.bodyJson)
        assertEquals(open.dueDate, h.taskDao.entity(42)!!.dueDate)
    }

    @Test
    fun `undo twice restores the dates once`() = runTest {
        val h = repeating()

        h.repository.setDone(42, true)
        h.repository.setDone(42, false)
        h.repository.setDone(42, false)

        assertEquals(2, h.patches().size, "the second undo finds nothing to put back")
    }

    @Test
    fun `undo leaves a due date the user changed after the completion alone`() = runTest {
        val h = repeating()

        h.repository.setDone(42, true)
        h.taskDao.upsert(h.taskDao.entity(42)!!.copy(dueDate = "2026-11-01T21:59:59Z"))
        h.repository.setDone(42, false)

        assertEquals(1, h.patches().size, "only the completion was sent")
        assertEquals("2026-11-01T21:59:59Z", h.taskDao.entity(42)!!.dueDate)
    }

    @Test
    fun `a task that is not repeating has nothing to put back`() = runTest {
        // The server answers a completion with the task done and the same dates.
        val h = harness { jsonOk("""{"id":42,"title":"Original","project_id":7,"done":true,"due_date":"${open.dueDate}"}""") }

        h.repository.setDone(42, true)
        h.repository.setDone(42, false)

        // Completion, then the reopen as a plain done=false.
        assertEquals(listOf(true, false), h.patches().map { (it.bodyJson!!["done"] as JsonPrimitive).content.toBoolean() })
    }
}
