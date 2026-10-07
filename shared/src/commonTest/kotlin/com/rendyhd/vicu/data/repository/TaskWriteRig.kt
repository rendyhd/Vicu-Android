package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.sync.LabelRefresher
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.worker.FakeLabelDao
import com.rendyhd.vicu.worker.SyncEngineHarness
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Task 42 on a fake server and in Room; task and label repositories and a sync engine share one queue. */
class TaskWriteRig {
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
            method == "POST" && Regex("^/tasks/\\d+/relations$").matches(path) -> respond("", HttpStatusCode.Created)
            method == "DELETE" && Regex("^/tasks/\\d+/relations/.+$").matches(path) -> respond("", HttpStatusCode.NoContent)
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

/** A string field of a queued merge patch. */
fun PendingActionEntity.field(name: String): String? =
    (Json.parseToJsonElement(payload).jsonObject[name])?.jsonPrimitive?.content
