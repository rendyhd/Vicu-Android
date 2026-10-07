package com.rendyhd.vicu.ui.screens.project

import androidx.lifecycle.SavedStateHandle
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProjectSectionPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
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
import kotlin.test.assertTrue

/** Sections the user collapsed stay collapsed when the project screen is opened again. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelSectionsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val root = Project(id = 1, title = "Root")
    private val sectionA = Project(id = 10, title = "A", parentProjectId = 1, position = 1.0)
    private val sectionB = Project(id = 11, title = "B", parentProjectId = 1, position = 2.0)
    private val nested = Project(id = 100, title = "Nested", parentProjectId = 10, position = 1.0)

    /** The stores a process keeps: they outlive any one ViewModel. */
    private class Stores {
        val sectionPrefs = ProjectSectionPrefsStore(InMemoryPreferencesDataStore())
        val behaviorPrefs = BehaviorPrefsStore(InMemoryPreferencesDataStore())
        val tasks = FakeTaskRepository()
    }

    private fun Stores.viewModel(): ProjectViewModel {
        val projects = FakeProjectRepository(listOf(root, sectionA, sectionB, nested))
        val labels = FakeLabelRepository()
        return ProjectViewModel(
            savedStateHandle = SavedStateHandle(mapOf("projectId" to root.id)),
            taskRepository = tasks,
            projectRepository = projects,
            labelRepository = labels,
            refresher = fakeScreenRefresher(tasks, projects, labels),
            behaviorPrefsStore = behaviorPrefs,
            projectSectionPrefsStore = sectionPrefs,
        )
    }

    private fun ProjectViewModel.section(id: Long): ProjectSection =
        checkNotNull(findProjectSection(uiState.value.sections, id)) { "no section $id" }

    @Test
    fun `a section collapsed earlier is collapsed when a fresh ViewModel loads`() = runTest {
        val stores = Stores().apply {
            tasks.put(Task(id = 1, title = "In A", projectId = 10))
            tasks.put(Task(id = 2, title = "In B", projectId = 11))
        }
        val first = stores.viewModel()
        runCurrent()
        assertTrue(first.section(10).isExpanded, "sections start expanded")
        first.toggleSection(10)
        first.toggleSection(100)
        runCurrent()
        assertFalse(first.section(10).isExpanded)

        // The screen is left and opened again: nothing but the stored state survives.
        val reopened = stores.viewModel()
        runCurrent()

        assertFalse(reopened.section(10).isExpanded, "the collapsed section stays collapsed")
        assertFalse(reopened.section(100).isExpanded, "so does a nested one")
        assertTrue(reopened.section(11).isExpanded, "other sections are untouched")
    }

    @Test
    fun `a collapsed section stays collapsed while the lists change underneath it`() = runTest {
        val stores = Stores()
        stores.sectionPrefs.setExpanded(rootProjectId = 1, sectionProjectId = 10, isExpanded = false)
        val vm = stores.viewModel()
        runCurrent()
        assertFalse(vm.section(10).isExpanded)

        stores.tasks.put(Task(id = 1, title = "Arrives later", projectId = 10))
        runCurrent()

        assertFalse(vm.section(10).isExpanded)
        assertEquals(listOf(1L), vm.section(10).tasks.map { it.id })
    }

    @Test
    fun `expanding a section again is remembered too`() = runTest {
        val stores = Stores()
        stores.sectionPrefs.setExpanded(rootProjectId = 1, sectionProjectId = 10, isExpanded = false)
        val first = stores.viewModel()
        runCurrent()
        first.toggleSection(10)
        runCurrent()

        val reopened = stores.viewModel()
        runCurrent()

        assertTrue(reopened.section(10).isExpanded)
    }
}
