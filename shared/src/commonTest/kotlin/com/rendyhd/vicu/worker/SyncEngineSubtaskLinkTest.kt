package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.created
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A subtask created offline is linked to its parent by the sync engine. */
class SyncEngineSubtaskLinkTest {

    private val queuedAt = "2026-10-06T10:00:00Z"

    private fun queuedSubtask(tempId: Long, title: String, parentId: Long, createdAt: String = queuedAt): PendingActionEntity {
        val task = Task(
            id = tempId,
            title = title,
            projectId = 7,
            created = createdAt,
            updated = createdAt,
            relatedTasks = mapOf(RelationKind.PARENTTASK to listOf(Task(id = parentId, title = "Parent", projectId = 7))),
        )
        return PendingActionEntity(
            entityType = "task",
            entityId = tempId,
            actionType = "create",
            payload = authTestJson.encodeToString(Task.serializer(), task),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    @Test
    fun `after creating the subtask the engine links it to its parent`() = runTest {
        val bodies = mutableListOf<String?>()
        val h = SyncEngineHarness { request ->
            bodies += (request.body as? TextContent)?.text
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" ->
                    created("""{"id":501,"title":"Child","project_id":7}""")
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/42/relations" ->
                    respond("{}", HttpStatusCode.Created, authTestJsonHeaders)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedSubtask(tempId = -1, title = "Child", parentId = 42))

        h.engine.performSync()

        assertEquals(1, h.count(HttpMethod.Post, "/tasks/42/relations"))
        val relationBody = bodies.filterNotNull().single { it.contains("relation_kind") }
        assertTrue(relationBody.contains("\"other_task_id\":501"), relationBody)
        assertTrue(relationBody.contains("\"relation_kind\":\"subtask\""), relationBody)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `a link that already exists is not an error`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" ->
                    created("""{"id":501,"title":"Child","project_id":7}""")
                request.method == HttpMethod.Post && request.url.encodedPath == "/tasks/42/relations" ->
                    respond("""{"title":"Conflict","status":409,"code":4005}""", HttpStatusCode.Conflict, authTestJsonHeaders)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedSubtask(tempId = -1, title = "Child", parentId = 42))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty(), "the action finished")
        h.close()
    }

    @Test
    fun `a parent created in the same run is linked by its new id`() = runTest {
        var creates = 0
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" -> {
                    creates++
                    created("""{"id":${499 + creates},"title":"t$creates","project_id":7}""")
                }
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/relations") ->
                    respond("{}", HttpStatusCode.Created, authTestJsonHeaders)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "Parent", createdAt = "2026-10-06T09:00:00Z"))
        h.pendingActionDao.insert(queuedSubtask(tempId = -2, title = "Child", parentId = -1, createdAt = "2026-10-06T09:05:00Z"))

        h.engine.performSync()

        assertEquals(1, h.count(HttpMethod.Post, "/tasks/500/relations"), h.requests().toString())
        h.close()
    }

    @Test
    fun `a parent created in an earlier run is still found by its new id`() = runTest {
        var creates = 0
        var childAttempts = 0
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" -> {
                    creates++
                    if (creates == 1) {
                        created("""{"id":500,"title":"Parent","project_id":7}""")
                    } else {
                        childAttempts++
                        if (childAttempts == 1) {
                            respond("", HttpStatusCode.ServiceUnavailable)
                        } else {
                            created("""{"id":501,"title":"Child","project_id":7}""")
                        }
                    }
                }
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/relations") ->
                    respond("{}", HttpStatusCode.Created, authTestJsonHeaders)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "Parent", createdAt = "2026-10-06T09:00:00Z"))
        h.pendingActionDao.insert(queuedSubtask(tempId = -2, title = "Child", parentId = -1, createdAt = "2026-10-06T09:05:00Z"))

        h.engine.performSync() // parent created; the child's create hits a 503 and stays queued
        assertEquals(0, h.count(HttpMethod.Post, "/tasks/500/relations"))
        h.newEngine().performSync() // a later run, with no memory of the first one

        assertEquals(1, h.count(HttpMethod.Post, "/tasks/500/relations"), h.requests().toString())
        h.close()
    }
}
