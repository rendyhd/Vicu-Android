package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.CurrentUser
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.CarrierIdStore
import com.rendyhd.vicu.data.local.ProLedgerStore
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.CarrierFinder
import com.rendyhd.vicu.data.sync.Req
import com.rendyhd.vicu.data.sync.SyncedDocumentStatus
import com.rendyhd.vicu.data.sync.TaskListServer
import com.rendyhd.vicu.util.MutableTimeSource
import com.rendyhd.vicu.util.ProLedgerEnvelope
import com.rendyhd.vicu.util.pro.LedgerRules
import com.rendyhd.vicu.util.pro.ProLedger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Pro ledger's sync (cross-app semantics 21.7): only while Pro is on, merged, remapped, archived. */
class ProLedgerRepositoryTest {

    private val now = Instant.parse("2026-11-12T12:00:00Z")

    private class Rig(val repo: ProLedgerRepository, val tasks: TaskListServer, val prefs: BehaviorPrefsStore, val store: ProLedgerStore) {
        fun creates() = tasks.requests.filter { it.method == "POST" }
        fun carrier(): ProLedger? = tasks.tasks.values.firstOrNull { ProLedgerEnvelope.hasMarker(it.description) }
            ?.let { ProLedgerEnvelope.parse(it.description).document }
        fun parts(): List<JsonObject> = tasks.tasks.values.filter { ProLedgerEnvelope.hasArchiveMarker(it.description) }
            .mapNotNull { ProLedgerEnvelope.parseArchive(it.description).document }
    }

    private suspend fun rig(tasks: TaskListServer = TaskListServer(), proOn: Boolean = true): Rig {
        var nextId = 950L
        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val id = path.removePrefix("/tasks/").toLongOrNull()
                when {
                    request.method.value == "POST" && path.startsWith("/projects/") -> {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val created = tasks.put(nextId++, title = body.getValue("title").jsonPrimitive.content, done = true,
                            description = body.getValue("description").jsonPrimitive.content)
                        tasks.requests += Req("POST", path, emptyMap())
                        respond("""{"id":${created.id},"done":true,"project_id":5,"created_by":{"id":1}}""", HttpStatusCode.Created, authTestJsonHeaders)
                    }
                    request.method.value == "PATCH" && id != null -> {
                        val patch = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val task = tasks.tasks.getValue(id)
                        patch["description"]?.let { task.description = it.jsonPrimitive.content }
                        tasks.requests += Req("PATCH", path, emptyMap())
                        respond("""{"id":$id,"done":true,"project_id":5,"created_by":{"id":1}}""", HttpStatusCode.OK, authTestJsonHeaders)
                    }
                    else -> tasks.handle(this, request)
                }
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        val api = VikunjaApiService(client, authTestJson)
        val time = MutableTimeSource(now)
        val prefs = BehaviorPrefsStore(InMemoryPreferencesDataStore())
        prefs.setProEnabled(proOn)
        val store = ProLedgerStore(InMemoryPreferencesDataStore())
        val repo = ProLedgerRepository(
            store = store,
            api = api,
            carrierFinder = CarrierFinder(api, CarrierIdStore(InMemoryPreferencesDataStore()), time),
            currentUser = CurrentUser.fixed(1),
            serverKey = { "https://vikunja.example" },
            inboxProjectId = { 5L },
            behaviorPrefs = prefs,
            platformHooks = RecordingRepositoryHooks(),
            time = time,
        )
        return Rig(repo, tasks, prefs, store)
    }

    private fun promise(person: Long, vararg verdicts: JsonObject) = JsonObject(
        mapOf(
            "dir" to JsonPrimitive("theirs"),
            "person" to JsonPrimitive(person),
            "verdicts" to JsonArray(verdicts.toList()),
        ),
    )

