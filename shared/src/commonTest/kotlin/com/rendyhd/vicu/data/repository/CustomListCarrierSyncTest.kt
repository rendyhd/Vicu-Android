package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.authHarness
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.CarrierIdStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.CarrierFinder
import com.rendyhd.vicu.data.sync.Req
import com.rendyhd.vicu.data.sync.TaskListServer
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.MutableTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A custom-list sync runs on every app open, every 30 minutes and after each edit. It must not
 * page through the completed tasks each time (A-CL-1): the carrier is fetched by id.
 */
class CustomListCarrierSyncTest {

    private val json: Json = authTestJson
    private val time = MutableTimeSource(Instant.parse("2026-10-06T12:00:00Z"))

    private class Rig(val repository: CustomListRepositoryImpl, val tasks: TaskListServer)

    private fun taskJson(task: TaskListServer.T): String =
        """{"id":${task.id},"title":${quote(task.title)},"description":${quote(task.description)},"done":true,"project_id":5}"""

    private fun quote(value: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private suspend fun TestScope.rig(tasks: TaskListServer): Rig {
        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    request.method.value == "POST" && path == "/projects/5/tasks" -> {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val created = tasks.put(
                            id = 900,
                            title = body.getValue("title").jsonPrimitive.content,
                            done = true,
                            description = body.getValue("description").jsonPrimitive.content,
                        )
                        tasks.requests += Req("POST", path, emptyMap())
                        respond(taskJson(created), HttpStatusCode.Created, authTestJsonHeaders)
                    }
                    request.method.value == "PATCH" && path.startsWith("/tasks/") -> {
                        val task = tasks.tasks.getValue(path.removePrefix("/tasks/").toLong())
                        val patch = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        task.description = patch.getValue("description").jsonPrimitive.content
                        tasks.requests += Req("PATCH", path, emptyMap())
                        respond(taskJson(task), HttpStatusCode.OK, authTestJsonHeaders)
                    }
                    else -> tasks.handle(this, request)
                }
            },
        ) {
            install(ContentNegotiation) { json(json) }
        }
        val api = VikunjaApiService(client, json)
        val storage = InMemoryTokenStorage().also {
            it.storeVikunjaUrl("https://vikunja.example")
            it.storeInboxProjectId(5)
        }
        val repository = CustomListRepositoryImpl(
            store = CustomListStore(InMemoryPreferencesDataStore()),
            api = api,
            authManager = authHarness(backgroundScope, storage) { respond("", HttpStatusCode.NotFound) }.manager,
            platformHooks = RecordingRepositoryHooks(),
            json = json,
            carrierFinder = CarrierFinder(api, CarrierIdStore(InMemoryPreferencesDataStore()), time),
        )
        return Rig(repository, tasks)
    }

    private val emptyDocument = CustomListEnvelope.encode(CustomListEnvelope.empty("desktop", 1_000L), json)

    private fun seededServer(): TaskListServer = TaskListServer().apply {
        for (id in 1L..60L) put(id, done = true)
        put(900, title = CustomListEnvelope.CARRIER_TITLE, done = true, description = emptyDocument)
    }

    private fun Rig.listings() = tasks.lists()

    @Test
    fun `the first sync scans the completed tasks and the next ones fetch the carrier by id`() = runTest {
        val rig = rig(seededServer())

        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())
        assertEquals(1, rig.listings().size, "the first sync has nothing remembered")
        rig.tasks.requests.clear()

        time.advance(10.seconds)
        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())
        time.advance(10.seconds)
        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())

        assertEquals(emptyList(), rig.listings(), "no completed task is listed again")
        assertTrue(rig.tasks.requests.all { it.path == "/tasks/900" }, rig.tasks.requests.toString())
    }

    @Test
    fun `a carrier this device creates is remembered and fetched by id afterwards`() = runTest {
        val rig = rig(TaskListServer().apply { for (id in 1L..60L) put(id, done = true) })
        rig.repository.upsert(CustomList(id = "list-1", name = "Work", filter = CustomListFilter()))

        rig.repository.sync()
        assertTrue(rig.tasks.tasks.containsKey(900), "the carrier was created")
        rig.tasks.requests.clear()

        time.advance(10.seconds)
        rig.repository.sync()

        assertEquals(emptyList(), rig.listings())
        assertTrue(rig.tasks.requests.all { it.path == "/tasks/900" }, rig.tasks.requests.toString())
    }
}
