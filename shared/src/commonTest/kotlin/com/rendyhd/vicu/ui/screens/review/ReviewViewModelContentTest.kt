package com.rendyhd.vicu.ui.screens.review

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.DayClock
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What an expanded review row shows follows the tasks while it is open. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelContentTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val garden = Project(id = 7, title = "Garden")
    private val shed = Project(id = 8, title = "Shed", parentProjectId = 7)

    private class Rig(
        val projects: FakeProjectRepository,
        val tasks: FakeTaskRepository,
        val vm: ReviewViewModel,
        val authScope: CoroutineScope,
    )

    private fun TestScope.rig(): Rig {
        val projects = FakeProjectRepository(listOf(garden, shed))
        val tasks = FakeTaskRepository()
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
            taskRepository = tasks,
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            authManager = auth,
            dayClock = DayClock(backgroundScope, time),
        )
        runCurrent()
        return Rig(projects, tasks, vm, authScope)
    }

    private fun task(id: Long, projectId: Long, done: Boolean = false) =
        Task(id = id, title = "Task $id", projectId = projectId, done = done)

    private fun Rig.content() = vm.uiState.value.content[7L]

    @Test
    fun `an expanded row lists the open tasks of the project and of its subprojects`() = runTest {
        val rig = rig()
        rig.tasks.put(task(1, 7))
        rig.tasks.put(task(2, 7, done = true))
        rig.tasks.put(task(3, 8))

        rig.vm.toggleExpanded(7)
        runCurrent()

        val content = rig.content()!!
        assertFalse(content.isLoading)
        assertEquals(listOf(1L), content.tasks.map { it.id }, "a completed task is not listed")
        assertEquals(listOf(8L), content.subProjects.map { it.project.id })
        assertEquals(listOf(3L), content.subProjects.single().tasks.map { it.id })
        rig.authScope.cancel()
    }

    @Test
    fun `tasks that change while the row is open change in it`() = runTest {
        val rig = rig()
        rig.tasks.put(task(1, 7))
        rig.vm.toggleExpanded(7)
        runCurrent()

        rig.tasks.put(task(4, 7)) // added on another device and synced
        rig.tasks.put(task(1, 7, done = true)) // completed elsewhere
        rig.tasks.put(task(5, 8)) // in the subproject
        runCurrent()

        val content = rig.content()!!
        assertEquals(listOf(4L), content.tasks.map { it.id })
        assertEquals(listOf(5L), content.subProjects.single().tasks.map { it.id })
        rig.authScope.cancel()
    }

    @Test
    fun `a subproject added while the row is open appears`() = runTest {
        val rig = rig()
        rig.projects.projects.value = listOf(garden)
        rig.vm.toggleExpanded(7)
        runCurrent()
        assertTrue(rig.content()!!.subProjects.isEmpty())

        rig.projects.projects.value = listOf(garden, shed)
        rig.tasks.put(task(3, 8))
        runCurrent()

        assertEquals(listOf(8L), rig.content()!!.subProjects.map { it.project.id })
        assertEquals(listOf(3L), rig.content()!!.subProjects.single().tasks.map { it.id })
        rig.authScope.cancel()
    }

    @Test
    fun `a collapsed row stops following, and opening it again shows what is there now`() = runTest {
        val rig = rig()
        rig.tasks.put(task(1, 7))
        rig.vm.toggleExpanded(7)
        runCurrent()

        rig.vm.toggleExpanded(7) // collapse
        runCurrent()
        rig.tasks.put(task(2, 7))
        runCurrent()
        assertEquals(listOf(1L), rig.content()!!.tasks.map { it.id }, "nothing is collected for a closed row")

        rig.vm.toggleExpanded(7)
        runCurrent()
        assertEquals(listOf(1L, 2L), rig.content()!!.tasks.map { it.id }.sorted())
        rig.authScope.cancel()
    }

    @Test
    fun `a row nobody opened has no content`() = runTest {
        val rig = rig()
        rig.tasks.put(task(1, 7))
        runCurrent()
        assertNull(rig.content())
        rig.authScope.cancel()
    }
}