    private fun verdict(id: String, at: String, kind: String) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "at" to JsonPrimitive(at), "kind" to JsonPrimitive(kind)))

    @Test
    fun `with Pro off nothing is read or written and a change stays on the device`() = runTest {
        val rig = rig(proOn = false)
        rig.repo.setRecord("promises", "42", promise(7))

        assertEquals(SyncedDocumentStatus.Idle, rig.repo.sync())

        assertTrue(rig.tasks.requests.isEmpty(), rig.tasks.requests.toString())
        assertNotNull(rig.repo.current().value("promises", 42))
        // Not counted at sign-out while Pro is off, as on the desktop.
        assertFalse(rig.repo.hasUnsyncedChanges.first())
        assertTrue(rig.store.load()!!.dirty)
        assertTrue(rig.repo.readArchive().isEmpty())
        assertTrue(rig.tasks.requests.isEmpty())
    }

    @Test
    fun `turning Pro off writes nothing`() = runTest {
        val rig = rig()
        rig.repo.setRecord("promises", "42", promise(7))
        rig.repo.sync()
        val before = rig.tasks.requests.size

        rig.prefs.setProEnabled(false)
        rig.repo.setRecord("promises", "43", promise(8))
        rig.repo.sync()

        assertEquals(before, rig.tasks.requests.size)
        assertNull(rig.carrier()?.value("promises", 43))
    }

    @Test
    fun `an empty ledger creates no carrier`() = runTest {
        val rig = rig()

        assertEquals(SyncedDocumentStatus.Idle, rig.repo.sync())

        assertTrue(rig.creates().isEmpty())
    }

    @Test
    fun `a promise is written to a new carrier in the Inbox`() = runTest {
        val rig = rig()
        rig.repo.setRecord("promises", "42", promise(7))
        assertTrue(rig.repo.hasUnsyncedChanges.first())

        assertEquals(SyncedDocumentStatus.Idle, rig.repo.sync())

        assertEquals(listOf("/projects/5/tasks"), rig.creates().map { it.path })
        val carrier = assertNotNull(rig.carrier())
        assertEquals(JsonPrimitive(7), carrier.value("promises", 42)?.get("person"))
        assertEquals(ProLedgerEnvelope.CARRIER_TITLE, rig.tasks.tasks.values.first { ProLedgerEnvelope.hasMarker(it.description) }.title)
        assertFalse(rig.repo.hasUnsyncedChanges.first())
    }

    @Test
    fun `the carrier's records and this device's merge`() = runTest {
        val server = LedgerRules.setRecord(LedgerRules.empty(), "people", "7", JsonObject(mapOf("aliases" to JsonArray(listOf(JsonPrimitive("Jan de Vries"))))), "desk", 1_000)
        val tasks = TaskListServer().apply { put(800, done = true, description = ProLedgerEnvelope.encode(server)) }
        val rig = rig(tasks)
        rig.repo.setRecord("promises", "42", promise(7))

        rig.repo.sync()

        val carrier = assertNotNull(rig.carrier())
        assertNotNull(carrier.value("people", 7))
        assertNotNull(carrier.value("promises", 42))
        assertTrue(rig.creates().isEmpty())
        assertNotNull(rig.repo.current().value("people", 7))
    }

    @Test
    fun `a task created offline takes its real id in the ledger`() = runTest {
        val rig = rig(proOn = false)
        rig.repo.setRecord("promises", "-3", promise(7))
        rig.repo.setEstimate(-3, 45)

        rig.repo.remapTask(-3, 120)

        val ledger = rig.repo.current()
        assertNotNull(ledger.value("promises", 120))
        assertEquals(45, ledger.estimateOf(120))
        assertNull(ledger.estimateOf(-3))
        // The temporary key is a tombstone, so an older copy cannot bring it back.
        assertEquals(JsonNull, ledger.records("promises")["-3"]?.jsonObject?.get("value"))
        assertTrue(rig.tasks.requests.isEmpty())
    }

    @Test
    fun `records closed more than 180 days ago move to their quarter's archive part`() = runTest {
        val rig = rig()
        rig.repo.setRecord("promises", "42", promise(7, verdict("v1", "2026-04-01T10:00:00Z", "delivered")))
        rig.repo.setRecord("promises", "43", promise(7))

        rig.repo.sync()

        val part = rig.parts().single()
        assertEquals("2026-Q2", ProLedgerEnvelope.quarterOf(part))
        assertNotNull(part["promises"]?.jsonObject?.get("42"))
        val carrier = assertNotNull(rig.carrier())
        assertNull(carrier.value("promises", 42))
        assertTrue(carrier.records("promises").containsKey("42"))
        assertNotNull(carrier.value("promises", 43))
        assertNotNull(rig.repo.readArchive()["promises"]?.get("42"))
    }
}
