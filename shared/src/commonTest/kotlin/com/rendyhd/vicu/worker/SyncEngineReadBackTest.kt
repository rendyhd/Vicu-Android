package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.dao.AssigneePayload
import com.rendyhd.vicu.data.local.dao.CollabQueue
import com.rendyhd.vicu.data.local.dao.RelationPayload
import com.rendyhd.vicu.data.local.dao.RelationQueue
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.created
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * After a queued assignee or relation change has been sent, its tasks are read back (review C-7):
 * a refresh skipped them while the change waited, and its cursor has moved on since.
 */
class SyncEngineReadBackTest {

    private val queuedAt = "2026-11-13T08:00:00Z"

    private fun harness() = SyncEngineHarness { request ->
        val path = request.url.encodedPath
        when {
            request.method == HttpMethod.Post -> created("{}")
            request.method == HttpMethod.Get && Regex("^/tasks/\\d+$").matches(path) -> {
                val id = path.substringAfterLast('/')
                respond("""{"id":$id,"title":"Renamed elsewhere","project_id":7}""", HttpStatusCode.OK, authTestJsonHeaders)
            }
            request.method == HttpMethod.Get -> emptyPage()
            else -> error("Unexpected ${request.method.value} $path")
        }
    }

    @Test
    fun `a task is read back after its assignee change was sent`() = runTest {
        val h = harness()
        h.pendingActionDao.insert(
            PendingActionEntity(
                entityType = CollabQueue.ASSIGNEE,
                entityId = 5,
                actionType = CollabQueue.ADD,
                payload = CollabQueue.encode(AssigneePayload(userId = 2)),
                createdAt = queuedAt,
                updatedAt = queuedAt,
            ),
        )

        h.engine.performSync()

        val requests = h.requests()
        val sent = requests.indexOf("POST /tasks/5/assignees")
        val read = requests.indexOf("GET /tasks/5")
        assertTrue(sent >= 0 && read > sent, requests.toString())
        h.close()
    }

    @Test
    fun `both tasks of a relation are read back`() = runTest {
        val h = harness()
        h.pendingActionDao.insert(
            PendingActionEntity(
                entityType = RelationQueue.ENTITY_TYPE,
                entityId = 5,
                actionType = RelationQueue.ADD,
                payload = RelationQueue.encode(RelationPayload(6, RelationKind.BLOCKED)),
                createdAt = queuedAt,
                updatedAt = queuedAt,
            ),
        )

        h.engine.performSync()

        val requests = h.requests()
        assertTrue("GET /tasks/5" in requests && "GET /tasks/6" in requests, requests.toString())
        h.close()
    }
}
