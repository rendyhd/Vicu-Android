package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.dao.AssigneePayload
import com.rendyhd.vicu.data.local.dao.CollabQueue
import com.rendyhd.vicu.data.local.dao.CommentPayload
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskCommentEntity
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.created
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Queued assignee and comment changes replay in order (cross-app semantics 9.6). */
class SyncEngineCollaborationTest {

    private val queuedAt = "2026-10-10T08:00:00Z"

    private fun collab(entityType: String, taskId: Long, actionType: String, payload: String) = PendingActionEntity(
        entityType = entityType,
        entityId = taskId,
        actionType = actionType,
        payload = payload,
        createdAt = queuedAt,
        updatedAt = queuedAt,
    )

    private fun assign(taskId: Long, actionType: String, userId: Long) =
        collab(CollabQueue.ASSIGNEE, taskId, actionType, CollabQueue.encode(AssigneePayload(userId, "maya", "Maya")))

    private fun comment(taskId: Long, actionType: String, commentId: Long, html: String = "", maybeSent: Boolean = false) =
        collab(CollabQueue.COMMENT, taskId, actionType, CollabQueue.encode(CommentPayload(commentId, html, maybeSent)))

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: Long): HttpResponseData =
        respond(
            content = """{"title":"${status.description}","status":${status.value},"detail":"refused","code":$code}""",
            status = status,
            headers = authTestJsonHeaders,
        )

    private fun MockRequestHandleScope.ok(body: String = "{}"): HttpResponseData =
        respond(content = body, status = HttpStatusCode.OK, headers = authTestJsonHeaders)

    private fun commentJson(id: Long, html: String, created: String = "2026-10-10T08:00:01Z") =
        """{"id":$id,"comment":"$html","author":{"id":1,"username":"rendy","name":"Rendy"},"created":"$created","updated":"$created"}"""

    @Test
    fun `a queued assignment is sent, and already assigned counts as done`() = runTest {
        val bodies = mutableListOf<String?>()
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/11/assignees" -> {
                    bodies += (request.body as? TextContent)?.text
                    if (bodies.size == 1) created("""{"user_id":2}""") else problem(HttpStatusCode.BadRequest, 4021)
                }
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(assign(11, CollabQueue.ADD, 2))
        h.pendingActionDao.insert(assign(11, CollabQueue.ADD, 3).copy(payload = CollabQueue.encode(AssigneePayload(3))))

        h.engine.performSync()

        assertEquals<List<String?>>(listOf("""{"user_id":2}""", """{"user_id":3}"""), bodies)
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), h.pendingActionDao.snapshot().toString())
        h.close()
    }

    @Test
    fun `unassigning someone who is not assigned is done`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Delete && request.url.encodedPath == "/tasks/11/assignees/2" ->
                    problem(HttpStatusCode.NotFound, 1005)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(assign(11, CollabQueue.REMOVE, 2))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `a refused assignment fails visibly instead of stopping the queue`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/11/assignees" ->
                    problem(HttpStatusCode.Forbidden, 1)
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/12/comments" ->
                    created(commentJson(70, "<p>hi</p>"))
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(assign(11, CollabQueue.ADD, 2))
        h.pendingActionDao.insert(comment(12, CollabQueue.CREATE, -5, "<p>hi</p>"))

        h.engine.performSync()

        val left = h.pendingActionDao.snapshot()
        assertEquals(listOf(CollabQueue.ASSIGNEE to "failed"), left.map { it.entityType to it.status })
        assertEquals(1, h.count(HttpMethod.Post, "/tasks/12/comments"), "the next change was still sent")
        h.close()
    }

    @Test
    fun `a comment the API token may not post fails visibly, the queue goes on and the session stays`() = runTest {
        // Vikunja answers 401 to a token without the comments permission; the token still reads the
        // project list, so only that change was refused (cross-app semantics 9.7).
        val h = SyncEngineHarness(productionPipeline = true) { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/tasks/12/comments" ->
                    respond("""{"code":11,"message":"invalid token"}""", HttpStatusCode.Unauthorized, authTestJsonHeaders)
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/tasks/11/assignees" ->
                    created("""{"user_id":2}""")
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.signInWithApiToken()
        h.pendingActionDao.insert(comment(12, CollabQueue.CREATE, -5, "<p>hi</p>"))
        h.pendingActionDao.insert(assign(11, CollabQueue.ADD, 2))

        h.engine.performSync()

        val left = h.pendingActionDao.snapshot()
        assertEquals(listOf(CollabQueue.COMMENT to "failed"), left.map { it.entityType to it.status })
        assertEquals(1, h.count(HttpMethod.Post, "/api/v2/tasks/11/assignees"), "the next change was still sent")
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        h.close()
    }

    @Test
    fun `a comment written offline takes its real id and a later edit goes to that id`() = runTest {
        val bodies = mutableListOf<String?>()
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/11/comments" ->
                    created(commentJson(70, "<p>first</p>"))
                request.method == HttpMethod.Patch && request.url.encodedPath == "/tasks/11/comments/70" -> {
                    bodies += (request.body as? TextContent)?.text
                    ok(commentJson(70, "<p>second</p>"))
                }
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.collaborationDao.upsertComments(listOf(TaskCommentEntity(id = -5, taskId = 11, comment = "<p>first</p>", pending = true)))
        h.pendingActionDao.insert(comment(11, CollabQueue.CREATE, -5, "<p>first</p>"))
        // Queued while the create was being sent, so it was not folded into it.
        h.pendingActionDao.insert(comment(11, CollabQueue.UPDATE, -5, "<p>second</p>"))

        h.engine.performSync()

        assertNull(h.collaborationDao.getComment(-5), "the temporary row is gone")
        assertEquals("<p>second</p>", h.collaborationDao.getComment(70)?.comment)
        assertEquals<List<String?>>(listOf("""{"comment":"<p>second</p>"}"""), bodies)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `a create that may have reached the server is looked for before it is sent again`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/tasks/11/comments" ->
                    ok("""{"items":[${commentJson(70, "<p>hi</p>", "2026-10-10T08:00:02Z")}],"total":1,"page":1,"per_page":50,"total_pages":1}""")
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/11/comments" ->
                    error("sent twice")
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.storage.storeUserId(1)
        h.collaborationDao.upsertComments(listOf(TaskCommentEntity(id = -5, taskId = 11, comment = "<p>hi</p>", pending = true)))
        h.pendingActionDao.insert(comment(11, CollabQueue.CREATE, -5, "<p>hi</p>", maybeSent = true))

        h.engine.performSync()

        assertEquals(0, h.count(HttpMethod.Post, "/tasks/11/comments"))
        assertEquals(70L, h.collaborationDao.comments.value.keys.single())
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `changes to a task created offline wait for its create and use its real id`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" ->
                    created("""{"id":501,"title":"New","project_id":7}""")
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/501/assignees" ->
                    created("""{"user_id":2}""")
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/501/comments" ->
                    created(commentJson(80, "<p>note</p>"))
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.collaborationDao.upsertComments(listOf(TaskCommentEntity(id = -9, taskId = -1, comment = "<p>note</p>", pending = true)))
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "New", createdAt = "2026-10-10T07:59:00Z"))
        h.pendingActionDao.insert(assign(-1, CollabQueue.ADD, 2))
        h.pendingActionDao.insert(comment(-1, CollabQueue.CREATE, -9, "<p>note</p>"))

        h.engine.performSync()

        assertEquals(1, h.count(HttpMethod.Post, "/tasks/501/assignees"), h.requests().toString())
        assertEquals(1, h.count(HttpMethod.Post, "/tasks/501/comments"))
        assertEquals(501L, h.collaborationDao.getComment(80)?.taskId)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `deleting a comment that is already gone is done`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Delete && request.url.encodedPath == "/tasks/11/comments/70" ->
                    problem(HttpStatusCode.NotFound, 4015)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(comment(11, CollabQueue.DELETE, 70))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }
}
