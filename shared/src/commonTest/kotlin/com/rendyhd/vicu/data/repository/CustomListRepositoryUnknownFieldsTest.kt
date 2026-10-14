package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authHarness
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.CarrierIdStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.CarrierFinder
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The custom-list carrier is shared with desktop, which may add fields this version does not
 * know. An edit made here must write them back (docs/cross-app-semantics-v1.md, section 3).
 */
@OptIn(ExperimentalEncodingApi::class)
class CustomListRepositoryUnknownFieldsTest {

    private val json: Json = authTestJson

    /** The list as a newer desktop writes it, with fields Android has never heard of. */
    private val desktopDocument = """
        {
          "version": 1,
          "lists": {
            "list-1": {
              "value": {
                "id": "list-1", "name": "Work", "icon": "work",
                "color": "#ff8800",
                "pinned": {"slot": 2, "tags": ["a", "b"]},
                "filter": {
                  "project_ids": [10], "project_filter_mode": "include", "add_to_project_id": 10,
                  "sort_by": "due_date", "order_by": "asc", "due_date_filter": "this_week",
                  "priority_filter": [], "label_ids": [], "include_done": false,
                  "include_today_all_projects": false,
                  "assignee_filter": [5, 6], "snooze_until": null,
                  "future": {"nested": {"deep": [1, 2, 3]}}
                }
              },
              "revision": {"wall_time_ms": 1000, "counter": 0, "device_id": "desktop"}
            }
          },
          "order": {"ids": ["list-1"], "revision": {"wall_time_ms": 1000, "counter": 1, "device_id": "desktop"}}
        }
    """.trimIndent()

    private fun marker(documentJson: String): String =
        "<!-- vicu-custom-lists:v1:${Base64.UrlSafe.encode(documentJson.encodeToByteArray()).trimEnd('=')} -->"

    private fun rawDocument(description: String): JsonObject {
        val encoded = Regex("""vicu-custom-lists:v1:([A-Za-z0-9_-]+)""").find(description)!!.groupValues[1]
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        return Json.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()).jsonObject
    }

    /** One carrier task on an in-memory server; [description] is what a GET returns right now. */
    private class CarrierServer(var description: String) {
        val writes = mutableListOf<String>()

        fun taskJson(): String {
            val escaped = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(description))
            return """{"id":900,"title":"${CustomListEnvelope.CARRIER_TITLE}","description":$escaped,"done":true,"project_id":5}"""
        }
    }

    private class Rig(
        val repository: CustomListRepositoryImpl,
        val store: CustomListStore,
        val server: CarrierServer,
    )

    private fun kotlinx.coroutines.test.TestScope.rig(server: CarrierServer): Rig {
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
        val api = VikunjaApiService(client, json)
        val repository = CustomListRepositoryImpl(
            store = store,
            api = api,
            authManager = authHarness(backgroundScope) { respond("", HttpStatusCode.NotFound) }.manager,
            platformHooks = RecordingRepositoryHooks(),
            json = json,
            carrierFinder = CarrierFinder(api, CarrierIdStore(InMemoryPreferencesDataStore())),
        )
        return Rig(repository, store, server)
    }

    @Test
    fun `an edit made here writes back the fields desktop added`() = runTest {
        val rig = rig(CarrierServer(marker(desktopDocument)))

        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())
        val pulled = rig.repository.lists.first().single()
        assertEquals("Work", pulled.name)
        assertEquals("this_week", pulled.filter.dueDateFilter)

        // The user turns overdue off and renames the list on this device.
        rig.repository.upsert(
            pulled.copy(
                name = "Work (phone)",
                filter = pulled.filter.copy(includeOverdue = false),
            ),
        )
        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())

        val written = rawDocument(rig.server.description)
        val value = written.getValue("lists").jsonObject.getValue("list-1").jsonObject.getValue("value").jsonObject
        assertEquals("Work (phone)", value.getValue("name").jsonPrimitive.content)
        assertEquals(JsonPrimitive("#ff8800"), value.getValue("color"))
        assertEquals(Json.parseToJsonElement("""{"slot":2,"tags":["a","b"]}"""), value.getValue("pinned"))

        val filter = value.getValue("filter").jsonObject
        assertFalse(filter.getValue("include_overdue").jsonPrimitive.boolean)
        assertEquals(Json.parseToJsonElement("[5,6]"), filter.getValue("assignee_filter"))
        assertEquals(JsonNull, filter.getValue("snooze_until"))
        assertEquals(Json.parseToJsonElement("""{"nested":{"deep":[1,2,3]}}"""), filter.getValue("future"))
        assertFalse("__unknown__" in value, "the holder key never reaches the wire")
        assertFalse("__unknown__" in filter)
    }

    @Test
    fun `turning overdue back on removes the key and keeps the other fields`() = runTest {
        val withOverdueOff = desktopDocument.replace(
            "\"include_today_all_projects\": false,",
            "\"include_today_all_projects\": false, \"include_overdue\": false,",
        )
        val rig = rig(CarrierServer(marker(withOverdueOff)))
        rig.repository.sync()
        val pulled = rig.repository.lists.first().single()
        assertEquals(false, pulled.filter.includeOverdue)

        rig.repository.upsert(pulled.copy(filter = pulled.filter.copy(includeOverdue = null)))
        rig.repository.sync()

        val filter = rawDocument(rig.server.description)
            .getValue("lists").jsonObject.getValue("list-1").jsonObject
            .getValue("value").jsonObject.getValue("filter").jsonObject
        assertFalse("include_overdue" in filter, "the key is removed when overdue is on again")
        assertTrue("future" in filter)
        assertTrue("assignee_filter" in filter)
    }

    @Test
    fun `a sync that changes nothing does not rewrite the carrier`() = runTest {
        val rig = rig(CarrierServer(marker(desktopDocument)))
        // The first sync only reads: a fresh device's empty order is stamped 0, so it never beats
        // the desktop's (CustomListFirstSyncOrderTest).
        rig.repository.sync()
        assertTrue(rig.server.writes.isEmpty(), "a fresh device does not write the carrier back")
        rig.server.writes.clear()

        assertEquals(CustomListSyncStatus.Idle, rig.repository.sync())

        assertTrue(rig.server.writes.isEmpty(), "the extra fields compare equal, so there is nothing to write")
    }

    @Test
    fun `the stored sync state still holds the unknown fields after a restart`() = runTest {
        val rig = rig(CarrierServer(marker(desktopDocument)))
        rig.repository.sync()

        val state = assertNotNull(rig.store.getSyncState())
        val record = CustomListEnvelope.activeLists(state.document).single()

        assertEquals(setOf("color", "pinned"), record.unknown.keys)
        assertEquals(setOf("assignee_filter", "snooze_until", "future"), record.filter.unknown.keys)
        assertIs<CustomListSyncStatus.Idle>(rig.repository.syncStatus.value)
    }
}
