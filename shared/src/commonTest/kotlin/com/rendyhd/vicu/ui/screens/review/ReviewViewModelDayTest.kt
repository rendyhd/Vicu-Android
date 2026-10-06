package com.rendyhd.vicu.ui.screens.review

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.DayClock
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
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.time.Duration.Companion.minutes

/** Project review follows the clock's local day: it falls due at local midnight and records the local date. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelDayTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun reviewed(id: Long, on: String) = Project(
        id = id,
        title = "Project $id",
        description = ReviewMetadata.upsert("", ReviewMetadata(ReviewState.REVIEWED, on, null)),
    )

    private class Rig(val vm: ReviewViewModel, val projects: FakeProjectRepository, val authScope: CoroutineScope)

    private fun TestScope.rig(start: String, zone: TimeZone, vararg projects: Project): Rig {
        val authScope = CoroutineScope(SupervisorJob())
        val projectRepository = FakeProjectRepository(projects.toList())
        val vm = ReviewViewModel(
            projectRepository = projectRepository,
            taskRepository = FakeTaskRepository(),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            dayClock = DayClock(backgroundScope, SchedulerTimeSource(testScheduler, Instant.parse(start), zone)),
        )
        runCurrent()
        return Rig(vm, projectRepository, authScope)
    }

    @Test
    fun `a review that falls due at midnight appears without leaving the screen`() = runTest {
        // Reviewed 2026-09-22 with the default 14 days: due on 2026-10-06, overdue from 2026-10-07.
        val rig = rig("2026-10-06T21:30:00Z", TimeZone.of("Europe/Amsterdam"), reviewed(1, "2026-09-22"))
        // 23:30 local on the 6th: due today, not overdue.
        assertEquals(emptyList(), rig.vm.uiState.value.due.map { it.project.id })

        advanceTimeBy(31.minutes)
        runCurrent()

        assertEquals(listOf(1L), rig.vm.uiState.value.due.map { it.project.id })
        rig.authScope.cancel()
    }

    @Test
    fun `the status uses the local date, not the UTC date`() = runTest {
        // 20:00Z on the 6th is already 09:00 on the 7th in Auckland, so this review is overdue
        // there although the UTC date is still the 6th.
        val rig = rig("2026-10-06T20:00:00Z", TimeZone.of("Pacific/Auckland"), reviewed(1, "2026-09-22"))

        assertEquals(listOf(1L), rig.vm.uiState.value.due.map { it.project.id })
        rig.authScope.cancel()
    }

    @Test
    fun `marking a project reviewed records the local date`() = runTest {
        val project = reviewed(1, "2026-09-01")
        val rig = rig("2026-10-06T20:00:00Z", TimeZone.of("Pacific/Auckland"), project)

        rig.vm.markReviewed(project)
        runCurrent()

        val written = rig.projects.updates.single()
        assertEquals("2026-10-07", ReviewMetadata.parse(written.description).lastReviewedAt)
        rig.authScope.cancel()
    }
}
