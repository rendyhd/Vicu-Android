package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.pluginOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KtorClientFactoryLoggingTest {

    /** [enableLogging] null means "use the factory default". */
    private fun buildClient(enableLogging: Boolean?, scope: CoroutineScope): HttpClient {
        val storage = InMemoryTokenStorage()
        val json = Json { ignoreUnknownKeys = true }
        val authManager = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("not used") },
            appScope = scope,
            networkMonitor = FakeNetworkMonitor(),
            deviceTokenTitle = { "Vicu — Test" },
        )
        val engine = MockEngine { respond("") }
        return if (enableLogging == null) {
            KtorClientFactory.create(engine, json, BaseUrlHolder(storage), authManager)
        } else {
            KtorClientFactory.create(engine, json, BaseUrlHolder(storage), authManager, enableLogging)
        }
    }

    @Test
    fun `request logging is off by default so release builds stay quiet`() = runTest {
        assertNull(buildClient(null, backgroundScope).pluginOrNull(Logging))
    }

    @Test
    fun `request logging can be disabled explicitly`() = runTest {
        assertNull(buildClient(false, backgroundScope).pluginOrNull(Logging))
    }

    @Test
    fun `request logging can be enabled for debug builds`() = runTest {
        assertNotNull(buildClient(true, backgroundScope).pluginOrNull(Logging))
    }
}
