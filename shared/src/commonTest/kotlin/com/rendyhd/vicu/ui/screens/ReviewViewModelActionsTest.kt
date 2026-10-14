package com.rendyhd.vicu.ui.screens

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.review.ReviewViewModel
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review actions use the result of the project update: a refused change is rolled back in the
 * screen and reported instead of leaving a review that looks done and is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelActionsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val project = Project(id = 7, title = "Garden", description = "Plants")
    private val refused = NetworkResult.Error("You cannot change this project", code = 403)

    private class Rig(val projects: FakeProjectRepository, val vm: ReviewViewModel, val authScope: CoroutineScope)

    private fun TestScope.rig(): Rig {
        val projects = FakeProjectRepository(listOf(project))
        val authScope = CoroutineScope(SupervisorJob())
        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = authScope,
            networkMonitor = FakeNetworkMonitor(),
        )
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-07T10:00:00Z"), TimeZone.UTC)
        val vm = ReviewViewModel(
            projectRepository = projects,
            taskRepository = FakeTaskRepository(),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            authManager = auth,
            dayClock = DayClock(backgroundScope, time),
        )
        runCurrent()
        return Rig(projects, vm, authScope)
    }

    private fun Rig.state() = vm.uiState.value

    @Test
    fun `marking a project reviewed writes the review footer and offers undo`() = runTest {
        val rig = rig()

        rig.vm.markReviewed(project)
        runCurrent()

        val written = rig.projects.updates.single()
        assertEquals(ReviewState.REVIEWED, ReviewMetadata.parse(written.description).state)
        assertEquals(setOf(7L), rig.state().reviewedThisSession)
        assertEquals(project, rig.state().undo)
        assertNull(rig.state().error)
        rig.authScope.cancel()
    }

    @Test
    fun `a review the server refuses is rolled back and reported`() = runTest {
        val rig = rig()
        rig.projects.updateResult = { refused }

        rig.vm.markReviewed(project)
        runCurrent()

        assertTrue(rig.state().reviewedThisSession.isEmpty(), "it does not stay counted as reviewed")
        assertNull(rig.state().undo, "there is nothing to undo")
        assertEquals("You cannot change this project", rig.state().error)
        rig.authScope.cancel()
    }

    @Test
    fun `a refused cadence or exclusion is reported`() = runTest {
        val rig = rig()
        rig.projects.updateResult = { refused }

        rig.vm.setCadence(project, 30)
        runCurrent()
        assertEquals("You cannot change this project", rig.state().error)
        rig.vm.clearError()

        rig.vm.setExcluded(project, true)
        runCurrent()
        assertEquals("You cannot change this project", rig.state().error)
        rig.authScope.cancel()
    }

    @Test
    fun `undoing a review puts the previous project back`() = runTest {
        val rig = rig()
        rig.vm.markReviewed(project)
        runCurrent()

        rig.vm.undo()
        runCurrent()

        assertEquals(project, rig.projects.updates.last())
        assertTrue(rig.state().reviewedThisSession.isEmpty())
        assertNull(rig.state().undo)
        rig.authScope.cancel()
    }

    @Test
    fun `an undo the server refuses leaves the project reviewed and says so`() = runTest {
        val rig = rig()
        rig.vm.markReviewed(project)
        runCurrent()
        rig.projects.updateResult = { refused }

        rig.vm.undo()
        runCurrent()

        assertEquals(setOf(7L), rig.state().reviewedThisSession, "still reviewed: the undo did not happen")
        assertEquals("You cannot change this project", rig.state().error)
        rig.authScope.cancel()
    }

    @Test
    fun `an error waiting to be shown survives the project list changing`() = runTest {
        val rig = rig()
        rig.projects.updateResult = { refused }
        rig.vm.setCadence(project, 30)
        runCurrent()

        rig.projects.projects.value = listOf(project.copy(title = "Garden 2"))
        runCurrent()

        assertEquals("You cannot change this project", rig.state().error)
        rig.authScope.cancel()
    }

    @Test
    fun `a failed refresh when the screen opens is shown unless the device is offline`() = runTest {
        val rig = rig()
        rig.projects.refreshResult = NetworkResult.Error("Server error", code = 500)

        rig.vm.refresh(manual = false)
        runCurrent()
        assertEquals("Server error", rig.state().error)
        rig.vm.clearError()

        rig.projects.refreshResult = NetworkResult.Error("Can't reach the server", offline = true)
        rig.vm.refresh(manual = false)
        runCurrent()
        assertNull(rig.state().error)
        rig.authScope.cancel()
    }
}
