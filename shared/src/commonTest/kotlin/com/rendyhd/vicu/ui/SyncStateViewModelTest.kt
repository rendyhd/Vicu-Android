package com.rendyhd.vicu.ui

import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.remote.ServerReachability
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import com.rendyhd.vicu.util.NetworkMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SyncStateViewModelTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private class RecordingSettingsHooks : PlatformSettingsHooks {
        var immediateSyncs = 0
        override val supportsQuickAddTile = false
        override fun updateWidgets() = Unit
        override fun scheduleSync(enabled: Boolean) = Unit
        override fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int) = Unit
        override fun sendTestNotification(): String? = null
        override fun requestQuickAddTile(onResult: (String) -> Unit) = Unit
        override fun triggerImmediateSync() {
            immediateSyncs++
        }
    }

    private fun failedAction(id: Long) = PendingActionEntity(
        entityType = "task",
        entityId = id,
        actionType = "update",
        payload = "{}",
        status = "failed",
    )

    private class SwitchableNetwork : NetworkMonitor {
        override val isOnline = MutableStateFlow(true)
    }

    /** An api whose every request answers with [status], through a client that reports to [reachability]. */
    private fun viewModel(
        dao: FakePendingActionDao,
        hooks: RecordingSettingsHooks,
        cursor: SyncCursorStore = SyncCursorStore(InMemoryPreferencesDataStore()),
        network: NetworkMonitor = FakeNetworkMonitor(),
        reachability: ServerReachability = ServerReachability(),
        status: () -> HttpStatusCode = { HttpStatusCode.OK },
    ): SyncStateViewModel {
        val client = HttpClient(MockEngine) {
            engine {
                // The test's scheduler, so that virtual time covers a request.
                dispatcher = Dispatchers.Main
                addHandler { respond("{}", status()) }
            }
            install(ContentNegotiation) { json(authTestJson) }
            defaultRequest { url("https://vikunja.test/api/v2/") }
        }
        client.plugin(HttpSend).intercept { request ->
            execute(request).also { reachability.answered(it.response.status.value) }
        }
        return SyncStateViewModel(network, dao, hooks, cursor, reachability, VikunjaApiService(client, authTestJson))
    }

    @Test
    fun `the server is unreachable only while the device has a network`() = runTest {
        val network = SwitchableNetwork()
        val reachability = ServerReachability()
        val vm = viewModel(FakePendingActionDao(), RecordingSettingsHooks(), network = network, reachability = reachability, status = { HttpStatusCode.ServiceUnavailable })
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { vm.serverUnreachable.collect { seen += it } }
        runCurrent()
        assertEquals(false, seen.last())

        reachability.noAnswer()
        runCurrent()
        assertEquals(true, seen.last())

        network.isOnline.value = false
        runCurrent()
        assertEquals(false, seen.last(), "with no network the banner says Offline instead")
    }

    @Test
    fun `while the server is down it is asked again and its return sends the queue`() = runTest {
        val reachability = ServerReachability()
        val hooks = RecordingSettingsHooks()
        var status = HttpStatusCode.ServiceUnavailable
        val vm = viewModel(FakePendingActionDao(), hooks, reachability = reachability, status = { status })
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { vm.serverUnreachable.collect { seen += it } }
        runCurrent()
        reachability.noAnswer()
        runCurrent()
        assertEquals(true, seen.last())

        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(true, seen.last(), "still down")
        assertEquals(0, hooks.immediateSyncs)

        status = HttpStatusCode.OK
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(false, seen.last())
        assertEquals(1, hooks.immediateSyncs)
    }

    @Test
    fun `the failed count follows the queue and retry puts the actions back to pending`() = runTest {
        val dao = FakePendingActionDao()
        val hooks = RecordingSettingsHooks()
        val vm = viewModel(dao, hooks)
        val failed = mutableListOf<Int>()
        val pending = mutableListOf<Int>()
        backgroundScope.launch { vm.failedCount.collect { failed += it } }
        backgroundScope.launch { vm.pendingCount.collect { pending += it } }
        runCurrent()
        assertEquals(0, failed.last())

        dao.insert(failedAction(11))
        dao.insert(failedAction(12))
        runCurrent()
        assertEquals(2, failed.last())

        vm.retryFailed()
        runCurrent()

        assertEquals(0, failed.last())
        assertEquals(2, pending.last())
        assertEquals(1, hooks.immediateSyncs, "a sync starts right away")
    }

    @Test
    fun `discarding removes the failed actions and syncs so the tasks come back from the server`() = runTest {
        val dao = FakePendingActionDao()
        val hooks = RecordingSettingsHooks()
        val cursor = SyncCursorStore(InMemoryPreferencesDataStore())
        val vm = viewModel(dao, hooks, cursor)
        val failed = mutableListOf<Int>()
        backgroundScope.launch { vm.failedCount.collect { failed += it } }
        dao.insert(failedAction(11))
        runCurrent()
        assertEquals(1, failed.last())

        vm.discardFailed()
        runCurrent()

        assertEquals(0, failed.last())
        assertEquals(emptyList(), dao.snapshot())
        assertEquals(1, hooks.immediateSyncs)
        assertTrue(cursor.begin().cursor.fullReconcileRequested, "only a full reconcile restores the rows the changes protected")
    }
}
