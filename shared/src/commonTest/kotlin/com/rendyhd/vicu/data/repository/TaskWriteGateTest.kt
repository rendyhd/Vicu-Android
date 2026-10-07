package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.sync.LabelRefresher
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.FakeLabelDao
import com.rendyhd.vicu.worker.SyncEngineHarness
import com.rendyhd.vicu.worker.awaitUntil
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A change to a task while an older change for it waits in the queue joins the queue. Sent
 * directly it would reach the server first and then be overwritten when the older one is replayed
 * (seen on a device: an edit made online after a queued one was lost, and an Undo of a queued
 * completion was undone by the replay).
 */
class TaskWriteGateTest {

    /** Task 42 on a fake server and in Room; task and label repositories and a sync engine share one queue. */
    private class Rig {
        val server = FakeTaskServer().apply { seed(42, "Original", "", done = false, projectId = 7) }

        /** Every request as "METHOD /path", including those answered with an error. */
        private val sentLog = MutableStateFlow<List<String>>(emptyList())
        val sent: List<String> get() = sentLog.value

        /** Label requests, which the fake server does not model. */
        val labelRequests = mutableListOf<String>()

        /** Every request answers 503 while set: the server is having trouble. */
        @kotlin.concurrent.Volatile
        var down = false

        /** When set, a request completes [arrived] and then waits for this: it is in flight. */
        @kotlin.concurrent.Volatile
        var hold: CompletableDeferred<Unit>? = null
        val arrived = CompletableDeferred<Unit>()

        val taskDao = FakeTaskDao(listOf(TaskEntity(id = 42, title = "Original", projectId = 7)))
        val pendingActionDao = FakePendingActionDao()
        val labelDao = FakeLabelDao()
        val mapper = TaskMapper(authTestJson)

        private suspend fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
            sentLog.update { it + "${request.method.value} ${request.url.encodedPath}" }
            hold?.let {
                arrived.complete(Unit)
                it.await()
            }
            if (down) return respond("", HttpStatusCode.ServiceUnavailable)
            val method = request.method.value
            val path = request.url.encodedPath
            return when {
                method == "POST" && Regex("^/tasks/\\d+/labels$").matches(path) -> {
                    labelRequests += "$method $path"
                    respond("", HttpStatusCode.Created)
                }
                method == "DELETE" && Regex("^/tasks/\\d+/labels/\\d+$").matches(path) -> {
                    labelRequests += "$method $path"
                    respond("", HttpStatusCode.NoContent)
                }
                else -> server.handle(this, request)
            }
        }

        val tasks = TaskRepositoryHarness(taskDao = taskDao, pendingActionDao = pendingActionDao) { route(it) }
        val labels = LabelRepositoryImpl(
            labelDao = labelDao,
            taskDao = taskDao,
            pendingActionDao = pendingActionDao,
            api = tasks.api,
            labelMapper = LabelMapper(),
            taskMapper = mapper,
            platformHooks = tasks.hooks,
            json = authTestJson,
            tempIds = tasks.tempIds,
            labelRefresher = LabelRefresher(labelDao, taskDao, pendingActionDao, tasks.api, LabelMapper(), mapper),
            writeGate = tasks.writeGate,
            mappingDispatcher = Dispatchers.Unconfined,
        )
        val sync = SyncEngineHarness(taskDao = taskDao, pendingActionDao = pendingActionDao, labelDao = labelDao) { route(it) }

        suspend fun current(): Task = with(mapper) { checkNotNull(taskDao.entity(42)).toDomain() }

        fun patchesTo42(): Int = sent.count { it == "PATCH /tasks/42" }

