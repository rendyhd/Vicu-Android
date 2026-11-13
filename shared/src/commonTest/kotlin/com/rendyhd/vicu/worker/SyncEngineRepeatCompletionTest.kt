package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.cachedTaskEntity
import com.rendyhd.vicu.util.RepeatCompletion
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay of a queued completion of a repeating task (cross-app semantics 7.5): it reads the
 * task first and sends nothing when another device has completed this occurrence already.
 */
class SyncEngineRepeatCompletionTest {

    private val due = "2026-11-13T22:59:59Z"
    private val queuedAt = "2026-11-13T08:00:00Z"

    private fun completion(payload: JsonObject) = PendingActionEntity(
        entityType = "task",
        entityId = 42,
        actionType = "toggle_done",
        payload = payload.toString(),
        createdAt = queuedAt,
        updatedAt = queuedAt,
    )

    private fun stored(vararg fields: Pair<String, Any>): JsonObject = JsonObject(
        fields.associate { (k, v) ->
            k to when (v) {
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private fun MockRequestHandleScope.task(dueDate: String, done: Boolean = false): HttpResponseData = respond(
        content = """{"id":42,"title":"Take pills","project_id":7,"done":$done,"repeat_after":86400,"due_date":"$dueDate"}""",
        status = HttpStatusCode.OK,
        headers = authTestJsonHeaders,
    )

    private val cached = cachedTaskEntity(id = 42, dueDate = due).copy(done = true, relatedTasksJson = "{}", repeatAfter = 86_400)

    private class Server(val readDue: String, val readFails: Boolean = false) {
        val patches = mutableListOf<String>()
    }

    private fun harness(server: Server) = SyncEngineHarness(taskDao = FakeTaskDao(listOf(cached))) { request ->
        val path = request.url.encodedPath
        when {
            request.method == HttpMethod.Get && path == "/tasks/42" ->
                if (server.readFails) respond("", HttpStatusCode.ServiceUnavailable) else task(server.readDue)
            request.method == HttpMethod.Patch && path == "/tasks/42" -> {
                server.patches += (request.body as TextContent).text
                task("2026-11-14T22:59:59Z")
            }
            request.method == HttpMethod.Get -> emptyPage()
            else -> error("Unexpected ${request.method.value} $path")
        }
    }

    @Test
    fun `a completion of the same occurrence is sent without the completed due`() = runTest {
        val server = Server(readDue = due)
        val h = harness(server)
        h.pendingActionDao.insert(completion(stored("done" to true, RepeatCompletion.COMPLETED_DUE_KEY to due)))

        h.engine.performSync()

        assertEquals(listOf("""{"done":true}"""), server.patches)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `an occurrence completed elsewhere is skipped and counted as done`() = runTest {
        val server = Server(readDue = "2026-11-14T22:59:59Z")
        val h = harness(server)
        h.pendingActionDao.insert(completion(stored("done" to true, RepeatCompletion.COMPLETED_DUE_KEY to due)))

        val ok = h.engine.performSync()

        assertTrue(ok)
        assertTrue(server.patches.isEmpty(), server.patches.toString())
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "a skip is not a failed change")
        assertTrue(42L in h.hooks.scheduled, "the server's task is stored and its reminder follows")
        h.close()
    }

    @Test
    fun `on a skip the fields edited with the completion are still sent`() = runTest {
        val server = Server(readDue = "2026-11-14T22:59:59Z")
        val h = harness(server)
        h.pendingActionDao.insert(
            completion(stored("title" to "Take vitamins", "done" to true, RepeatCompletion.COMPLETED_DUE_KEY to due)),
        )

        h.engine.performSync()

        assertEquals(listOf(JsonObject(mapOf("title" to JsonPrimitive("Take vitamins")))), server.patches.map { Json.parseToJsonElement(it) })
        h.close()
    }

    @Test
    fun `a patch that also sets the due date is sent without the check`() = runTest {
        val server = Server(readDue = "2026-11-14T22:59:59Z")
        val h = harness(server)
        h.pendingActionDao.insert(
            completion(stored("due_date" to "2026-11-20T22:59:59Z", "done" to true, RepeatCompletion.COMPLETED_DUE_KEY to due)),
        )

        h.engine.performSync()

        assertEquals(0, h.count(HttpMethod.Get, "/tasks/42"))
        assertEquals(1, server.patches.size)
        assertTrue(RepeatCompletion.COMPLETED_DUE_KEY !in server.patches.single())
        h.close()
    }

    @Test
    fun `when the task cannot be read the completion waits`() = runTest {
        val server = Server(readDue = due, readFails = true)
        val h = harness(server)
        h.pendingActionDao.insert(completion(stored("done" to true, RepeatCompletion.COMPLETED_DUE_KEY to due)))

        h.engine.performSync()

        assertTrue(server.patches.isEmpty())
        assertEquals(listOf("pending"), h.pendingActionDao.snapshot().map { it.status })
        h.close()
    }

    @Test
    fun `a completion queued by an older version is sent as before`() = runTest {
        val server = Server(readDue = "2026-11-14T22:59:59Z")
        val h = harness(server)
        h.pendingActionDao.insert(completion(stored("done" to true)))

        h.engine.performSync()

        assertEquals(0, h.count(HttpMethod.Get, "/tasks/42"))
        assertEquals(listOf("""{"done":true}"""), server.patches)
        h.close()
    }
}
