package com.rendyhd.vicu.ui.screens.project

import androidx.lifecycle.SavedStateHandle
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProjectSectionPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.ui.screens.shared.testProjectActions
import com.rendyhd.vicu.util.AppMessages

/**
 * What the Project screen keeps and what it drops when its lists emit again: an error that is
 * waiting to be shown stays, one that only described the project's old state goes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelStateTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private class Rig(projects: List<Project>) {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(projects)
        val labels = FakeLabelRepository()

        fun viewModel() = ProjectViewModel(
            savedStateHandle = SavedStateHandle(mapOf("projectId" to 1L)),
            taskRepository = tasks,
            projectRepository = projects,
            labelRepository = labels,
            refresher = fakeScreenRefresher(tasks, projects, labels),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            projectSectionPrefsStore = ProjectSectionPrefsStore(InMemoryPreferencesDataStore()),
            projectActions = testProjectActions(projects),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            appMessages = AppMessages(),
        )
    }

    @Test
    fun `moving a task one place stores the position a drag to that slot would`() = runTest {
        val rig = Rig(listOf(Project(id = 1, title = "Project")))
        rig.tasks.put(Task(id = 5, title = "A", projectId = 1, position = 100.0))
        rig.tasks.put(Task(id = 6, title = "B", projectId = 1, position = 200.0))
        rig.tasks.put(Task(id = 7, title = "C", projectId = 1, position = 300.0))
        val vm = rig.viewModel()
        runCurrent()
        assertEquals(listOf(5L, 6L, 7L), vm.uiState.value.unsectionedTasks.map { it.id })

        assertTrue(vm.moveTaskBy(7, offset = -1))
        runCurrent()

        assertEquals(listOf(5L, 7L, 6L), vm.uiState.value.unsectionedTasks.map { it.id })
        assertEquals(listOf(1L to listOf(PositionUpdate(7, 150.0))), rig.tasks.appliedPositions)
        assertFalse(vm.moveTaskBy(5, offset = -1), "the first task cannot move up")
        assertFalse(vm.moveTaskBy(99, offset = 1), "a task that is not on the screen")
    }

    @Test
    fun `the not found message goes away when the project arrives`() = runTest {
        val rig = Rig(emptyList())
        val vm = rig.viewModel()
        runCurrent()
        assertEquals("Project not found", vm.uiState.value.error)

        rig.projects.projects.value = listOf(Project(id = 1, title = "Arrived"))
        runCurrent()

        assertNull(vm.uiState.value.error)
        assertEquals("Arrived", vm.uiState.value.project?.title)
    }

    @Test
    fun `the archived message goes away when the project is restored`() = runTest {
        val rig = Rig(listOf(Project(id = 1, title = "Old", isArchived = true)))
        val vm = rig.viewModel()
        runCurrent()
        assertNotNull(vm.uiState.value.error)

        rig.projects.projects.value = listOf(Project(id = 1, title = "Old", isArchived = false))
        runCurrent()

        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `an error about a failed action survives the lists emitting`() = runTest {
        val rig = Rig(listOf(Project(id = 1, title = "Project")))
        rig.tasks.put(Task(id = 5, title = "Task", projectId = 1))
        rig.tasks.completionOutcome = { NetworkResult.Error("Could not complete") }
        val vm = rig.viewModel()
        runCurrent()

        vm.toggleDone(rig.tasks.current(5)!!)
        runCurrent()
        assertEquals("Could not complete", vm.uiState.value.error)

        rig.tasks.put(Task(id = 6, title = "Another", projectId = 1))
        runCurrent()

        assertEquals("Could not complete", vm.uiState.value.error)
    }

    @Test
    fun `the project and its tasks follow the stored data while the spinner state is kept`() = runTest {
        val rig = Rig(listOf(Project(id = 1, title = "Before")))
        val vm = rig.viewModel()
        runCurrent()

        rig.projects.projects.value = listOf(Project(id = 1, title = "After"))
        runCurrent()

        assertEquals("After", vm.uiState.value.project?.title)
    }
}
