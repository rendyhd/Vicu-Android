package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.CarrierIdStore
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.MutableTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Carrier tasks are found by remembered id, with a marker search for new ones and a full scan of
 * the completed tasks only as a fallback (A-CL-1). The server below keeps a long completed
 * history so a scan is visible in the request log.
 */
class CarrierFinderTest {

    private val server = "https://vikunja.example"
    private val marker = "<!-- vicu-custom-lists:v1:e30 -->"

    private class Rig(val finder: CarrierFinder, val tasks: TaskListServer, val store: CarrierIdStore, val time: MutableTimeSource)

    private fun rig(
        history: Int = 30,
        store: CarrierIdStore = CarrierIdStore(InMemoryPreferencesDataStore()),
        time: MutableTimeSource = MutableTimeSource(Instant.parse("2026-10-06T12:00:00Z")),
    ): Rig {
        val tasks = TaskListServer()
        // Completed history that is not a carrier, and an open task.
        for (id in 1L..history) tasks.put(id, done = true)
        tasks.put(500, done = false, description = marker)
        val client = HttpClient(MockEngine { request -> tasks.handle(this, request) }) {
            install(ContentNegotiation) { json(authTestJson) }
        }
        return Rig(CarrierFinder(VikunjaApiService(client, authTestJson), store, time), tasks, store, time)
    }

    private fun Rig.carrier(id: Long, description: String = marker) {
        tasks.put(id, title = CustomListEnvelope.CARRIER_TITLE, done = true, description = description)
    }

    /** The requests that list tasks: scans and searches. */
    private fun Rig.listings() = tasks.lists()
    private fun Rig.scans() = listings().filter { it.query == null }
    private fun Rig.searches() = listings().filter { it.query != null }
    private fun Rig.directFetches() = tasks.requests.filter { Regex("^/tasks/\\d+$").matches(it.path) }

    @Test
    fun `the first load scans the completed tasks and remembers the carrier`() = runTest {
        val rig = rig()
        rig.carrier(900)

        val found = rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(listOf(900L), found.map { it.id })
        assertEquals(1, rig.scans().size)
        assertEquals("done = true", rig.scans().single().filter)
        assertEquals(listOf(900L), rig.store.get(server, "custom-lists").ids)
        assertEquals(rig.time.now.toEpochMilliseconds(), rig.store.get(server, "custom-lists").lastFullScanAtMs)
    }

    @Test
    fun `later loads fetch the remembered id and do not list anything`() = runTest {
        val rig = rig(history = 400)
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.tasks.requests.clear()

        rig.time.advance(10.seconds)
        val found = rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(listOf(900L), found.map { it.id })
        assertEquals(emptyList(), rig.listings(), "no scan and no search")
        assertEquals(listOf("/tasks/900"), rig.directFetches().map { it.path })
    }

    @Test
    fun `the remembered ids survive a new finder over the same store`() = runTest {
        val store = CarrierIdStore(InMemoryPreferencesDataStore())
        val first = rig(store = store)
        first.carrier(900)
        first.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        val second = rig(store = store, time = first.time)
        second.carrier(900)
        second.time.advance(5.minutes)
        second.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(emptyList(), second.scans(), "a restart does not scan again")
    }

    @Test
    fun `a carrier another device created is found by a search for the marker name`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.tasks.requests.clear()

