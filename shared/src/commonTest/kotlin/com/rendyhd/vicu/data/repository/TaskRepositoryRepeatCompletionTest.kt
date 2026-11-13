package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RepeatCompletion
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A repeating task completed on two devices is moved on once (cross-app semantics 7.5): before
 * the completion of a task the phone holds as repeating is sent, the task is read, and when
 * another device completed this occurrence already nothing is sent.
 */
class TaskRepositoryRepeatCompletionTest {

    private val due = "2026-11-13T22:59:59Z"
    private val repeating = cachedTaskEntity(id = 42, dueDate = due)
        .copy(done = false, relatedTasksJson = "{}", repeatAfter = 86_400)

    private fun serverTask(dueDate: String, done: Boolean = false) =
        """{"id":42,"title":"Take pills","project_id":7,"done":$done,"repeat_after":86400,"due_date":"$dueDate"}"""

    @Test
    fun `the same occurrence on the server is completed`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(repeating))) { request ->
            if (request.method == HttpMethod.Get) jsonOk(serverTask(due)) else jsonOk(serverTask("2026-11-14T22:59:59Z"))
        }

        val result = h.repository.setDone(42, true)

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(listOf("GET /tasks/42", "PATCH /tasks/42"), h.sent.map { "${it.method} ${it.path}" })
        assertEquals(JsonObject(mapOf("done" to JsonPrimitive(true))), h.patches().single().bodyJson)
        assertEquals("2026-11-14T22:59:59Z", h.taskDao.entity(42)!!.dueDate)
    }

    @Test
    fun `an occurrence completed elsewhere is not completed again`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(repeating))) { request ->
            if (request.method == HttpMethod.Get) jsonOk(serverTask("2026-11-14T22:59:59Z")) else error("Nothing may be sent")
        }

        val result = h.repository.toggleDone(h.mapper.run { repeating.toDomain() })

        assertIs<NetworkResult.Success<*>>(result, "a skip is not an error")
        assertTrue(h.patches().isEmpty())
        val stored = h.taskDao.entity(42)!!
        assertFalse(stored.done)
        assertEquals("2026-11-14T22:59:59Z", stored.dueDate, "the server's task is shown")
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertEquals(listOf(42L), h.hooks.scheduled)
    }

    @Test
    fun `a task done on the server is not sent again`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(repeating))) { request ->
            if (request.method == HttpMethod.Get) jsonOk(serverTask(due, done = true)) else error("Nothing may be sent")
        }

        h.repository.setDone(42, true)

        assertTrue(h.patches().isEmpty())
        assertTrue(h.taskDao.entity(42)!!.done)
    }

    @Test
    fun `offline the completion is queued with its completed due`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(repeating))) { serviceUnavailable() }

        h.repository.setDone(42, true)

        val queued = h.pendingActionDao.snapshot().single()
        assertEquals("toggle_done", queued.actionType)
        assertEquals(
            JsonObject(mapOf("done" to JsonPrimitive(true), RepeatCompletion.COMPLETED_DUE_KEY to JsonPrimitive(due))),
            Json.parseToJsonElement(queued.payload),
        )
        assertTrue(h.taskDao.entity(42)!!.done)
    }

    @Test
    fun `a completion from a notification keeps its completed due for the sync`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(repeating))) { error("Nothing is sent from it") }

        h.repository.setDoneInBackground(42, true)

        val queued = h.pendingActionDao.snapshot().single()
        assertEquals(
            JsonObject(mapOf("done" to JsonPrimitive(true), RepeatCompletion.COMPLETED_DUE_KEY to JsonPrimitive(due))),
            Json.parseToJsonElement(queued.payload),
        )
    }

    @Test
    fun `a task that does not repeat is completed without the read`() = runTest {
        val plain = repeating.copy(repeatAfter = 0)
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(plain))) {
            jsonOk("""{"id":42,"title":"Once","project_id":7,"done":true}""")
        }

        h.repository.setDone(42, true)

        assertEquals(listOf("PATCH /tasks/42"), h.sent.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `reopening a repeating task needs no read`() = runTest {
        val done = repeating.copy(done = true, doneAt = "2026-11-13T08:00:00Z")
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(done))) { jsonOk(serverTask(due)) }

        h.repository.setDone(42, false)

        assertEquals(listOf("PATCH /tasks/42"), h.sent.map { "${it.method} ${it.path}" })
    }
}
