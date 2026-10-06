package com.rendyhd.vicu.auth

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthManagerBackupTokenTest {

    private val routesJson = """{"tasks":{"read_all":{"path":"/tasks","method":"GET"}}}"""

    private fun HttpRequestData.bodyText(): String = (body as TextContent).text

    @Test
    fun `backup token title carries the install id and the token id is stored`() = runTest {
        var createdTitle: String? = null
        val h = authHarness(backgroundScope, InMemoryTokenStorage(installId = "a1b2c3")) { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/routes" ->
                    respondJson(routesJson)
                request.method == HttpMethod.Post && request.url.encodedPath == "/tokens" -> {
                    createdTitle = Json.parseToJsonElement(request.bodyText())
                        .jsonObject["title"]?.jsonPrimitive?.content
                    respondJson("""{"id":7,"token":"tk_new"}""", HttpStatusCode.Created)
                }
                request.method == HttpMethod.Get && request.url.encodedPath == "/tokens" ->
                    respondJson("""{"items":[],"total":0,"page":1,"per_page":100,"total_pages":1}""")
                else -> error("Unexpected request ${request.describe()}")
            }
        }

        assertTrue(h.manager.createBackupApiToken())

        assertEquals("Vicu — Pixel 8 [a1b2c3]", createdTitle)
        assertEquals("tk_new", h.storage.getApiToken())
        assertEquals(7L, h.storage.getBackupApiTokenId())
    }

    @Test
    fun `sibling cleanup only deletes stale tokens from this install`() = runTest {
        val deleted = Recorder()
        val h = authHarness(backgroundScope, InMemoryTokenStorage(installId = "a1b2c3")) { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/routes" ->
                    respondJson(routesJson)
                request.method == HttpMethod.Post && request.url.encodedPath == "/tokens" ->
                    respondJson("""{"id":10,"token":"tk_new"}""", HttpStatusCode.Created)
                request.method == HttpMethod.Get && request.url.encodedPath == "/tokens" ->
                    // The only deletable token comes last, so by the time its DELETE is seen
                    // every wrongly matched earlier token would have been deleted as well.
                    respondJson(
                        """{"items":[
                            {"id":10,"title":"Vicu — Pixel 8 [a1b2c3]"},
                            {"id":12,"title":"Vicu — Pixel 8 [ffffff]"},
                            {"id":13,"title":"Vicu — Pixel 8"},
                            {"id":14,"title":"Personal script"},
                            {"id":11,"title":"Vicu — Pixel 8 [a1b2c3]"}
                        ],"total":5,"page":1,"per_page":100,"total_pages":1}""",
                    )
                request.method == HttpMethod.Delete -> {
                    deleted.add(request.url.encodedPath)
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                else -> error("Unexpected request ${request.describe()}")
            }
        }

        assertTrue(h.manager.createBackupApiToken())
        awaitCondition { deleted.items.isNotEmpty() }

        assertEquals(listOf("/tokens/11"), deleted.items)
    }

    // The logout tests run in real time: logout bounds the revocation with a timeout, and the
    // virtual clock of runTest would otherwise skip straight past it while the mock engine
    // answers on its own thread.

    @Test
    fun `logout revokes the stored backup token before ending the server session`() = runTest {
        withContext(Dispatchers.Default) {
            val requests = Recorder()
            val h = authHarness(backgroundScope) { request ->
                requests.add(request.describe())
                when {
                    request.method == HttpMethod.Delete -> respond(content = "", status = HttpStatusCode.NoContent)
                    request.method == HttpMethod.Post && request.url.encodedPath == "/logout" -> respondJson("{}")
                    else -> error("Unexpected request ${request.describe()}")
                }
            }
            h.storage.storeVikunjaUrl("https://vikunja.test")
            h.storage.storeBackupApiToken("tk_backup", 4_102_444_800L, tokenId = 42L)

            h.manager.logout()

            assertEquals(listOf("DELETE /tokens/42", "POST /logout"), requests.items)
            assertNull(h.storage.getApiToken())
            assertNull(h.storage.getBackupApiTokenId())
            assertEquals(AuthState.Unauthenticated, h.manager.authState.value)
        }
    }

    @Test
    fun `logout still completes when the token revocation fails`() = runTest {
        withContext(Dispatchers.Default) {
            val requests = Recorder()
            val h = authHarness(backgroundScope) { request ->
                requests.add(request.describe())
                when {
                    request.method == HttpMethod.Delete ->
                        respondJson(
                            """{"title":"Internal Server Error","status":500}""",
                            HttpStatusCode.InternalServerError,
                        )
                    else -> respondJson("{}")
                }
            }
            h.storage.storeBackupApiToken("tk_backup", 4_102_444_800L, tokenId = 42L)

            h.manager.logout()

            assertEquals(listOf("DELETE /tokens/42", "POST /logout"), requests.items)
            assertNull(h.storage.getApiToken())
            assertEquals(AuthState.Unauthenticated, h.manager.authState.value)
        }
    }

    @Test
    fun `logout still completes when the network is down`() = runTest {
        withContext(Dispatchers.Default) {
            val h = authHarness(backgroundScope) { throw IllegalStateException("offline") }
            h.storage.storeBackupApiToken("tk_backup", 4_102_444_800L, tokenId = 42L)

            h.manager.logout()

            assertNull(h.storage.getApiToken())
            assertEquals(AuthState.Unauthenticated, h.manager.authState.value)
        }
    }

    @Test
    fun `logout leaves a token the user entered manually alone`() = runTest {
        withContext(Dispatchers.Default) {
            val requests = Recorder()
            val h = authHarness(backgroundScope) { request ->
                requests.add(request.describe())
                respondJson("{}")
            }
            h.manager.onApiTokenLogin("user_supplied_token", "https://vikunja.test")

            h.manager.logout()

            assertFalse(
                requests.items.any { it.startsWith("DELETE") },
                "A manual API token must never be revoked: ${requests.items}",
            )
        }
    }
}
