package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.domain.model.Task
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Creating a task sends everything the task has, `done` included, in the one request. Routine
 * carriers rely on it: they are created done, so they are never an open task in any list.
 */
class TaskRepositoryCreateTest {

    private fun harness() = TaskRepositoryHarness { request ->
        when {
            request.method.value == "POST" && request.url.encodedPath == "/projects/7/tasks" ->
                respond(
                    """{"id":55,"title":"Created","project_id":7,"done":true}""",
                    HttpStatusCode.Created,
                    authTestJsonHeaders,
                )
            request.url.encodedPath == "/projects/7/views" ->
                jsonOk("""{"items":[],"total":0,"page":1,"per_page":100,"total_pages":1}""")
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `a task is created with its done flag in the same request`() = runTest {
        val h = harness()

        h.repository.create(Task(id = 0, title = "Done already", done = true, projectId = 7))

        val create = h.sent.single { it.method == "POST" }
        assertTrue(create.bodyJson!!.getValue("done").jsonPrimitive.boolean)
        assertTrue(h.patches().isEmpty(), "no second request is needed to complete it")
    }

    @Test
    fun `an ordinary task is created open`() = runTest {
        val h = harness()

        h.repository.create(Task(id = 0, title = "Buy milk", projectId = 7))

        assertEquals(false, h.sent.first { it.method == "POST" }.bodyJson!!.getValue("done").jsonPrimitive.boolean)
    }

    @Test
    fun `a routine carrier is not positioned at the end of the project list`() = runTest {
        val h = harness()
        val carrier = Task(
            id = 0,
            title = "Vitamin D",
            description = "<!-- vicu-routine:v1:e30 -->",
            done = true,
            projectId = 7,
        )

        h.repository.create(carrier)

        assertEquals(listOf("POST /projects/7/tasks"), h.sent.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `an archive part is not positioned either`() = runTest {
        val h = harness()

        h.repository.create(
            Task(id = 0, title = "Vicu routine archive", description = "<!-- vicu-routine:archive:v1:e30 -->", done = true, projectId = 7),
        )

        assertEquals(listOf("POST /projects/7/tasks"), h.sent.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `an ordinary task is still positioned at the end of the list`() = runTest {
        val h = harness()

        h.repository.create(Task(id = 0, title = "Buy milk", projectId = 7))
        h.positioner.awaitIdle()

        assertTrue(h.sent.any { it.method == "GET" && it.path == "/projects/7/views" })
    }
}
