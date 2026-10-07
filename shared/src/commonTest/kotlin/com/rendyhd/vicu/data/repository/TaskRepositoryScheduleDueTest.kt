package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.domain.repository.QuickDue
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Today / Tomorrow quick picks used by the TalkBack actions of a task row. */
class TaskRepositoryScheduleDueTest {

    private val patchResponse = """{"id":42,"title":"Newer title","project_id":7,"priority":2}"""

    // 22:30Z on 6 October is already the 7th in Amsterdam (CEST, UTC+2).
    private val clock = DayClock(
        CoroutineScope(Job()),
        FixedTimeSource(Instant.parse("2026-10-06T22:30:00Z"), TimeZone.of("Europe/Amsterdam")),
        ticking = false,
    )

    private fun harness(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { jsonOk(patchResponse) },
    ) = TaskRepositoryHarness(
        taskDao = FakeTaskDao(listOf(cachedTaskEntity(id = 42, title = "Newer title", dueDate = ""))),
        dayClock = clock,
        handler = handler,
    )

    @Test
    fun `today sets only the due date to the end of the local day`() = runTest {
        val h = harness()

        val result = h.repository.scheduleDue(42, QuickDue.TODAY)

        assertIs<NetworkResult.Success<*>>(result)
        val patch = h.patches().single()
        assertEquals(setOf("due_date"), patch.bodyJson!!.keys)
        // 23:59:59 on the 7th in Amsterdam
        assertEquals(JsonPrimitive("2026-10-07T21:59:59Z"), patch.bodyJson!!["due_date"])
    }

    @Test
    fun `tomorrow sets the due date to the end of the next local day`() = runTest {
        val h = harness()

        h.repository.scheduleDue(42, QuickDue.TOMORROW)

        val patch = h.patches().single()
        assertEquals(setOf("due_date"), patch.bodyJson!!.keys)
        assertEquals(JsonPrimitive("2026-10-08T21:59:59Z"), patch.bodyJson!!["due_date"])
    }

    @Test
    fun `an unknown task sends nothing`() = runTest {
        val h = harness()

        val result = h.repository.scheduleDue(999, QuickDue.TODAY)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `offline the queued patch holds only the due date`() = runTest {
        val h = harness(handler = { serviceUnavailable() })

        h.repository.scheduleDue(42, QuickDue.TOMORROW)

        val queued = h.pendingActionDao.snapshot().single()
        assertEquals("update", queued.actionType)
        assertEquals(setOf("due_date"), (Json.parseToJsonElement(queued.payload) as JsonObject).keys)
    }
}
