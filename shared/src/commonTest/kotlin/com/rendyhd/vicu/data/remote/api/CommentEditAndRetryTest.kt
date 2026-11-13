package com.rendyhd.vicu.data.remote.api

import com.rendyhd.vicu.util.isRetriableNetworkError
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review C-13: a comment edit that changes nothing, and HTTP 408. */
class CommentEditAndRetryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonHeaders = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }

    @Test
    fun `a comment edit answered 304 reads the comment back and succeeds`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when (request.method) {
                HttpMethod.Patch -> respond("", HttpStatusCode.NotModified)
                else -> respond("""{"id":3,"comment":"<p>Same</p>"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = VikunjaApiService(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)

        val comment = api.updateComment(42, 3, "<p>Same</p>")

        assertEquals(3, comment.id)
        assertEquals("<p>Same</p>", comment.comment)
        assertEquals(listOf("PATCH /tasks/42/comments/3", "GET /tasks/42/comments/3"), requests)
    }

    @Test
    fun `a request timeout is worth retrying a refusal is not`() {
        assertTrue(isRetriableNetworkError(VikunjaApiException(408)))
        assertTrue(isRetriableNetworkError(VikunjaApiException(429)))
        assertTrue(isRetriableNetworkError(VikunjaApiException(503)))
        assertFalse(isRetriableNetworkError(VikunjaApiException(400)))
        assertFalse(isRetriableNetworkError(VikunjaApiException(404)))
    }
}
