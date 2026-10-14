package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A task created while the server is out of reach gets its reminder alarms at once, under its
 * temporary id (A-DATA-5); the sync moves them to the real id when the create replays
 * (SyncEngineOfflineCreateAlarmTest).
 */
class TaskRepositoryOfflineCreateAlarmTest {

    private val reminder = TaskReminder(reminder = "2030-01-01T09:00:00Z")

    private fun harness() = TaskRepositoryHarness { serviceUnavailable() }

    @Test
    fun `an offline create schedules its alarms under the temporary id`() = runTest {
        val h = harness()

        val result = h.repository.create(Task(id = 0, title = "Call mum", projectId = 7, dueDate = "2030-01-01T10:00:00Z", reminders = listOf(reminder)))

        assertIs<NetworkResult.Success<Task>>(result)
        val tempId = result.data.id
        assertTrue(tempId < 0, "a temporary id")
        assertEquals(listOf(tempId), h.hooks.scheduled)
        assertEquals(1, h.pendingActionDao.snapshot().count { it.actionType == "create" && it.entityId == tempId })
    }

    @Test
    fun `an online create schedules the alarm once, under the real id`() = runTest {
        val h = TaskRepositoryHarness { request ->
            if (request.method.value == "POST") {
                respond(
                    """{"id":55,"title":"Call mum","project_id":7}""",
                    HttpStatusCode.Created,
                    authTestJsonHeaders,
                )
            } else {
                serviceUnavailable()
            }
        }

        h.repository.create(Task(id = 0, title = "Call mum", projectId = 7, reminders = listOf(reminder)))

        assertEquals(listOf(55L), h.hooks.scheduled)
    }

    @Test
    fun `editing a task that only exists here reschedules its alarms`() = runTest {
        val h = harness()
        val created = (h.repository.create(Task(id = 0, title = "Call mum", projectId = 7)) as NetworkResult.Success).data
        h.hooks.scheduled.clear()

        h.repository.update(created.copy(reminders = listOf(reminder)))

        assertEquals(listOf(created.id), h.hooks.scheduled)
    }

    @Test
    fun `deleting a task that only exists here cancels its alarms`() = runTest {
        val h = harness()
        val created = (h.repository.create(Task(id = 0, title = "Call mum", projectId = 7, reminders = listOf(reminder))) as NetworkResult.Success).data

        h.repository.delete(created.id)

        assertTrue(created.id in h.hooks.cancelled)
    }
}
