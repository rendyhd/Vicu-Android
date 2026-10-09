package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.MutableTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The done count behind a drawer progress ring: one request for a page of one task, the envelope's
 * total, kept for ten minutes and for as long as the phone's own tally of the project is the same.
 */
class ProjectTaskCountsTest {

    private class Rig(val counts: ProjectTaskCounts, val requests: MutableList<String>, val time: MutableTimeSource) {
        /** The project ids asked for, in order. */
        fun asked(): List<Long> = requests.map { Regex("/projects/(\\d+)/tasks").find(it)!!.groupValues[1].toLong() }
    }

    private fun rig(
        scope: kotlinx.coroutines.CoroutineScope,
        totals: MutableMap<Long, Long> = mutableMapOf(7L to 5L),
        server: String? = "https://vikunja.example",
        respondWith: (Long) -> Pair<HttpStatusCode, String>? = { null },
    ): Rig {
        val requests = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath + "?" + request.url.encodedQuery
                val projectId = Regex("/projects/(\\d+)/tasks").find(request.url.encodedPath)!!.groupValues[1].toLong()
                val custom = respondWith(projectId)
                if (custom != null) {
                    respond(custom.second, custom.first, authTestJsonHeaders)
                } else {
                    val total = totals[projectId] ?: 0L
                    val item = if (total > 0) """{"id":1,"title":"A","done":true,"project_id":$projectId}""" else ""
                    respond(
                        """{"items":[$item],"total":$total,"page":1,"per_page":1,"total_pages":$total}""",
                        HttpStatusCode.OK,
                        authTestJsonHeaders,
                    )
                }
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        val time = MutableTimeSource(Instant.parse("2026-10-08T10:00:00Z"))
        return Rig(
            ProjectTaskCounts(VikunjaApiService(client, authTestJson), { server }, time, scope),
            requests,
            time,
        )
    }

    @Test
    fun `one request for a page of one done task, and the total is the answer`() = runTest {
        val rig = rig(backgroundScope)

        assertEquals(5L, rig.counts.doneTotal(7))

        assertEquals(1, rig.requests.size)
        val request = rig.requests.single()
        assertEquals(true, request.startsWith("/projects/7/tasks?"), request)
        assertEquals(true, "per_page=1" in request, request)
        assertEquals(true, "done" in request && "true" in request, request)
    }

    @Test
    fun `a fresh answer is reused for ten minutes, then the server is asked again`() = runTest {
        val rig = rig(backgroundScope)
        rig.counts.doneTotal(7)

        rig.time.advance(9.minutes)
        assertEquals(5L, rig.counts.doneTotal(7))
        assertEquals(1, rig.requests.size)

        rig.time.advance(2.minutes)
        rig.counts.doneTotal(7)
        assertEquals(2, rig.requests.size)
    }

    @Test
    fun `each project is its own request, once`() = runTest {
        val rig = rig(backgroundScope, totals = mutableMapOf(1L to 1L, 2L to 2L, 3L to 3L))

        val answers = listOf(1L, 2L, 3L, 1L, 2L, 3L).map { rig.counts.doneTotal(it) }

        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), answers)
        assertEquals(listOf(1L, 2L, 3L), rig.asked())
    }

    @Test
    fun `a change in what the phone holds of the project asks again`() = runTest {
        val rig = rig(backgroundScope)

        rig.counts.doneTotal(7, signature = "3 open")
        rig.counts.doneTotal(7, signature = "3 open")
        assertEquals(1, rig.requests.size)

        rig.counts.doneTotal(7, signature = "2 open")
        assertEquals(2, rig.requests.size)
    }

    @Test
    fun `every invalidate is counted so a screen that remembers its questions can ask again`() = runTest {
        val rig = rig(backgroundScope)
        assertEquals(0, rig.counts.invalidations.value)

        rig.counts.invalidate()
        rig.counts.invalidate()

        assertEquals(2, rig.counts.invalidations.value)
    }

    @Test
    fun `invalidate makes the next question ask again`() = runTest {
        val rig = rig(backgroundScope)
        rig.counts.doneTotal(7)

        rig.counts.invalidate()
        rig.counts.doneTotal(7)

        assertEquals(2, rig.requests.size)
    }

    @Test
    fun `questions that overlap share one request`() = runTest {
        val rig = rig(backgroundScope)

        val first = async { rig.counts.doneTotal(7, "a") }
        val second = async { rig.counts.doneTotal(7, "a") }

        assertEquals(listOf(5L, 5L), listOf(first.await(), second.await()))
        assertEquals(1, rig.requests.size)
    }

    @Test
    fun `a failure is no answer, is remembered for a minute and then asked again`() = runTest {
        var failing = true
        val rig = rig(backgroundScope) {
            if (failing) HttpStatusCode.ServiceUnavailable to """{"title":"down","status":503}""" else null
        }

        assertNull(rig.counts.doneTotal(7))
        failing = false
        // Inside the minute nothing is asked, so a screen that recomposes does not retry in a loop.
        assertNull(rig.counts.doneTotal(7))
        assertNull(rig.counts.doneTotal(7))
        assertEquals(1, rig.requests.size)

        rig.time.advance(61.seconds)
        assertEquals(5L, rig.counts.doneTotal(7))
        assertEquals(2, rig.requests.size)
    }

    @Test
    fun `invalidate also ends the memory of a failure`() = runTest {
        var failing = true
        val rig = rig(backgroundScope) {
            if (failing) HttpStatusCode.ServiceUnavailable to """{"title":"down","status":503}""" else null
        }

        assertNull(rig.counts.doneTotal(7))
        failing = false
        rig.counts.invalidate()
        assertEquals(5L, rig.counts.doneTotal(7))
    }

    @Test
    fun `a response without a usable total is no answer`() = runTest {
        // A server that leaves the total out: it reads as zero next to a task that is there.
        val rig = rig(backgroundScope) {
            HttpStatusCode.OK to """{"items":[{"id":1,"title":"A","done":true}],"page":1,"per_page":1}"""
        }

        assertNull(rig.counts.doneTotal(7))
    }

    @Test
    fun `a project with no done task is zero, not unknown`() = runTest {
        val rig = rig(backgroundScope, totals = mutableMapOf())

        assertEquals(0L, rig.counts.doneTotal(7))
    }

    @Test
    fun `signed out, nothing is asked`() = runTest {
        val rig = rig(backgroundScope, server = null)

        assertNull(rig.counts.doneTotal(7))
        assertEquals(0, rig.requests.size)
    }
}
