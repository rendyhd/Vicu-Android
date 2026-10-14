package com.rendyhd.vicu.auth

import com.rendyhd.vicu.worker.awaitUntil
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sessions stored before the user id was recorded get it backfilled once they are signed in, so
 * a later re-login can tell "same account" from "another account".
 */
class AuthManagerUserIdTest {

    private suspend fun InMemoryTokenStorage.signedInWithApiToken() {
        storeApiToken("tk_stored", Long.MAX_VALUE)
        storeVikunjaUrl("https://tasks.example.com")
    }

    @Test
    fun `a signed-in session without a user id gets it recorded`() = runTest {
        val storage = InMemoryTokenStorage().apply { signedInWithApiToken() }
        val h = authHarness(backgroundScope, storage) { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath == "/user") {
                respondJson("""{"id":8,"username":"rendy"}""")
            } else {
                error("Unexpected request ${request.describe()}")
            }
        }

        h.manager.ensureInitializedAndGetToken()

        awaitUntil { storage.getUserId() == 8L }
        assertEquals(AuthState.Authenticated, h.manager.authState.value)
    }

    @Test
    fun `a session that already has a user id is not asked again`() = runTest {
        val storage = InMemoryTokenStorage().apply {
            signedInWithApiToken()
            storeUserId(7)
        }
        val requests = Recorder()
        val h = authHarness(backgroundScope, storage) { request ->
            requests.add(request.describe())
            error("Unexpected request ${request.describe()}")
        }

        h.manager.ensureInitializedAndGetToken()
        // Give any background work the initialisation started time to run (real time).
        withContext(Dispatchers.Default) { delay(300) }

        assertTrue(requests.items.isEmpty(), "no request expected, saw ${requests.items}")
        assertEquals(7L, storage.getUserId())
    }

    @Test
    fun `a failed lookup is not fatal and leaves the id unknown`() = runTest {
        val storage = InMemoryTokenStorage().apply { signedInWithApiToken() }
        val requests = Recorder()
        val h = authHarness(backgroundScope, storage) { request ->
            requests.add(request.describe())
            respondJson("{}", HttpStatusCode.InternalServerError)
        }

        h.manager.ensureInitializedAndGetToken()
        awaitCondition { requests.items.isNotEmpty() }

        assertNull(storage.getUserId())
        assertEquals(AuthState.Authenticated, h.manager.authState.value)
    }
}
