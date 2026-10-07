package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What the sign-in form shows when the server refuses a password login. */
class PasswordLoginHandlerErrorTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun refusing(status: HttpStatusCode, body: String): PasswordLoginHandler {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = Headers.build { append(HttpHeaders.ContentType, "application/problem+json") },
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return PasswordLoginHandler { VikunjaApiService(client, json) }
    }

    private suspend fun message(handler: PasswordLoginHandler): String =
        assertIs<PasswordLoginResult.Error>(handler.login("user", "password")).message

    @Test
    fun `wrong credentials show the server's own explanation`() = runTest {
        val handler = refusing(
            HttpStatusCode.Forbidden,
            """{"title":"Forbidden","status":403,"detail":"Wrong username or password.","code":1011}""",
        )

        assertEquals("Wrong username or password.", message(handler))
    }

    @Test
    fun `wrong credentials without an explanation get a readable message`() = runTest {
        val handler = refusing(
            HttpStatusCode.Forbidden,
            """{"title":"Forbidden","status":403,"detail":"","code":1011}""",
        )

        assertEquals("Invalid username or password", message(handler))
    }

    @Test
    fun `another refusal without an explanation names the HTTP status`() = runTest {
        val handler = refusing(
            HttpStatusCode.InternalServerError,
            """{"title":"Internal Server Error","status":500,"detail":"","code":0}""",
        )

        assertEquals("Login failed: HTTP 500", message(handler))
    }
}
