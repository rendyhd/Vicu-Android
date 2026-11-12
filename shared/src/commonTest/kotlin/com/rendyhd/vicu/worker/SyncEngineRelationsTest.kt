package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.dao.RelationPayload
import com.rendyhd.vicu.data.local.dao.RelationQueue
import com.rendyhd.vicu.data.local.dao.remapRelationAction
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.util.RelationKind
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

/**
 * Pro's "after" relations go through the offline queue (decision 24, cross-app semantics 21.2), as
 * the desktop's `offline-relations.test.ts`: an add and a removal of the same relation cancel, the
 * same add twice is one, both task ids may be temporary and are rewritten, a relation to a task
 * whose create failed fails with it, and "already there" (409) and "already gone" (404) are done.
 */
class SyncEngineRelationsTest {

    private val queuedAt = "2026-11-12T08:00:00Z"

    private fun relation(taskId: Long, otherTaskId: Long, add: Boolean = true, createdAt: String = queuedAt) = PendingActionEntity(
        entityType = RelationQueue.ENTITY_TYPE,
        entityId = taskId,
        actionType = if (add) RelationQueue.ADD else RelationQueue.REMOVE,
        payload = RelationQueue.encode(RelationPayload(otherTaskId, RelationKind.BLOCKED)),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: Long): HttpResponseData =
        respond(
            content = """{"title":"${status.description}","status":${status.value},"detail":"refused","code":$code}""",
            status = status,
            headers = authTestJsonHeaders,
        )

    @Test
    fun `replays an add and a removal`() = runTest {
        val calls = mutableListOf<String>()
        val h = SyncEngineHarness { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/tasks/5/relations" -> {
                    calls += "relate 5 ${(request.body as TextContent).text}"
                    created("{}")
                }
                request.method == HttpMethod.Delete && path == "/tasks/7/relations/blocked/8" -> {
                    calls += "unrelate 7 blocked 8"
                    respond("", HttpStatusCode.NoContent)
                }
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} $path")
            }
        }
        h.pendingActionDao.insert(relation(5, 6))
        h.pendingActionDao.insert(relation(7, 8, add = false))

        h.engine.performSync()

        assertEquals(2, calls.size, calls.toString())
        assertTrue(calls[0].contains("\"other_task_id\":6") && calls[0].contains("\"relation_kind\":\"blocked\""), calls[0])
        assertEquals("unrelate 7 blocked 8", calls[1])
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }

    @Test
    fun `cancels an add with a removal of the same relation, and the same add twice is one`() = runTest {
        val dao = FakePendingActionDao()

        dao.queueRelationAction(relation(5, 6))
        dao.queueRelationAction(relation(5, 6))
        assertEquals(1, dao.snapshot().size)

        dao.queueRelationAction(relation(5, 6, add = false))
        assertTrue(dao.snapshot().isEmpty())

        // A relation of another kind or to another task is its own change.
        dao.queueRelationAction(relation(5, 6))
        dao.queueRelationAction(relation(5, 9))
        assertEquals(2, dao.snapshot().size)
    }

    @Test
    fun `an add being sent is not cancelled by a removal`() = runTest {
        val dao = FakePendingActionDao()
        val id = dao.insert(relation(5, 6))
        dao.claim(id)

        dao.queueRelationAction(relation(5, 6, add = false))

        assertEquals(listOf(RelationQueue.ADD, RelationQueue.REMOVE), dao.snapshot().map { it.actionType })
    }

    @Test
    fun `both temporary ids are rewritten once their creates replay`() = runTest {
        var nextId = 500L
        val relations = mutableListOf<String>()
        val h = SyncEngineHarness { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/projects/7/tasks" -> {
                    val id = nextId++
                    created("""{"id":$id,"title":"T$id","project_id":7}""")
                }
                request.method == HttpMethod.Post && path.endsWith("/relations") -> {
                    relations += "$path ${(request.body as TextContent).text}"
                    created("{}")
                }
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} $path")
            }
        }
        h.pendingActionDao.insert(queuedCreate(-1, "Draft"))
        h.pendingActionDao.insert(queuedCreate(-2, "Review"))
        h.pendingActionDao.insert(relation(-2, -1, createdAt = "2026-11-12T09:00:00Z"))

        h.engine.performSync()

        assertEquals(1, relations.size, relations.toString())
        assertTrue(relations.single().startsWith("/tasks/501/relations "), relations.single())
        assertTrue(relations.single().contains("\"other_task_id\":500"), relations.single())
        assertTrue(h.pendingActionDao.snapshot().isEmpty(), h.pendingActionDao.snapshot().toString())
        h.close()
    }

    @Test
    fun `a relation to a task whose create failed goes to the failed log`() = runTest {
        val h = SyncEngineHarness { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/projects/7/tasks" -> problem(HttpStatusCode.BadRequest, 2)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} $path")
            }
        }
        h.pendingActionDao.insert(queuedCreate(-1, "Draft"))
        h.pendingActionDao.insert(relation(9, -1))

        h.engine.performSync()

        assertEquals(0, h.count(HttpMethod.Post, "/tasks/9/relations"))
        val left = h.pendingActionDao.snapshot()
        assertEquals(listOf("failed", "failed"), left.map { it.status }, left.toString())
        assertTrue(left.any { it.entityType == RelationQueue.ENTITY_TYPE })
        h.close()
    }

    @Test
    fun `already there and already gone are done`() = runTest {
        val h = SyncEngineHarness { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/tasks/5/relations" -> problem(HttpStatusCode.Conflict, 4008)
                request.method == HttpMethod.Delete && path == "/tasks/7/relations/blocked/8" -> problem(HttpStatusCode.NotFound, 4011)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} $path")
            }
        }
        h.pendingActionDao.insert(relation(5, 6))
        h.pendingActionDao.insert(relation(7, 8, add = false))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty(), h.pendingActionDao.snapshot().toString())
        h.close()
    }

    @Test
    fun `an add to a task that is gone is dropped with it`() = runTest {
        val h = SyncEngineHarness { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/tasks/5/relations" -> problem(HttpStatusCode.NotFound, 4002)
                request.method == HttpMethod.Get && path == "/tasks/5" -> problem(HttpStatusCode.NotFound, 4002)
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} $path")
            }
        }
        h.pendingActionDao.insert(relation(5, 6))

        h.engine.performSync()

        assertTrue(h.pendingActionDao.snapshot().isEmpty(), h.pendingActionDao.snapshot().toString())
        h.close()
    }

    @Test
    fun `a remap names the created task on either side`() {
        val onTask = remapRelationAction(relation(-1, 6), tempId = -1, realId = 500)
        assertEquals(500L, onTask?.entityId)
        val onOther = remapRelationAction(relation(5, -1), tempId = -1, realId = 500)
        assertEquals(5L, onOther?.entityId)
        assertEquals(500L, onOther?.payload?.let(RelationQueue::decode)?.otherTaskId)
        assertNull(remapRelationAction(relation(5, 6), tempId = -1, realId = 500))
    }
}
