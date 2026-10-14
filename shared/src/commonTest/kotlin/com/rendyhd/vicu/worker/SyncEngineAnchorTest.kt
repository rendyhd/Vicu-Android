package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.created
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A task created while offline was never put at the end of its list: the server prepends new
 * tasks, so replaying the create has to do what an online create does afterwards.
 */
class SyncEngineAnchorTest {

    private fun viewsPage() =
        """{"items":[{"id":70,"project_id":7,"title":"List","view_kind":"list"}],"total":1,"page":1,"per_page":100,"total_pages":1}"""

    private fun lastTaskPage() =
        """{"items":[{"id":1,"title":"Last","project_id":7,"position":1000.0}],"total":1,"page":1,"per_page":1,"total_pages":1}"""

    private fun harness(createdBody: String = """{"id":501,"title":"Call mum","project_id":7}""") =
        SyncEngineHarness(anchorCreates = true) { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/projects/7/tasks" -> created(createdBody)
                request.method == HttpMethod.Get && path == "/projects/7/views" ->
                    respond(viewsPage(), HttpStatusCode.OK, authTestJsonHeaders)
                request.method == HttpMethod.Get && path == "/projects/7/views/70/tasks" ->
                    respond(lastTaskPage(), HttpStatusCode.OK, authTestJsonHeaders)
                request.method == HttpMethod.Put && path.endsWith("/position") ->
                    respond("{}", HttpStatusCode.OK, authTestJsonHeaders)
                // The reconcile after the replay lists the new task as open, with no position: the
                // list endpoint never states one.
                request.method == HttpMethod.Get && path == "/tasks" ->
                    respond(
                        """{"items":[${createdBody}],"total":1,"page":1,"per_page":100,"total_pages":1}""",
                        HttpStatusCode.OK,
                        authTestJsonHeaders,
                    )
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} $path")
            }
        }

    @Test
    fun `replaying an offline create puts the task at the end of its list`() = runTest {
        val h = harness()
        h.taskDao.upsert(TaskEntity(id = -1, title = "Call mum", projectId = 7))
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "Call mum"))

        h.engine.performSync()
        h.positioner.awaitIdle()

        assertEquals(1, h.count(HttpMethod.Put, "/tasks/501/position"))
        assertEquals(
            1_000.0 + 65_536.0,
            h.taskDao.entity(501)!!.position,
            "the cached row says so too, and the reconcile that follows does not erase it",
        )
    }

    @Test
    fun `a metadata carrier is not positioned`() = runTest {
        val marker = "<!-- vicu-custom-lists:v1:AAAA -->"
        val h = harness(createdBody = """{"id":501,"title":"Lists","project_id":7,"description":"$marker"}""")
        h.taskDao.upsert(TaskEntity(id = -1, title = "Lists", projectId = 7, description = marker))
        val carrierCreate = Task(id = -1, title = "Lists", projectId = 7, description = marker)
        h.pendingActionDao.insert(
            queuedCreate(tempId = -1, title = "Lists")
                .copy(payload = authTestJson.encodeToString(Task.serializer(), carrierCreate)),
        )

        h.engine.performSync()
        h.positioner.awaitIdle()

        assertEquals(0, h.count(HttpMethod.Put, "/tasks/501/position"))
    }
}
