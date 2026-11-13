package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.cachedTaskEntity
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.NotesMerge
import com.rendyhd.vicu.util.RepeatCompletion
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay of a queued notes edit (description format, "Notes changed in two places"): it reads
 * the task first and keeps both versions when the server's notes moved since the edit began.
 */
class SyncEngineNotesMergeTest {

    private val base = "<p>Base</p>"
    private val due = "2026-11-13T22:59:59Z"
    private val queuedAt = "2026-11-13T08:00:00Z"

    private fun update(payload: JsonObject, actionType: String = "update") = PendingActionEntity(
        entityType = "task",
        entityId = 42,
        actionType = actionType,
        payload = payload.toString(),
        createdAt = queuedAt,
        updatedAt = queuedAt,
    )

    private fun notesEdit(description: String, withBase: String? = base): JsonObject =
        NotesMerge.withBase(JsonObject(mapOf("description" to JsonPrimitive(description))), withBase)

    private class Server(
        var description: String,
        val readStatus: HttpStatusCode = HttpStatusCode.OK,
        val dueDate: String = "",
        val repeatAfter: Long = 0,
    ) {
        val patches = mutableListOf<JsonObject>()
    }

    private fun MockRequestHandleScope.taskJson(server: Server): HttpResponseData = respond(
        content = buildJsonObject {
            put("id", 42)
            put("title", "Original")
            put("project_id", 7)
            put("description", server.description)
            put("repeat_after", server.repeatAfter)
            if (server.dueDate.isNotEmpty()) put("due_date", server.dueDate)
        }.toString(),
        status = HttpStatusCode.OK,
        headers = authTestJsonHeaders,
    )

    private val cached = cachedTaskEntity(id = 42, description = "<p>Mine</p>").copy(relatedTasksJson = "{}")

    private fun harness(server: Server, messages: AppMessages = AppMessages()) =
        SyncEngineHarness(taskDao = FakeTaskDao(listOf(cached)), messages = messages) { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Get && path == "/tasks/42" ->
                    if (server.readStatus == HttpStatusCode.OK) taskJson(server) else respond("", server.readStatus)
                request.method == HttpMethod.Patch && path == "/tasks/42" -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    server.patches += body
                    body["description"]?.let { server.description = it.jsonPrimitive.content }
                    taskJson(server)
                }
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} $path")
            }
        }

    private suspend fun AppMessages.posted(): List<String> {
        post("end")
        return messages.takeWhile { it.text != "end" }.toList().map { it.text }
    }

    @Test
    fun `the server still holds the base so the queued notes are sent without the base`() = runTest {
        val server = Server(base)
        val messages = AppMessages()
        val h = harness(server, messages)
        h.pendingActionDao.insert(update(notesEdit("<p>Mine</p>")))

        h.engine.performSync()

        assertEquals(listOf(JsonObject(mapOf("description" to JsonPrimitive("<p>Mine</p>")))), server.patches)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertEquals(emptyList(), messages.posted())
        h.close()
    }

    @Test
    fun `queued notes changed on both sides are merged and the app says so`() = runTest {
        val server = Server("<p>Theirs</p>")
        val messages = AppMessages()
        val h = harness(server, messages)
        h.pendingActionDao.insert(update(notesEdit("<p>Mine</p>")))

        h.engine.performSync()

        val merged = "<p>Theirs</p>${NotesMerge.DIVIDER}<p>Mine</p>"
        assertEquals(listOf(JsonObject(mapOf("description" to JsonPrimitive(merged)))), server.patches)
        assertEquals(listOf(NotesMerge.MERGED_MESSAGE), messages.posted())
        h.close()
    }

    @Test
    fun `queued notes the server already holds send nothing`() = runTest {
        val server = Server("<p>Mine</p>")
        val h = harness(server)
        h.pendingActionDao.insert(update(notesEdit("<p>Mine</p>")))

        h.engine.performSync()

        assertTrue(server.patches.isEmpty())
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "nothing to send is not a failed change")
        h.close()
    }

    @Test
    fun `when the server cannot be reached the queued notes wait with their base`() = runTest {
        val server = Server("<p>Theirs</p>", readStatus = HttpStatusCode.ServiceUnavailable)
        val h = harness(server)
        h.pendingActionDao.insert(update(notesEdit("<p>Mine</p>")))

        h.engine.performSync()

        assertTrue(server.patches.isEmpty())
        val waiting = h.pendingActionDao.snapshot().single()
        assertEquals("pending", waiting.status)
        assertEquals(JsonPrimitive(base), Json.parseToJsonElement(waiting.payload).jsonObject[NotesMerge.BASE_KEY])
        h.close()
    }

    @Test
    fun `any other failed read sends the queued notes as they are`() = runTest {
        val server = Server("<p>Theirs</p>", readStatus = HttpStatusCode.Forbidden)
        val h = harness(server)
        h.pendingActionDao.insert(update(notesEdit("<p>Mine</p>")))

        h.engine.performSync()

        assertEquals(listOf(JsonObject(mapOf("description" to JsonPrimitive("<p>Mine</p>")))), server.patches)
        h.close()
    }

    @Test
    fun `queued notes of an older version are sent without a read`() = runTest {
        val server = Server("<p>Theirs</p>")
        val h = harness(server)
        h.pendingActionDao.insert(update(notesEdit("<p>Mine</p>", withBase = null)))

        h.engine.performSync()

        assertEquals(0, h.count(HttpMethod.Get, "/tasks/42"))
        assertEquals("<p>Mine</p>", server.description)
        h.close()
    }

    @Test
    fun `a completion and a notes edit folded together read the task once`() = runTest {
        val server = Server("<p>Theirs</p>", dueDate = "2026-11-14T22:59:59Z", repeatAfter = 86_400)
        val messages = AppMessages()
        val h = harness(server, messages)
        val stored = JsonObject(
            notesEdit("<p>Mine</p>") +
                ("done" to JsonPrimitive(true)) +
                (RepeatCompletion.COMPLETED_DUE_KEY to JsonPrimitive(due)),
        )
        h.pendingActionDao.insert(update(stored, actionType = "toggle_done"))

        h.engine.performSync()

        assertEquals(1, h.count(HttpMethod.Get, "/tasks/42"))
        // The occurrence was completed elsewhere: only the merged notes go.
        val merged = "<p>Theirs</p>${NotesMerge.DIVIDER}<p>Mine</p>"
        assertEquals(listOf(JsonObject(mapOf("description" to JsonPrimitive(merged)))), server.patches)
        assertEquals(listOf(NotesMerge.MERGED_MESSAGE), messages.posted())
        h.close()
    }
}
