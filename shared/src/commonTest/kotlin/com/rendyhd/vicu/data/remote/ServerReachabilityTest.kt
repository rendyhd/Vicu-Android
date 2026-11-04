package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.ui.components.shared.offlineBannerText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerReachabilityTest {

    @Test
    fun `an answer means the server is there, unless a proxy says it is gone`() {
        val reachability = ServerReachability()
        assertFalse(reachability.unreachable.value)

        reachability.noAnswer()
        assertTrue(reachability.unreachable.value)

        reachability.answered(404)
        assertFalse(reachability.unreachable.value, "a refusal is an answer")

        reachability.answered(503)
        assertTrue(reachability.unreachable.value)

        reachability.answered(500)
        assertFalse(reachability.unreachable.value, "a 500 comes from the server itself")
    }

    @Test
    fun `the client reports what each request got`() = runTest {
        val reachability = ServerReachability()
        var fail = true
        val engine = MockEngine {
            if (fail) throw IOException("connection refused")
            respond("{}", HttpStatusCode.OK)
        }
        val storage = InMemoryTokenStorage()
        val authManager = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("not used") },
            appScope = backgroundScope,
            networkMonitor = FakeNetworkMonitor(),
            deviceTokenTitle = { "Vicu — Test" },
        )
        val client = KtorClientFactory.create(
            engine,
            Json { ignoreUnknownKeys = true },
            BaseUrlHolder(storage),
            authManager,
            reachability = reachability,
        )

        assertFailsWith<IOException> { client.get("https://vikunja.test/api/v2/info") }
        assertTrue(reachability.unreachable.value)

        fail = false
        client.get("https://vikunja.test/api/v2/info")
        assertFalse(reachability.unreachable.value)
    }

    @Test
    fun `the banner says which of the two it is and what waits`() {
        assertNull(offlineBannerText(isOffline = false, serverUnreachable = false, pendingCount = 2))
        assertEquals("Offline", offlineBannerText(isOffline = true, serverUnreachable = false, pendingCount = 0))
        assertEquals("Offline — 2 changes pending", offlineBannerText(isOffline = true, serverUnreachable = true, pendingCount = 2))
        assertEquals("Can't reach the server", offlineBannerText(isOffline = false, serverUnreachable = true, pendingCount = 0))
        assertEquals("Can't reach the server — 1 change pending", offlineBannerText(isOffline = false, serverUnreachable = true, pendingCount = 1))
    }
}
