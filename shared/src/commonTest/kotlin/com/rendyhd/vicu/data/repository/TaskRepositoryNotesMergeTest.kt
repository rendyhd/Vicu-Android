package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.NotesMerge
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Notes changed in two places (description format, "Synchronization"): a notes edit sent at once
 * through the write gate reads the task first and keeps both versions when the server's notes moved.
 */
class TaskRepositoryNotesMergeTest {

    private val base = "<p>Base</p>"
    private val cached = cachedTaskEntity(id = 42, description = base).copy(relatedTasksJson = "{}")

    /** The server: the notes it holds, how a read answers, and the patches it was sent. */
    private class Server(var description: String, val readStatus: HttpStatusCode = HttpStatusCode.OK) {
        val patches = mutableListOf<JsonObject>()
    }

    private fun MockRequestHandleScope.taskJson(description: String): HttpResponseData = respond(
        content = buildJsonObject {
            put("id", 42)
            put("title", "Original")
            put("project_id", 7)
            put("description", description)
        }.toString(),
        status = HttpStatusCode.OK,
        headers = authTestJsonHeaders,
    )

    private fun harness(server: Server, messages: AppMessages = AppMessages()) =
        TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(cached)), messages = messages) { request ->
            when (request.method) {
                HttpMethod.Get ->
                    if (server.readStatus == HttpStatusCode.OK) taskJson(server.description) else respond("", server.readStatus)
                HttpMethod.Patch -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    server.patches += body
                    body["description"]?.let { server.description = it.jsonPrimitive.content }
                    taskJson(server.description)
                }
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }

    private suspend fun TaskRepositoryHarness.editNotes(description: String, notesBase: String? = base) =
        repository.updateWithNotesBase(mapper.run { cached.toDomain() }.copy(description = description), notesBase)

    /** Every message posted so far. */
    private suspend fun AppMessages.posted(): List<String> {
        post("end")
        return messages.takeWhile { it.text != "end" }.toList().map { it.text }
    }

    @Test
    fun `the server still holds the base so the edit is sent`() = runTest {
        val server = Server(base)
        val messages = AppMessages()
        val h = harness(server, messages)

        val result = h.editNotes("<p>Mine</p>")

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(listOf("GET /tasks/42", "PATCH /tasks/42"), h.sent.map { "${it.method} ${it.path}" })
        assertEquals(JsonObject(mapOf("description" to JsonPrimitive("<p>Mine</p>"))), server.patches.single())
        assertEquals(emptyList(), messages.posted())
    }

    @Test
    fun `notes changed on both sides are merged and the app says so once`() = runTest {
        val server = Server("<p>Theirs</p>")
        val messages = AppMessages()
        val h = harness(server, messages)

        val result = h.editNotes("<p>Mine</p>")

        val merged = "<p>Theirs</p>${NotesMerge.DIVIDER}<p>Mine</p>"
        assertEquals(merged, (result as NetworkResult.Success).data.description)
        assertEquals(JsonObject(mapOf("description" to JsonPrimitive(merged))), server.patches.single())
        assertEquals(merged, h.taskDao.entity(42)!!.description, "the local row holds the merged notes")
        assertEquals(listOf(NotesMerge.MERGED_MESSAGE), messages.posted())
    }

    @Test
    fun `notes the server already holds are not sent`() = runTest {
        val server = Server("<p>Mine</p>")
        val h = harness(server)

        val result = h.editNotes("<p>Mine</p>")

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(server.patches.isEmpty(), "nothing is left to send")
        assertEquals("<p>Mine</p>", h.taskDao.entity(42)!!.description)
    }

    @Test
    fun `only the notes are left out when other fields changed too`() = runTest {
        val server = Server("<p>Mine</p>")
        val h = harness(server)

        h.repository.updateWithNotesBase(
            h.mapper.run { cached.toDomain() }.copy(description = "<p>Mine</p>", title = "Renamed"),
            base,
        )

        assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Renamed"))), server.patches.single())
    }

    @Test
    fun `an edit without a base is sent without a read`() = runTest {
        val server = Server("<p>Theirs</p>")
        val h = harness(server)

        h.editNotes("<p>Mine</p>", notesBase = null)

        assertEquals(listOf("PATCH /tasks/42"), h.sent.map { "${it.method} ${it.path}" })
        assertEquals("<p>Mine</p>", server.description)
    }

    @Test
    fun `when the server cannot be reached the edit waits in the queue with its base`() = runTest {
        val server = Server(base, readStatus = HttpStatusCode.ServiceUnavailable)
        val h = harness(server)

        val result = h.editNotes("<p>Mine</p>")

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(server.patches.isEmpty())
        val queued = Json.parseToJsonElement(h.pendingActionDao.snapshot().single().payload).jsonObject
        assertEquals(JsonPrimitive("<p>Mine</p>"), queued["description"])
        assertEquals(JsonPrimitive(base), queued[NotesMerge.BASE_KEY])
        assertEquals("<p>Mine</p>", h.taskDao.entity(42)!!.description)
    }

    @Test
    fun `any other failed read sends the edit as it is`() = runTest {
        val server = Server("<p>Theirs</p>", readStatus = HttpStatusCode.Forbidden)
        val h = harness(server)

        h.editNotes("<p>Mine</p>")

        assertEquals(JsonObject(mapOf("description" to JsonPrimitive("<p>Mine</p>"))), server.patches.single())
    }

    @Test
    fun `edits folded into one queued change keep the first base`() = runTest {
        val server = Server(base, readStatus = HttpStatusCode.ServiceUnavailable)
        val h = harness(server)

        h.editNotes("<p>One</p>")
        // The editor's base after a queued save is what the repository answered.
        h.repository.updateWithNotesBase(
            h.mapper.run { h.taskDao.entity(42)!!.toDomain() }.copy(description = "<p>Two</p>"),
            "<p>One</p>",
        )

        val queued = Json.parseToJsonElement(h.pendingActionDao.snapshot().single().payload).jsonObject
        assertEquals(JsonPrimitive("<p>Two</p>"), queued["description"])
        assertEquals(JsonPrimitive(base), queued[NotesMerge.BASE_KEY])
    }

    @Test
    fun `a hidden carrier gets no notes check`() = runTest {
        val carrier = "<!-- vicu-prefs:v1 {} -->"
        val server = Server(carrier)
        val h = harness(server)

        h.editNotes("<!-- vicu-prefs:v1 {\"a\":1} -->", notesBase = carrier)

        assertEquals(listOf("PATCH /tasks/42"), h.sent.map { "${it.method} ${it.path}" })
    }
}