        rig.carrier(901)
        rig.time.advance(DISCOVERY_MS)
        val found = rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(listOf(900L, 901L), found.map { it.id })
        val search = rig.searches().single()
        assertEquals("vicu-custom-lists", search.query)
        assertEquals("done = true", search.filter)
        assertEquals(emptyList(), rig.scans(), "the history is not paged through")
        assertEquals(listOf(900L, 901L), rig.store.get(server, "custom-lists").ids)
    }

    @Test
    fun `the search runs at most once a minute`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.time.advance(DISCOVERY_MS)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        assertEquals(1, rig.searches().size)

        rig.time.advance(20.seconds)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(1, rig.searches().size, "still inside the interval")
    }

    @Test
    fun `a remembered id that is gone triggers a full scan and is forgotten`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.tasks.remove(900)
        rig.carrier(950)
        rig.tasks.requests.clear()
        rig.time.advance(5.seconds)

        val found = rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(listOf(950L), found.map { it.id })
        assertEquals(1, rig.scans().size, "the 404 falls back to a scan")
        assertEquals(listOf(950L), rig.store.get(server, "custom-lists").ids)
    }

    @Test
    fun `a forbidden carrier also falls back to a scan`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.tasks.override = { req -> if (req.path == "/tasks/900") respond("", HttpStatusCode.Forbidden) else null }
        rig.tasks.requests.clear()

        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(1, rig.scans().size)
    }

    @Test
    fun `the completed tasks are listed in full again after a day`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.tasks.requests.clear()

        rig.time.advance(23.hours)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        assertEquals(emptyList(), rig.scans())

        rig.time.advance(2.hours)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        assertEquals(1, rig.scans().size, "the daily safety net for servers whose search ignores descriptions")
    }

    @Test
    fun `the daily scan finds a carrier the search could not`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        // A server that does not search descriptions: every q search comes back empty.
        rig.tasks.override = { req ->
            if (req.method == "GET" && req.path == "/tasks" && req.query != null) {
                respond("""{"items":[],"total":0,"page":1,"per_page":1000,"total_pages":1}""", HttpStatusCode.OK, authTestJsonHeaders)
            } else {
                null
            }
        }
        rig.carrier(901)

        rig.time.advance(DISCOVERY_MS)
        assertEquals(listOf(900L), rig.finder.find(CarrierSpec.CUSTOM_LISTS, server).map { it.id })

        rig.time.advance(25.hours)
        assertEquals(listOf(900L, 901L), rig.finder.find(CarrierSpec.CUSTOM_LISTS, server).map { it.id })
    }

    @Test
    fun `an unreachable server is an error and the remembered ids are kept`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.tasks.override = { respond("", HttpStatusCode.ServiceUnavailable) }
        rig.tasks.requests.clear()

        assertFailsWith<VikunjaApiException> { rig.finder.find(CarrierSpec.CUSTOM_LISTS, server) }

        assertEquals(listOf(900L), rig.store.get(server, "custom-lists").ids)
        assertEquals(emptyList(), rig.scans(), "a 503 is not a reason to scan")
    }

    @Test
    fun `a failed search falls back to the remembered carriers`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.tasks.override = { req ->
            if (req.method == "GET" && req.path == "/tasks" && req.query != null) respond("", HttpStatusCode.UnprocessableEntity) else null
        }
        rig.time.advance(DISCOVERY_MS)

        val found = rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(listOf(900L), found.map { it.id })
    }

    @Test
    fun `a failed first scan is an error, never an empty answer`() = runTest {
        val rig = rig()
        rig.tasks.override = { req ->
            if (req.method == "GET" && req.path == "/tasks") respond("", HttpStatusCode.InternalServerError) else null
        }

        assertFailsWith<VikunjaApiException> { rig.finder.find(CarrierSpec.CUSTOM_LISTS, server) }
    }

    @Test
    fun `a carrier that was reopened or rewritten is dropped`() = runTest {
        val rig = rig()
        rig.carrier(900)
        rig.carrier(901)
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        rig.tasks.tasks.getValue(900).done = false
        rig.tasks.tasks.getValue(901).apply { description = "plain"; title = "Plain" }
        rig.time.advance(5.seconds)

        assertEquals(emptyList(), rig.finder.find(CarrierSpec.CUSTOM_LISTS, server))
        assertEquals(emptyList(), rig.store.get(server, "custom-lists").ids)
    }

    @Test
    fun `a carrier this app created is fetched directly from then on`() = runTest {
        val rig = rig()
        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)
        rig.carrier(900)
        rig.finder.remember(CarrierSpec.CUSTOM_LISTS, server, 900)
        rig.tasks.requests.clear()

        rig.time.advance(5.seconds)
        val found = rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(listOf(900L), found.map { it.id })
        assertEquals(emptyList(), rig.listings())
    }

    @Test
    fun `a duplicate carrier is remembered and returned too`() = runTest {
        val rig = rig()
        rig.carrier(901)
        rig.carrier(900)

        val found = rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(listOf(900L, 901L), found.map { it.id })
    }

    @Test
    fun `ids of another server are never asked of this one`() = runTest {
        val store = CarrierIdStore(InMemoryPreferencesDataStore())
        store.set("https://other.example", "custom-lists", listOf(900L), 1L)
        val rig = rig(store = store)
        rig.carrier(900)

        rig.finder.find(CarrierSpec.CUSTOM_LISTS, server)

        assertEquals(1, rig.scans().size, "this server has nothing remembered, so it scans")
        assertTrue(rig.directFetches().isEmpty())
    }

    private companion object {
        /** Just past the discovery interval. */
        val DISCOVERY_MS = CarrierFinder.DISCOVERY_INTERVAL + 1.seconds
    }
}
