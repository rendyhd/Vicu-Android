package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authHarness
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.util.CustomListEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A device that has never synced has no custom lists and no order of its own. Its first sync must
 * take the order from the carrier, not overwrite it with an empty order stamped "now" (which made
 * desktop's [b, a] come back as [a, b]).
 */
@OptIn(ExperimentalEncodingApi::class)
class CustomListFirstSyncOrderTest {

    private val json: Json = authTestJson

    /** Two lists that desktop ordered [list-b, list-a]; the records themselves are older than "now". */
    private val desktopDocument = """
        {
          "version": 1,
          "lists": {
            "list-a": {
              "value": {"id": "list-a", "name": "A", "icon": "", "filter": {"project_ids": [], "sort_by": "due_date"}},
              "revision": {"wall_time_ms": 1000, "counter": 0, "device_id": "desktop"}
            },
            "list-b": {
              "value": {"id": "list-b", "name": "B", "icon": "", "filter": {"project_ids": [], "sort_by": "due_date"}},
              "revision": {"wall_time_ms": 1000, "counter": 1, "device_id": "desktop"}
            }
          },
          "order": {"ids": ["list-b", "list-a"], "revision": {"wall_time_ms": 2000, "counter": 0, "device_id": "desktop"}}
        }
    """.trimIndent()

    private fun marker(documentJson: String): String =
        "<!-- vicu-custom-lists:v1:${Base64.UrlSafe.encode(documentJson.encodeToByteArray()).trimEnd('=')} -->"

    private fun rawDocument(description: String): JsonObject {
        val encoded = Regex("""vicu-custom-lists:v1:([A-Za-z0-9_-]+)""").find(description)!!.groupValues[1]
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        return Json.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()).jsonObject
    }

    private fun orderIn(description: String): List<String> =
        rawDocument(description).getValue("order").jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content }

    /** One carrier task on an in-memory server; [description] is what a GET returns right now. */
    private class CarrierServer(var description: String) {
        val writes = mutableListOf<String>()

        fun taskJson(): String {
            val escaped = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(description))
            return """{"id":900,"title":"${CustomListEnvelope.CARRIER_TITLE}","description":$escaped,"done":true,"project_id":5}"""
        }
    }

    private class Rig(val repository: CustomListRepositoryImpl, val store: CustomListStore, val server: CarrierServer)

    private fun TestScope.rig(server: CarrierServer): Rig {
        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    request.method == HttpMethod.Get && path == "/tasks" ->
                        respond(
                            """{"items":[${server.taskJson()}],"total":1,"page":1,"per_page":100,"total_pages":1}""",
                            HttpStatusCode.OK,
                            authTestJsonHeaders,
                        )
                    request.method == HttpMethod.Get && path == "/tasks/900" ->
                        respond(server.taskJson(), HttpStatusCode.OK, authTestJsonHeaders)
                    request.method == HttpMethod.Patch && path == "/tasks/900" -> {
                        val patch = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val written = patch.getValue("description").jsonPrimitive.content
                        server.writes += written
                        server.description = written
                        respond(server.taskJson(), HttpStatusCode.OK, authTestJsonHeaders)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ContentNegotiation) { json(json) }
        }
        val store = CustomListStore(InMemoryPreferencesDataStore())
        val repository = CustomListRepositoryImpl(
            store = store,
            api = VikunjaApiService(client, json),
            authManager = authHarness(backgroundScope) { respond("", HttpStatusCode.NotFound) }.manager,
            platformHooks = RecordingRepositoryHooks(),
            json = json,
        )
        return Rig(repository, store, server)
    }

    @Test
    fun `a fresh device takes the order from the carrier and does not write it back`() = runTest {
        val rig = rig(CarrierServer(marker(desktopDocument)))

        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())

        assertEquals(listOf("list-b", "list-a"), rig.repository.lists.first().map { it.id })
        assertTrue(rig.server.writes.isEmpty(), "a fresh device has nothing to say about the order")
        assertEquals(listOf("list-b", "list-a"), orderIn(rig.server.description))
    }

    @Test
    fun `any real order revision on the carrier beats the fresh empty one`() = runTest {
        val tiny = """{"version":1,"lists":{},"order":{"ids":[],"revision":{"wall_time_ms":5,"counter":0,"device_id":"x"}}}"""
        val rig = rig(CarrierServer(marker(tiny)))

        rig.repository.sync()

        // The fresh state's order is stamped with wall time 0, so even a revision from 1970 wins.
        val state = assertNotNull(rig.store.getSyncState())
        assertEquals(5L, state.document.order.revision.wallTimeMs)
        assertTrue(rig.server.writes.isEmpty())
    }

    @Test
    fun `an order this device sets after the first sync still wins`() = runTest {
        val rig = rig(CarrierServer(marker(desktopDocument)))
        rig.repository.sync()

        rig.repository.reorder(fromIndex = 0, toIndex = 1)
        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())

        assertEquals(listOf("list-a", "list-b"), orderIn(rig.server.description))
        assertEquals(listOf("list-a", "list-b"), rig.repository.lists.first().map { it.id })
    }
}