        suspend fun queued(): List<PendingActionEntity> = pendingActionDao.snapshot()
    }

    private fun PendingActionEntity.field(name: String): String? =
        (Json.parseToJsonElement(payload).jsonObject[name])?.jsonPrimitive?.content

    @Test
    fun `an edit made while an older one waits in the queue joins it, and the newer value wins`() = runTest {
        val rig = Rig()
        rig.down = true
        rig.tasks.repository.update(rig.current().copy(title = "B"))
        assertEquals("B", rig.queued().single().field("title"), "the server was unreachable: queued")

        rig.down = false
        val sentBefore = rig.sent.size
        val result = rig.tasks.repository.update(rig.current().copy(title = "C"))

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(sentBefore, rig.sent.size, "nothing is sent past the queued change")
        val waiting = rig.queued().single()
        assertEquals("C", waiting.field("title"), "merged into the queued change")
        assertEquals("C", rig.current().title, "the local row shows the newest edit")

        assertTrue(rig.sync.engine.performSync())
        assertEquals("C", rig.server.row(42).title)
        assertEquals(2, rig.patchesTo42(), "the attempt that failed, then one merged request")
        assertTrue(rig.queued().isEmpty())
        rig.sync.close()
    }

    @Test
    fun `undoing a completion that waits in the queue leaves the task open after the replay`() = runTest {
        val rig = Rig()
        rig.down = true
        rig.tasks.repository.setDone(42, true)
        assertEquals("true", rig.queued().single().field("done"))

        rig.down = false
        val result = rig.tasks.repository.setDone(42, false)

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(1, rig.patchesTo42(), "the undo did not overtake the queued completion")
        assertEquals("false", rig.queued().single().field("done"))
        assertFalse(rig.current().done)

        assertTrue(rig.sync.engine.performSync())
        assertFalse(rig.server.row(42).done, "the replay does not complete the task again")
        assertFalse(checkNotNull(rig.taskDao.entity(42)).done)
        rig.sync.close()
    }

    @Test
    fun `a change the server refused for good does not hold the next one back`() = runTest {
        val rig = Rig()
        rig.pendingActionDao.insert(
            PendingActionEntity(
                entityType = "task", entityId = 42, actionType = "update", payload = """{"priority":3}""",
                status = "failed", createdAt = "2026-10-06T10:00:00Z", updatedAt = "2026-10-06T10:00:00Z",
            ),
        )

        rig.tasks.repository.update(rig.current().copy(title = "C"))

        assertEquals(1, rig.patchesTo42(), "sent at once")
        assertEquals("C", rig.server.row(42).title)
        assertEquals(listOf("failed"), rig.queued().map { it.status }, "the failed change stays for Retry or Discard")
    }

    @Test
    fun `completing and deleting a task with a queued change join the queue too`() = runTest {
        val rig = Rig()
        rig.down = true
        rig.tasks.repository.update(rig.current().copy(title = "B"))
        rig.down = false

        rig.tasks.repository.setDone(42, true)
        assertEquals(1, rig.patchesTo42(), "only the edit that failed")
        assertEquals("true", rig.queued().single().field("done"))

        rig.tasks.repository.delete(42)
        assertTrue(rig.sent.none { it.startsWith("DELETE") }, "the delete waits behind the queue")
        assertEquals(listOf("delete"), rig.queued().map { it.actionType })

        assertTrue(rig.sync.engine.performSync())
        assertTrue(42L !in rig.server.rows)
        rig.sync.close()
    }

    @Test
    fun `a label change waits behind a queued edit of its task`() = runTest {
        val rig = Rig()
        rig.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        rig.down = true
        rig.tasks.repository.update(rig.current().copy(title = "B"))
        rig.down = false

        val result = rig.labels.addToTask(42, 5)

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(rig.labelRequests.isEmpty(), "not sent past the queued edit")
        assertEquals(listOf("update", "add_label"), rig.queued().map { it.actionType })
        assertTrue(checkNotNull(rig.taskDao.entity(42)).labelsJson.contains("\"id\":5"), "shown on the task at once")

        assertTrue(rig.sync.engine.performSync())
        assertEquals(listOf("POST /tasks/42/labels"), rig.labelRequests)
        assertEquals("B", rig.server.row(42).title)
        rig.sync.close()
    }

    @Test
    fun `a label change is sent at once when nothing waits for the task`() = runTest {
        val rig = Rig()
        rig.labelDao.upsert(LabelEntity(id = 5, title = "home"))

        rig.labels.addToTask(42, 5)

        assertEquals(listOf("POST /tasks/42/labels"), rig.labelRequests)
        assertTrue(rig.queued().isEmpty())
    }

    @Test
    fun `an edit made while the previous one is in flight waits for it and is queued when it fails`() = runTest {
        val rig = Rig()
        val release = CompletableDeferred<Unit>()
        rig.hold = release

        withContext(Dispatchers.Default) {
            val first = async { rig.tasks.repository.update(rig.current().copy(title = "B")) }
            rig.arrived.await()
            val second = async { rig.tasks.repository.update(rig.current().copy(title = "C")) }
            awaitUntil { rig.tasks.writeGate.writersFor(42) == 2 }

            // The first request now fails: the server went away while it was in flight.
            rig.down = true
            rig.hold = null
            release.complete(Unit)
            first.await()
            second.await()
        }

        assertEquals(1, rig.patchesTo42(), "only the first request was sent")
        assertEquals("C", rig.queued().single().field("title"), "the second joined the first in the queue")
        assertEquals(0, rig.tasks.writeGate.writersFor(42))

        rig.down = false
        assertTrue(rig.sync.engine.performSync())
        assertEquals("C", rig.server.row(42).title)
        rig.sync.close()
    }

    @Test
    fun `an edit cancelled while it waits for its turn is queued, not lost`() = runTest {
        val rig = Rig()
        val release = CompletableDeferred<Unit>()
        rig.hold = release

        withContext(Dispatchers.Default) {
            val first = async { rig.tasks.repository.update(rig.current().copy(title = "B")) }
            rig.arrived.await()
            val second = launch { rig.tasks.repository.update(rig.current().copy(title = "C")) }
            awaitUntil { rig.tasks.writeGate.writersFor(42) == 2 }
            second.cancelAndJoin()
            rig.hold = null
            release.complete(Unit)
            first.await()
        }

        assertEquals("B", rig.server.row(42).title, "the first went through")
        assertEquals("C", rig.queued().single().field("title"), "the cancelled one waits in the queue")
        assertTrue(rig.sync.engine.performSync())
        assertEquals("C", rig.server.row(42).title)
        rig.sync.close()
    }

    @Test
    fun `an edit of an offline task whose create is being sent lands on the created task`() = runTest {
        val rig = Rig()
        // Created offline: a local row with a temporary id and a queued create.
        rig.down = true
        val created = rig.tasks.repository.create(Task(id = 0, title = "Buy milk", projectId = 7))
        val tempId = (created as NetworkResult.Success).data.id
        assertTrue(tempId < 0L)
        rig.down = false

        val release = CompletableDeferred<Unit>()
        rig.hold = release
        withContext(Dispatchers.Default) {
            val run = async { rig.sync.engine.performSync() }
            rig.arrived.await()
            assertEquals("processing", rig.queued().single().status, "the create is being sent")

            // Edited while the create is in flight: it cannot go into the create any more.
            rig.tasks.repository.update(
                with(rig.mapper) { checkNotNull(rig.taskDao.entity(tempId)).toDomain() }.copy(title = "Buy oat milk"),
            )
            rig.hold = null
            release.complete(Unit)
            assertTrue(run.await())
        }

        val serverTask = rig.server.rows.values.single { it.id != 42L }
        assertEquals("Buy oat milk", serverTask.title, "the edit followed the create in the same run")
        assertTrue(rig.queued().isEmpty())
        rig.sync.close()
    }

    @Test
    fun `the gate counts label changes on the task, not on other tasks`() = runTest {
        val dao = FakePendingActionDao()
        fun label(type: String, payload: String, status: String = "pending") = PendingActionEntity(
            entityType = "label", entityId = 5, actionType = type, payload = payload, status = status,
            createdAt = "2026-10-06T10:00:00Z", updatedAt = "2026-10-06T10:00:00Z",
        )
        dao.insert(label("add_label", "142:5"))
        dao.insert(label("remove_label", "42:5", status = "failed"))
        assertFalse(dao.hasWaitingForTask(42))
        assertTrue(dao.hasWaitingForTask(142))
        dao.insert(label("add_label", "42:6"))
        assertTrue(dao.hasWaitingForTask(42))
    }
}
