package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Completing a task online stores the server's answer in Room. Other screens, search, the daily
 * summary and the widgets read Room, so they must not keep showing the task as open.
 */
class TaskRepositoryCompletionWritesRoomTest {

    private val open = cachedTaskEntity(id = 42).copy(done = false, relatedTasksJson = "{}")

    /** The server's answer: done, with an attachment and a label the cached row did not have. */
    private val doneResponse = """
        {"id":42,"title":"Original","project_id":7,"done":true,"done_at":"2026-10-06T20:00:00Z",
         "due_date":"2026-10-08T21:59:59Z","priority":2,
         "labels":[{"id":5,"title":"home","hex_color":"ff0000"}],
         "attachments":[{"id":11,"task_id":42,"file":{"name":"b.pdf","mime":"application/pdf","size":99}}]}
    """.trimIndent()

    @Test
    fun `an online completion stores the response with its attachments and labels`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(open))) { jsonOk(doneResponse) }

        val result = h.repository.toggleDone(h.mapper.run { open.toDomain() })

        assertIs<NetworkResult.Success<*>>(result)
        val stored = h.taskDao.entity(42)!!
        assertTrue(stored.done)
        assertEquals("2026-10-06T20:00:00Z", stored.doneAt)
        assertTrue(stored.attachmentsJson.contains("b.pdf"), stored.attachmentsJson)
        assertTrue(stored.labelsJson.contains("home"), stored.labelsJson)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `a repeating task comes back open with its next due date and keeps its alarm`() = runTest {
        val repeating = open.copy(repeatAfter = 86_400, repeatMode = 0)
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(repeating))) {
            jsonOk(
                """{"id":42,"title":"Original","project_id":7,"done":false,"repeat_after":86400,
                    "due_date":"2026-10-09T21:59:59Z"}""",
            )
        }

        h.repository.toggleDone(h.mapper.run { repeating.toDomain() })

        val stored = h.taskDao.entity(42)!!
        assertFalse(stored.done)
        assertEquals("2026-10-09T21:59:59Z", stored.dueDate)
        assertEquals(listOf(42L), h.hooks.scheduled, "the next occurrence gets its reminders")
        assertTrue(h.hooks.cancelled.isEmpty())
    }

    @Test
    fun `reopening online stores the open task`() = runTest {
        val done = open.copy(done = true, doneAt = "2026-10-06T07:00:00Z")
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(done))) {
            jsonOk("""{"id":42,"title":"Original","project_id":7,"done":false}""")
        }

        h.repository.setDone(42, false)

        assertFalse(h.taskDao.entity(42)!!.done)
        assertEquals(listOf(42L), h.hooks.scheduled)
    }

    @Test
    fun `a rejected completion leaves the cached task untouched`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(open))) {
            respond(content = """{"code":3005,"message":"forbidden"}""", status = HttpStatusCode.Forbidden)
        }

        val result = h.repository.toggleDone(h.mapper.run { open.toDomain() })

        assertIs<NetworkResult.Error>(result)
        assertFalse(h.taskDao.entity(42)!!.done)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `offline the completion is stored locally and queued as before`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(open))) { serviceUnavailable() }

        h.repository.toggleDone(h.mapper.run { open.toDomain() })

        assertTrue(h.taskDao.entity(42)!!.done)
        assertEquals("toggle_done", h.pendingActionDao.snapshot().single().actionType)
    }
}
