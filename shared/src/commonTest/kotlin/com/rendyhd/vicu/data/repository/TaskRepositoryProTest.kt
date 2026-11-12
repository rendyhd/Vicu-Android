package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.dao.RelationQueue
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RelationKind
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What Pro changes in the task repository: "after" goes through the queue, a flag is its own write. */
class TaskRepositoryProTest {

    private fun row(id: Long, title: String) = TaskEntity(id = id, title = title, projectId = 7, created = "2026-11-01T08:00:00Z", updated = "2026-11-01T08:00:00Z")

    private suspend fun prefs(proOn: Boolean) = BehaviorPrefsStore(InMemoryPreferencesDataStore()).also { it.setProEnabled(proOn) }

    @Test
    fun `with Pro off a relation is sent as before and an unreachable server is an error`() = runTest {
        val h = TaskRepositoryHarness(
            taskDao = FakeTaskDao(listOf(row(5, "Review"), row(6, "Draft"))),
            behaviorPrefsStore = prefs(proOn = false),
        ) { serviceUnavailable() }

        val result = h.repository.createRelation(5, 6, RelationKind.BLOCKED)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertEquals(listOf("POST /tasks/5/relations"), h.sent.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `with Pro on an after relation is shown at once and queued while the server is away`() = runTest {
        val h = TaskRepositoryHarness(
            taskDao = FakeTaskDao(listOf(row(5, "Review"), row(6, "Draft"))),
            behaviorPrefsStore = prefs(proOn = true),
        ) { serviceUnavailable() }

        val result = h.repository.createRelation(5, 6, RelationKind.BLOCKED)

        assertIs<NetworkResult.Success<*>>(result)
        val queued = h.pendingActionDao.snapshot().single()
        assertEquals(RelationQueue.ENTITY_TYPE to RelationQueue.ADD, queued.entityType to queued.actionType)
        assertEquals(5L, queued.entityId)
        assertEquals(6L, RelationQueue.decode(queued.payload)?.otherTaskId)
        assertTrue(h.taskDao.entity(5)!!.relatedTasksJson.contains("\"blocked\""))
        assertTrue(h.taskDao.entity(6)!!.relatedTasksJson.contains("\"blocking\""))

        // Removing it before it was sent cancels both.
        h.repository.deleteRelation(5, RelationKind.BLOCKED, 6)
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        assertTrue(!h.taskDao.entity(5)!!.relatedTasksJson.contains("\"blocked\""))
    }

    @Test
    fun `with Pro on a relation that is already there counts as made`() = runTest {
        val h = TaskRepositoryHarness(
            taskDao = FakeTaskDao(listOf(row(5, "Review"), row(6, "Draft"))),
            behaviorPrefsStore = prefs(proOn = true),
        ) { respond("""{"title":"Conflict","status":409,"code":4008}""", HttpStatusCode.Conflict, authTestJsonHeaders) }

        assertIs<NetworkResult.Success<*>>(h.repository.createRelation(5, 6, RelationKind.BLOCKED))
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `a flagged new task is created, then flagged with is_favorite alone`() = runTest {
        val h = TaskRepositoryHarness { request ->
            when (request.method.value) {
                "POST" -> respond("""{"id":77,"title":"Plan","project_id":7}""", HttpStatusCode.Created, authTestJsonHeaders)
                "PATCH" -> jsonOk("""{"id":77,"title":"Plan","project_id":7,"is_favorite":true}""")
                else -> jsonOk("""{"items":[],"total":0,"page":1,"per_page":50,"total_pages":1}""")
            }
        }

        val result = h.repository.create(Task(id = 0, title = "Plan", projectId = 7, isFavorite = true))

        assertIs<NetworkResult.Success<Task>>(result)
        assertTrue(result.data.isFavorite)
        val create = h.sent.first { it.method == "POST" && it.path == "/projects/7/tasks" }
        assertTrue("is_favorite" !in create.bodyJson!!.keys, create.body.orEmpty())
        val patch = h.patches().single()
        assertEquals(setOf("is_favorite"), patch.bodyJson!!.keys)
        assertEquals(JsonPrimitive(true), patch.bodyJson!!["is_favorite"])
    }
}
