package com.rendyhd.vicu.ui.screens.settings

import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_SERVER
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.worker.awaitUntil
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Settings screen reads: every source the view model watches reaches [SettingsUiState]
 * in the right field, and a change to one does not disturb the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelStateTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private class Rig(
        val vm: SettingsViewModel,
        val harness: LoginHarness,
        val pending: FakePendingActionDao,
        val labels: FakeLabelRepository,
        val projects: FakeProjectRepository,
    )

    private class QuietHooks : PlatformSettingsHooks {
        var widgetUpdates = 0
        override val supportsQuickAddTile = true
        override fun updateWidgets() { widgetUpdates++ }
        override fun scheduleSync(enabled: Boolean) = Unit
        override fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int) = Unit
        override fun sendTestNotification(): String? = "sent"
        override fun requestQuickAddTile(onResult: (String) -> Unit) = Unit
        override fun triggerImmediateSync() = Unit
    }

    private suspend fun rig(): Rig {
        val harness = LoginHarness { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/user") ->
                    respond(
                        """{"id":7,"username":"rendy","email":"r@example.com"}""",
                        io.ktor.http.HttpStatusCode.OK,
                        com.rendyhd.vicu.auth.authTestJsonHeaders,
                    )
                else -> respond("", io.ktor.http.HttpStatusCode.NotFound)
            }
        }
        harness.signIn()
        val pending = FakePendingActionDao()
        val labels = FakeLabelRepository(listOf(Label(2, "beta"), Label(1, "Alpha")))
        val projects = FakeProjectRepository(
            listOf(Project(5, "Inbox"), Project(6, "Work"), Project(7, "Old", isArchived = true)),
        )
        val vm = SettingsViewModel(
            authManager = harness.authManager,
            tokenStorage = harness.storage,
            labelRepository = labels,
            projectRepository = projects,
            customListRepository = harness.fixture.customLists,
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            themePrefsStore = ThemePrefsStore(InMemoryPreferencesDataStore()),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            bottomBarPrefsStore = BottomBarPrefsStore(InMemoryPreferencesDataStore()),
            widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            logbookPrefsStore = LogbookPrefsStore(InMemoryPreferencesDataStore()),
            pendingActionDao = pending,
            networkMonitor = FakeNetworkMonitor(online = true),
            sessionCleanup = harness.sessionCleanup,
            apiService = harness.api,
            platformSettingsHooks = QuietHooks(),
            syncCursor = harness.fixture.syncCursor,
        )
        return Rig(vm, harness, pending, labels, projects)
    }

    @Test
    fun `every source reaches its own field`() = runTest {
        val r = rig()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.uiState.collect { } }
        awaitUntil { r.vm.uiState.value.username == "rendy" }

        val s = r.vm.uiState.value
        assertEquals("r@example.com", s.email)
        assertEquals("api_token", s.authMethod)
        assertEquals(ORIGINAL_SERVER, s.vikunjaUrl)
        assertEquals(5L, s.inboxProjectId)
        assertEquals(listOf("Alpha", "beta"), s.labels.map { it.title }, "labels sort without regard to case")
        assertEquals(listOf("Inbox", "Work"), s.projects.map { it.title })
        assertEquals(listOf("Old"), s.archivedProjects.map { it.title })
        assertTrue(s.isOnline)
        assertTrue(s.supportsQuickAddTile)
        assertEquals(ThemeMode.System, s.themeMode)
        assertNull(s.error)
        assertNull(s.successMessage)
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `changing one preference updates its field and leaves the others alone`() = runTest {
        val r = rig()
        val collector: Job = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.uiState.collect { } }
        awaitUntil { r.vm.uiState.value.username == "rendy" }
        val before = r.vm.uiState.value

        r.vm.setThemeMode(ThemeMode.Dark)
        r.vm.setReviewEnabled(true)
        r.vm.setLogbookRetentionEnabled(true)
        r.vm.setWidgetSmartAdd(false)
        r.vm.setNlpEnabled(!before.nlpConfig.enabled)
        awaitUntil {
            val s = r.vm.uiState.value
            s.themeMode == ThemeMode.Dark && s.reviewPrefs.enabled && s.logbookPrefs.enabled &&
                !s.widgetSmartAdd && s.nlpConfig.enabled != before.nlpConfig.enabled
        }

        val after = r.vm.uiState.value
        assertEquals(before.labels, after.labels)
        assertEquals(before.projects, after.projects)
        assertEquals(before.username, after.username)
        assertEquals(before.behaviorPrefs, after.behaviorPrefs)
        assertEquals(before.notificationPrefs, after.notificationPrefs)
        assertEquals(before.bottomBarSlots, after.bottomBarSlots)
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `queued and failed changes are counted separately`() = runTest {
        val r = rig()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.uiState.collect { } }
        awaitUntil { r.vm.uiState.value.username == "rendy" }

        r.pending.insert(com.rendyhd.vicu.data.local.queuedAction(entityId = 1))
        r.pending.insert(com.rendyhd.vicu.data.local.queuedAction(entityId = 2))
        r.pending.insert(com.rendyhd.vicu.data.local.queuedAction(entityId = 3, status = "failed"))
        awaitUntil { r.vm.uiState.value.pendingActionCount == 2 && r.vm.uiState.value.failedActionCount == 1 }

        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `messages show up and clear`() = runTest {
        val r = rig()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.uiState.collect { } }
        awaitUntil { r.vm.uiState.value.username == "rendy" }

        r.vm.setInboxProject(6)
        awaitUntil { r.vm.uiState.value.successMessage == "Inbox project updated" }
        assertEquals(6L, r.vm.uiState.value.inboxProjectId)
        assertNull(r.vm.uiState.value.error)

        r.vm.archiveProject(Project(6, "Work"))
        awaitUntil { r.vm.uiState.value.error != null || r.vm.uiState.value.successMessage != "Inbox project updated" }
        assertEquals(
            "Select another Inbox project before archiving this project",
            r.vm.uiState.value.error,
        )

        r.vm.clearMessages()
        awaitUntil { r.vm.uiState.value.error == null }
        assertFalse(r.vm.uiState.value.successMessage != null)
        collector.cancel()
        r.harness.close()
    }
}
