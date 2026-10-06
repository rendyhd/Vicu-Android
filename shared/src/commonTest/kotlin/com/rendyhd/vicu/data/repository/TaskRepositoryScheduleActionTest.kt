package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.ScheduleAction
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskRepositoryScheduleActionTest {

    private val patchResponse =
        """{"id":42,"title":"Newer title","description":"Newer description","project_id":7,"priority":2}"""

    private fun harness(
        action: ScheduleAction,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonOk(patchResponse) },
    ) = TaskRepositoryHarness(
        taskDao = FakeTaskDao(
            listOf(cachedTaskEntity(id = 42, title = "Newer title", description = "Newer description")),
        ),
        scheduleAction = action,
        handler = handler,
    )

    @Test
    fun `due today patch contains only due_date and is built from the current Room row`() = runTest {
        val h = harness(ScheduleAction.DUE_TODAY)
        h.initScheduleAction()

        val result = h.repository.applyScheduleAction(42)

        assertIs<NetworkResult.Success<*>>(result)
        val patch = h.patches().single()
        assertEquals("/tasks/42", patch.path)
        assertEquals(setOf("due_date"), patch.bodyJson!!.keys)
        val due = patch.bodyJson!!["due_date"]
        assertTrue(due is JsonPrimitive && due !is JsonNull && DateUtils.parseIsoDate(due.content) != null)
    }

    @Test
    fun `urgent patch contains only priority`() = runTest {
        val h = harness(ScheduleAction.PRIORITY_URGENT)
        h.initScheduleAction()

        h.repository.applyScheduleAction(42)

        val patch = h.patches().single()
        assertEquals(setOf("priority"), patch.bodyJson!!.keys)
        assertEquals(JsonPrimitive(4), patch.bodyJson!!["priority"])
    }

    @Test
    fun `an unknown task id sends nothing`() = runTest {
        val h = harness(ScheduleAction.DUE_TODAY)
        h.initScheduleAction()

        val result = h.repository.applyScheduleAction(999)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `offline the queued patch contains only the schedule field`() = runTest {
        val h = harness(ScheduleAction.DUE_TODAY) { serviceUnavailable() }
        h.initScheduleAction()

        h.repository.applyScheduleAction(42)

        val queued = h.pendingActionDao.snapshot().single()
        assertEquals("update", queued.actionType)
        val payload = Json.parseToJsonElement(queued.payload)
        assertEquals(setOf("due_date"), (payload as JsonObject).keys)
        assertNotNull(h.taskDao.entity(42))
    }
}
