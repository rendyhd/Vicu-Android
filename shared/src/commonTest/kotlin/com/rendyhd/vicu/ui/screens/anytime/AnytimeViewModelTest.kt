package com.rendyhd.vicu.ui.screens.anytime

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.fakeScreenRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Anytime screen: every project level, expansion by project id, and no Inbox chosen yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnytimeViewModelTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val inbox = Project(id = 5, title = "Inbox")
    private val work = Project(id = 7, title = "Work")
    private val deep = Project(id = 8, title = "Deep", parentProjectId = 7)
    private val deeper = Project(id = 9, title = "Deeper", parentProjectId = 8)

    private class Rig(projects: List<Project>) {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(projects)
        val labels = FakeLabelRepository()
        val storage = InMemoryTokenStorage()
        val scope = CoroutineScope(SupervisorJob())
        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("no network in this test") },
            appScope = scope,
            networkMonitor = FakeNetworkMonitor(),
        )

        fun viewModel() = AnytimeViewModel(
            tasks, this.projects, labels, auth,
            fakeScreenRefresher(tasks, this.projects, labels),
        )
    }

    private suspend fun rig(projects: List<Project>, inboxId: Long?): Rig =
        Rig(projects).also { rig -> inboxId?.let { rig.storage.storeInboxProjectId(it) } }

    private fun taskIn(id: Long, projectId: Long) = Task(id = id, title = "Task $id", projectId = projectId)

    @Test
    fun `a task three levels below a root is shown under it`() = runTest {
        val rig = rig(listOf(inbox, work, deep, deeper), inboxId = 5).apply {
            tasks.put(taskIn(1, 9))
        }
        val vm = rig.viewModel()
        runCurrent()

        val group = vm.uiState.value.projectGroups.single()
        assertEquals(7L, group.project.id)
        assertEquals(listOf(8L), group.sections.map { it.project.id })
        assertEquals(listOf(9L), group.sections.single().children.map { it.project.id })
        assertEquals(listOf(1L), group.sections.single().children.single().tasks.map { it.id })
        rig.scope.cancel()
    }

    @Test
    fun `collapsing a project hides it by id even when the list changes order underneath`() = runTest {
        val rig = rig(listOf(inbox, work, deep, deeper), inboxId = 5).apply {
            tasks.put(taskIn(1, 7))
            tasks.put(taskIn(2, 8))
        }
        val vm = rig.viewModel()
        runCurrent()

        vm.toggleProject(8L)
        // A project that sorts before everything else arrives: indexes shift, ids do not.
        rig.projects.projects.value = rig.projects.projects.value + Project(id = 20, title = "Aardvark")
        rig.tasks.put(taskIn(3, 20))
        runCurrent()

        val groups = vm.uiState.value.projectGroups
        assertEquals(listOf(20L, 7L), groups.map { it.project.id })
        assertTrue(groups[0].isExpanded, "the new group is untouched")
        assertTrue(groups[1].isExpanded, "the root is untouched")
        assertFalse(groups[1].sections.single().isExpanded, "the collapsed section stays collapsed")

        vm.toggleProject(8L)
        runCurrent()
        assertTrue(vm.uiState.value.projectGroups[1].sections.single().isExpanded)
        rig.scope.cancel()
    }

    @Test
    fun `the rows follow what is collapsed`() = runTest {
        val rig = rig(listOf(inbox, work, deep), inboxId = 5).apply {
            tasks.put(taskIn(1, 7))
            tasks.put(taskIn(2, 8))
        }
        val vm = rig.viewModel()
        runCurrent()
        // Work (two tasks with its sub-project) has a header; the sub-project's single task has none.
        assertEquals(3, vm.uiState.value.rows.size)

        vm.toggleProject(7L)
        runCurrent()

        assertEquals(1, vm.uiState.value.rows.size)
        rig.scope.cancel()
    }

    @Test
    fun `with no inbox chosen yet every open task is shown`() = runTest {
        val rig = rig(listOf(inbox, work), inboxId = null).apply {
            tasks.put(taskIn(1, 5))
            tasks.put(taskIn(2, 7))
        }
        val vm = rig.viewModel()
        runCurrent()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(setOf(5L, 7L), vm.uiState.value.projectGroups.map { it.project.id }.toSet())
        rig.scope.cancel()
    }
}
