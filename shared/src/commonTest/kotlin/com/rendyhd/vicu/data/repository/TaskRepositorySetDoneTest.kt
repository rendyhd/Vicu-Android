package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `setDone` is the idempotent form of completion used by the notification action. Unlike
 * toggleDone it can never reopen a task that is already in the requested state.
 */
class TaskRepositorySetDoneTest {

    private fun harness(
        entity: TaskEntity,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            jsonOk("""{"id":${entity.id},"title":"${entity.title}","project_id":7,"done":true}""")
        },
    ) = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(entity)), handler = handler)

    // No subtasks, so completing the task is a single request.
    private val open = cachedTaskEntity(id = 42).copy(done = false, relatedTasksJson = "{}")
    private val done = cachedTaskEntity(id = 42).copy(
        done = true,
        doneAt = "2026-10-06T07:00:00Z",
        relatedTasksJson = "{}",
    )

    @Test
    fun `completing an open task sends done true`() = runTest {
        val h = harness(open)

        val result = h.repository.setDone(42, true)

        assertIs<NetworkResult.Success<*>>(result)
        val patch = h.patches().single()
        assertEquals("/tasks/42", patch.path)
        assertEquals(JsonObject(mapOf("done" to JsonPrimitive(true))), patch.bodyJson)
    }

    @Test
    fun `completing a task that sync already stored as done does nothing`() = runTest {
        val h = harness(done)

        val result = h.repository.setDone(42, true)

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(h.sent.isEmpty(), "no request: a toggle would have reopened the task")
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertTrue(h.taskDao.entity(42)!!.done)
    }

    @Test
    fun `completing twice never reopens the task`() = runTest {
        val h = harness(open) { serviceUnavailable() }

        h.repository.setDone(42, true)
        h.repository.setDone(42, true)

        assertTrue(h.taskDao.entity(42)!!.done)
        // The first call queued done=true offline; the second saw the stored done state.
        val queued = h.pendingActionDao.snapshot().single()
        assertEquals(JsonObject(mapOf("done" to JsonPrimitive(true))), Json.parseToJsonElement(queued.payload))
    }

    @Test
    fun `reopening a done task sends done false and an open task stays open`() = runTest {
        val reopen = harness(done) { jsonOk("""{"id":42,"title":"Original","project_id":7,"done":false}""") }
        reopen.repository.setDone(42, false)
        assertEquals(JsonObject(mapOf("done" to JsonPrimitive(false))), reopen.patches().single().bodyJson)

        val alreadyOpen = harness(open)
        alreadyOpen.repository.setDone(42, false)
        assertTrue(alreadyOpen.sent.isEmpty())
    }

    @Test
    fun `a completion from a notification is stored and queued, nothing is sent from it`() = runTest {
        val h = harness(open) { error("The notification action must not send anything itself") }

        val result = h.repository.setDoneInBackground(42, true)

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(h.sent.isEmpty())
        assertTrue(h.taskDao.entity(42)!!.done, "recorded on the device at once")
        val queued = h.pendingActionDao.snapshot().single()
        assertEquals("toggle_done", queued.actionType)
        assertEquals(JsonObject(mapOf("done" to JsonPrimitive(true))), Json.parseToJsonElement(queued.payload))
        assertEquals(1, h.hooks.syncTriggers, "the sync sends it")
        assertEquals(listOf(42L), h.hooks.cancelled)
    }

    @Test
    fun `a completion from a notification of a task already done does nothing`() = runTest {
        val h = harness(done) { error("Nothing to send") }

        h.repository.setDoneInBackground(42, true)

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertTrue(h.taskDao.entity(42)!!.done)
    }

    @Test
    fun `an unknown task is an error and sends nothing`() = runTest {
        val h = harness(open)

        val result = h.repository.setDone(999, true)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.sent.isEmpty())
    }
}
