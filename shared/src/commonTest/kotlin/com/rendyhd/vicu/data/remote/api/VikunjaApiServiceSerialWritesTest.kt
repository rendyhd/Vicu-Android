package com.rendyhd.vicu.data.remote.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vikunja on SQLite answers parallel writes with "database is locked" (a 500), so every request
 * that changes data leaves one at a time, across tasks and across service instances.
 */
class VikunjaApiServiceSerialWritesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonHeaders = Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

    /** A server that fails (500) when a write arrives while another write is in flight. */
    private class LockyServer {
        private val bookkeeping = Mutex()
        var writing = 0
        var maxWriting = 0
        var locked = 0
        var writes = 0
        var maxReading = 0
        var reading = 0

        suspend fun enter(write: Boolean): Boolean = bookkeeping.withLock {
            if (write) {
                writing++
                writes++
                maxWriting = maxOf(maxWriting, writing)
                writing > 1
            } else {
                reading++
                maxReading = maxOf(maxReading, reading)
                false
            }
        }

        suspend fun leave(write: Boolean) = bookkeeping.withLock {
            if (write) writing-- else reading--
        }
    }

    private fun service(server: LockyServer): VikunjaApiService {
        val engine = MockEngine { request ->
            val write = request.method != HttpMethod.Get
            val clash = server.enter(write)
            delay(15)
            server.leave(write)
            when {
                clash -> respond("""{"code":500,"message":"database is locked"}""", HttpStatusCode.InternalServerError, jsonHeaders)
                request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                request.method == HttpMethod.Post -> respond("""{"id":50,"title":"New","project_id":1}""", HttpStatusCode.Created, jsonHeaders)
                request.method == HttpMethod.Patch -> respond("""{"id":9,"title":"T"}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"id":9,"title":"T"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return VikunjaApiService(client, json)
    }

    @Test
    fun `writes for different tasks and from different service instances never overlap`() = runTest {
        val server = LockyServer()
        val a = service(server)
        val b = service(server)

        // Real time: the mock server's delay runs on the engine's own dispatcher.
        withContext(Dispatchers.Default) {
            val calls = (1L..6L).map { id ->
                async {
                    when (id % 3) {
                        0L -> a.deleteTask(id)
                        1L -> b.updateTask(id, buildJsonObject { put("done", true) }).let { Unit }
                        else -> a.createTask(1, CreateTaskDto(title = "T$id")).let { Unit }
                    }
                }
            }
            calls.awaitAll()
        }

        assertEquals(6, server.writes)
        assertEquals(1, server.maxWriting, "no two writes were in flight together")
    }

    @Test
    fun `a failed write releases the turn for the next one`() = runTest {
        val server = LockyServer()
        val failing = MockEngine { respond("", HttpStatusCode.NotFound) }
        val broken = VikunjaApiService(HttpClient(failing) { install(ContentNegotiation) { json(json) } }, json)
        val ok = service(server)

        runCatching { broken.deleteTask(1) }
        withContext(Dispatchers.Default) { ok.deleteTask(2) }

        assertEquals(1, server.writes)
    }

    @Test
    fun `reads are not held back by writes`() = runTest {
        val server = LockyServer()
        val api = service(server)

        withContext(Dispatchers.Default) {
            (1L..4L).map { async { api.getTask(it) } }.awaitAll()
        }

        assertTrue(server.maxReading > 1, "reads still overlap")
        assertEquals(0, server.writes)
    }
}
