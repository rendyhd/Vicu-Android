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
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.ui.screens.shared.testProjectActions
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
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.RecordingRepositoryHooks

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

    private suspend fun rig(
        projectList: List<Project> = listOf(Project(5, "Inbox"), Project(6, "Work"), Project(7, "Old", isArchived = true)),
    ): Rig {
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
        val projects = FakeProjectRepository(projectList)
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
            routinePrefsStore = RoutinePrefsStore(InMemoryPreferencesDataStore()),
            repositoryHooks = RecordingRepositoryHooks(),
            pendingActionDao = pending,
            networkMonitor = FakeNetworkMonitor(online = true),
            sessionCleanup = harness.sessionCleanup,
            apiService = harness.api,
            platformSettingsHooks = QuietHooks(),
            syncCursor = harness.fixture.syncCursor,
            projectActions = testProjectActions(projects, auth = harness.authManager),
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

    // --- the Projects tab ---

    /** What the real repository does: a saved project is what the screen reads next. */
    private fun FakeProjectRepository.storeWhatIsSaved() {
        updateResult = { saved ->
            projects.value = projects.value.map { if (it.id == saved.id) saved else it }
            NetworkResult.Success(saved)
        }
    }

    private val siblings = listOf(
        Project(5, "Inbox", position = 100.0),
        Project(6, "Work", position = 200.0),
        Project(8, "Home", position = 300.0),
        Project(10, "Reports", parentProjectId = 6, position = 100.0),
    )

    @Test
    fun `the projects tab lists the tree in the drawer's order with the inbox`() = runTest {
        val r = rig(siblings.reversed() + Project(7, "Old", isArchived = true))
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.projectRows.collect { } }
        awaitUntil { r.vm.projectRows.value.size == 4 }

        val rows = r.vm.projectRows.value
        assertEquals(listOf(5L, 6L, 10L, 8L), rows.map { it.project.id }, "siblings by position, children below their parent")
        assertEquals(listOf(0, 0, 1, 0), rows.map { it.depth })
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `a dropped project is saved by the shared sibling move and keeps its new place`() = runTest {
        val r = rig(siblings)
        r.projects.storeWhatIsSaved()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.projectRows.collect { } }
        awaitUntil { r.vm.projectRows.value.size == 4 }

        r.vm.reorderProject(movedId = 8, idsInNewOrder = listOf(5, 8, 6))
        awaitUntil { r.projects.updates.isNotEmpty() }

        // The plan of ProjectActions.moveAmongSiblings: halfway between the new neighbours.
        assertEquals(listOf(8L to 150.0), r.projects.updates.map { it.id to it.position })
        awaitUntil { r.vm.projectRows.value.map { it.project.id } == listOf(5L, 8L, 6L, 10L) }
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `a child moves only among its own siblings`() = runTest {
        val r = rig(siblings + Project(11, "Budget", parentProjectId = 6, position = 200.0))
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.projectRows.collect { } }
        awaitUntil { r.vm.projectRows.value.size == 5 }

        // A root in the list is not a sibling of the child, so it is left out of the plan.
        r.vm.reorderProject(movedId = 11, idsInNewOrder = listOf(11, 5, 10))
        awaitUntil { r.projects.updates.isNotEmpty() }

        assertEquals(listOf(11L to 50.0), r.projects.updates.map { it.id to it.position })
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `a refused drop says the order was not saved and the rows go back`() = runTest {
        val r = rig(siblings)
        r.projects.updateResult = { NetworkResult.Error("Server said no") }
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            launch { r.vm.projectRows.collect { } }
            r.vm.uiState.collect { }
        }
        awaitUntil { r.vm.projectRows.value.size == 4 }

        r.vm.reorderProject(movedId = 8, idsInNewOrder = listOf(8, 5, 6))
        awaitUntil { r.vm.uiState.value.error != null }

        assertEquals("Could not save the new order: Server said no", r.vm.uiState.value.error)
        awaitUntil { r.vm.projectRows.value.map { it.project.id } == listOf(5L, 6L, 10L, 8L) }
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `dropping a project where it was saves nothing`() = runTest {
        val r = rig(siblings)
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.projectRows.collect { } }
        awaitUntil { r.vm.projectRows.value.size == 4 }

        r.vm.reorderProject(movedId = 6, idsInNewOrder = listOf(5, 6, 8))

        assertTrue(r.projects.updates.isEmpty())
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `excluded projects are listed and include takes one back into review`() = runTest {
        val someday = Project(9, "Someday", description = "Later\n\n---\n**Vicu review**: excluded")
        val r = rig(listOf(Project(5, "Inbox"), Project(6, "Work"), someday, Project(12, "Gone", description = "---\n**Vicu review**: excluded", isArchived = true)))
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.uiState.collect { } }
        awaitUntil { r.vm.uiState.value.username == "rendy" }

        assertEquals(listOf("Someday"), r.vm.uiState.value.excludedFromReview.map { it.title }, "archived projects are not listed")
        assertEquals("Someday", excludedFromReviewSummary(r.vm.uiState.value.excludedFromReview))

        r.vm.includeInReview(someday)
        awaitUntil { r.vm.uiState.value.successMessage != null }

        assertEquals("Included \"Someday\" in review", r.vm.uiState.value.successMessage)
        assertEquals("Later\n\n---\n**Vicu review**: never", r.projects.updates.single().description)
        collector.cancel()
        r.harness.close()
    }

    @Test
    fun `set as inbox from a project row says the same as the inbox picker`() = runTest {
        val r = rig()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.uiState.collect { } }
        awaitUntil { r.vm.uiState.value.username == "rendy" }

        r.vm.setInboxProject(6)
        awaitUntil { r.vm.uiState.value.inboxProjectId == 6L && r.vm.uiState.value.successMessage != null }

        assertEquals("Inbox project updated", r.vm.uiState.value.successMessage)
        assertEquals(6L, r.harness.authManager.getInboxProjectId())
        collector.cancel()
        r.harness.close()
    }
}
