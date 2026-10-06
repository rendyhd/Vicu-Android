package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.ScheduleAction
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.DayClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One request the repository sent through the mock engine. */
data class SentRequest(val method: String, val path: String, val body: String?) {
    val bodyJson: JsonObject? get() = body?.let { Json.parseToJsonElement(it).jsonObject }
}

/**
 * A [TaskRepositoryImpl] wired to in-memory fakes and a mock HTTP engine. [handler] answers the
 * API calls; every request is recorded in [sent] before the handler runs.
 */
class TaskRepositoryHarness(
    val taskDao: FakeTaskDao = FakeTaskDao(),
    val pendingActionDao: FakePendingActionDao = FakePendingActionDao(),
    val hooks: RecordingRepositoryHooks = RecordingRepositoryHooks(),
    scheduleAction: ScheduleAction = ScheduleAction.DUE_TODAY,
    /** A clock frozen at the real day unless a test needs the day to change. */
    dayClock: DayClock = DayClock(CoroutineScope(Job()), ticking = false),
    val tempIds: TempIdGenerator = TempIdGenerator(InMemoryPreferencesDataStore()),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val json: Json = authTestJson
    val mapper = TaskMapper(json)
    val sent = mutableListOf<SentRequest>()

    private val behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore())
    private val scheduleActionDefault = scheduleAction

    private val client = HttpClient(
        MockEngine { request ->
            val body = (request.body as? TextContent)?.text
            sent += SentRequest(request.method.value, request.url.encodedPath, body)
            handler(request)
        },
    ) {
        install(ContentNegotiation) { json(authTestJson) }
    }

    val repository = TaskRepositoryImpl(
        taskDao = taskDao,
        api = VikunjaApiService(client, authTestJson),
        pendingActionDao = pendingActionDao,
        taskMapper = mapper,
        platformHooks = hooks,
        json = json,
        behaviorPrefsStore = behaviorPrefsStore,
        logbookPrefsStore = LogbookPrefsStore(InMemoryPreferencesDataStore()),
        dayClock = dayClock,
        tempIds = tempIds,
    )

    suspend fun initScheduleAction() {
        behaviorPrefsStore.setScheduleAction(scheduleActionDefault)
    }

    fun patches(): List<SentRequest> = sent.filter { it.method == "PATCH" }

    companion object {
        /** A 503, which the repository treats as "server unreachable": the change is queued. */
        fun MockRequestHandleScope.serviceUnavailable(): HttpResponseData =
            respond(content = "", status = HttpStatusCode.ServiceUnavailable)

        fun MockRequestHandleScope.jsonOk(body: String): HttpResponseData =
            respond(content = body, status = HttpStatusCode.OK, headers = authTestJsonHeaders)
    }
}

private val relatedTasksSerializer =
    MapSerializer(String.serializer(), ListSerializer(TaskDto.serializer()))

/** A cached row with relations and attachments, the data an optimistic edit must not lose. */
fun cachedTaskEntity(
    id: Long = 42,
    title: String = "Original",
    description: String = "Original description",
    priority: Int = 2,
    projectId: Long = 7,
    dueDate: String = "2026-10-08T21:59:59Z",
): TaskEntity {
    val related = mapOf(
        "subtask" to listOf(TaskDto(id = 43, title = "Child", projectId = projectId)),
        "related" to listOf(TaskDto(id = 44, title = "Sibling", projectId = projectId)),
    )
    return TaskEntity(
        id = id,
        title = title,
        description = description,
        priority = priority,
        projectId = projectId,
        dueDate = dueDate,
        position = 1234.5,
        created = "2026-10-01T08:00:00Z",
        updated = "2026-10-02T08:00:00Z",
        createdById = 3,
        createdByUsername = "rendy",
        labelsJson = """[{"id":5,"title":"home","hex_color":"ff0000"}]""",
        attachmentsJson = """[{"id":9,"task_id":$id,"file":{"name":"a.png","mime":"image/png","size":10}}]""",
        relatedTasksJson = authTestJson.encodeToString(relatedTasksSerializer, related),
    )
}
