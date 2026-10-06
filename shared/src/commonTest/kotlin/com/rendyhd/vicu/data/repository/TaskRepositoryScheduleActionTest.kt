package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.ScheduleAction
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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
        dayClock: DayClock = DayClock(CoroutineScope(Job()), ticking = false),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonOk(patchResponse) },
    ) = TaskRepositoryHarness(
        taskDao = FakeTaskDao(
            listOf(cachedTaskEntity(id = 42, title = "Newer title", description = "Newer description")),
        ),
        scheduleAction = action,
        dayClock = dayClock,
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
    fun `due today is local 23_59_59 of the clock's day in the clock's zone`() = runTest {
        // 22:30Z on 6 October is already the 7th in Auckland (NZDT, UTC+13) and Amsterdam (CEST),
        // but still 6:30 pm on the 6th in New York (EDT): "today" depends on the zone.
        val cases = mapOf(
            "Pacific/Auckland" to "2026-10-07T10:59:59Z",
            "America/New_York" to "2026-10-07T03:59:59Z",
            "Europe/Amsterdam" to "2026-10-07T21:59:59Z",
        )
        for ((zoneId, expected) in cases) {
            val clock = DayClock(
                CoroutineScope(Job()),
                FixedTimeSource(Instant.parse("2026-10-06T22:30:00Z"), TimeZone.of(zoneId)),
                ticking = false,
            )
            val h = harness(ScheduleAction.DUE_TODAY, clock)
            h.initScheduleAction()

            h.repository.applyScheduleAction(42)

            assertEquals(JsonPrimitive(expected), h.patches().single().bodyJson!!["due_date"], zoneId)
        }
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
        val h = harness(ScheduleAction.DUE_TODAY, handler = { serviceUnavailable() })
        h.initScheduleAction()

        h.repository.applyScheduleAction(42)

        val queued = h.pendingActionDao.snapshot().single()
        assertEquals("update", queued.actionType)
        val payload = Json.parseToJsonElement(queued.payload)
        assertEquals(setOf("due_date"), (payload as JsonObject).keys)
        assertNotNull(h.taskDao.entity(42))
    }
}
