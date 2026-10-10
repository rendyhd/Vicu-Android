package com.rendyhd.vicu.ui.screens.project

import androidx.lifecycle.SavedStateHandle
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProjectSectionPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.ui.screens.shared.offlineAuthManager
import com.rendyhd.vicu.ui.screens.shared.testProjectActions
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The project screen's options menu: what it offers, and what its actions do through ProjectActions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelMenuTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    // --- what the menu offers ---

    private val work = Project(id = 1, title = "Work")
    private val reviewOn = ReviewPrefs(enabled = true, excludeInbox = true)

    @Test
    fun `an ordinary project offers everything`() {
        val menu = projectMenuState(work, listOf(work), inboxProjectId = 5, reviewPrefs = reviewOn, confirmBeforeDelete = true)
        assertEquals(
            listOf(
                ProjectMenuEntry.EDIT,
                ProjectMenuEntry.ADD_SUBPROJECT,
                ProjectMenuEntry.SET_INBOX,
                ProjectMenuEntry.MARK_REVIEWED,
                ProjectMenuEntry.ARCHIVE,
                ProjectMenuEntry.DELETE,
            ),
            menu.entries(),
        )
        assertEquals(listOf("Edit project", "Add subproject", "Set as Inbox", "Mark reviewed", "Archive", "Delete"), menu.entries().map { it.label })
        assertTrue(ProjectMenuEntry.DELETE.destructive)
    }

    @Test
    fun `the inbox is not offered as the inbox, archived or deleted`() {
        val inbox = Project(id = 5, title = "Inbox")
        val menu = projectMenuState(inbox, listOf(inbox), inboxProjectId = 5, reviewPrefs = reviewOn, confirmBeforeDelete = true)
        assertEquals(listOf(ProjectMenuEntry.EDIT, ProjectMenuEntry.ADD_SUBPROJECT), menu.entries())

        // With the Inbox tracked by review it can be marked reviewed.
        val tracked = projectMenuState(inbox, listOf(inbox), 5, ReviewPrefs(enabled = true, excludeInbox = false), true)
        assertEquals(
            listOf(ProjectMenuEntry.EDIT, ProjectMenuEntry.ADD_SUBPROJECT, ProjectMenuEntry.MARK_REVIEWED),
            tracked.entries(),
        )
    }

    @Test
    fun `mark reviewed needs review tracking on and the project not excluded`() {
        val off = projectMenuState(work, listOf(work), 5, ReviewPrefs(enabled = false), true)
        assertTrue(ProjectMenuEntry.MARK_REVIEWED !in off.entries())

        val excluded = work.copy(description = "---\n**Vicu review**: excluded")
        val left = projectMenuState(excluded, listOf(excluded), 5, reviewOn, true)
        assertTrue(ProjectMenuEntry.MARK_REVIEWED !in left.entries())
    }

    @Test
    fun `a missing or archived project has no menu`() {
        assertTrue(projectMenuState(null, emptyList(), 5, reviewOn, true).entries().isEmpty())
        val archived = work.copy(isArchived = true)
        assertTrue(projectMenuState(archived, listOf(archived), 5, reviewOn, true).entries().isEmpty())
    }

    @Test
    fun `the parent picker lists only active projects`() {
        val old = Project(id = 2, title = "Old", isArchived = true)
        val menu = projectMenuState(work, listOf(work, old), 5, reviewOn, false)
        assertEquals(listOf(work), menu.projects)
        assertEquals(false, menu.confirmBeforeDelete)
    }

    // --- the actions ---

    private class Rig(projects: List<Project>) {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(projects)
        val labels = FakeLabelRepository()
        val auth = offlineAuthManager()
        val messages = AppMessages()
        val shown = mutableListOf<String>()

        fun viewModel() = ProjectViewModel(
            savedStateHandle = SavedStateHandle(mapOf("projectId" to 1L)),
            taskRepository = tasks,
            projectRepository = projects,
            labelRepository = labels,
            refresher = fakeScreenRefresher(tasks, projects, labels),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            projectSectionPrefsStore = ProjectSectionPrefsStore(InMemoryPreferencesDataStore()),
            projectActions = testProjectActions(projects, auth = auth),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            appMessages = messages,
        )
    }

    private fun TestScope.rig(projects: List<Project> = listOf(work)): Rig {
        val rig = Rig(projects)
        backgroundScope.launch { rig.messages.messages.collect { rig.shown += it.text } }
        return rig
    }

    @Test
    fun `archiving leaves the screen and says so`() = runTest {
        val rig = rig()
        val vm = rig.viewModel()
        runCurrent()

        vm.archive()
        runCurrent()

        assertEquals(true, rig.projects.updates.single().isArchived)
        assertEquals(ProjectExit.LEFT, vm.exit.value)
        assertEquals(listOf("Project archived"), rig.shown)
    }

    @Test
    fun `a refused archive stays and says why`() = runTest {
        val rig = rig()
        rig.projects.updateResult = { NetworkResult.Error("Forbidden") }
        val vm = rig.viewModel()
        runCurrent()

        vm.archive()
        runCurrent()

        assertEquals(ProjectExit.NONE, vm.exit.value)
        assertEquals(listOf("Forbidden"), rig.shown)
    }

    @Test
    fun `deleting leaves the screen`() = runTest {
        val rig = rig()
        val vm = rig.viewModel()
        runCurrent()

        vm.delete()
        runCurrent()

        assertEquals(listOf(1L), rig.projects.deletes)
        assertEquals(ProjectExit.LEFT, vm.exit.value)
        assertEquals(listOf("Project deleted"), rig.shown)
    }

    @Test
    fun `add subproject creates a project below the chosen parent`() = runTest {
        val rig = rig()
        val vm = rig.viewModel()
        runCurrent()

        vm.addSubproject("Reports", "", parentProjectId = 1)
        runCurrent()

        assertEquals(1L, rig.projects.creates.single().parentProjectId)
        assertEquals(listOf("Project created"), rig.shown)
    }

    @Test
    fun `set as inbox makes this project the inbox`() = runTest {
        val rig = rig()
        val vm = rig.viewModel()
        runCurrent()

        vm.setAsInbox()
        runCurrent()

        assertEquals(1L, rig.auth.getInboxProjectId())
        assertEquals(listOf("Inbox project updated"), rig.shown)
    }

    @Test
    fun `mark reviewed offers undo, and undo puts the project back`() = runTest {
        val rig = rig()
        val vm = rig.viewModel()
        runCurrent()

        vm.markReviewed()
        runCurrent()

        assertEquals(work, vm.reviewUndo.value)
        assertEquals("---\n**Vicu review**: 2026-10-10", rig.projects.updates.single().description)

        vm.undoReview()
        runCurrent()

        assertNull(vm.reviewUndo.value)
        assertEquals(work, rig.projects.updates.last())
    }

    @Test
    fun `a review that was not recorded has nothing to undo`() = runTest {
        val rig = rig()
        rig.projects.updateResult = { NetworkResult.Error("Server error") }
        val vm = rig.viewModel()
        runCurrent()

        vm.markReviewed()
        runCurrent()

        assertNull(vm.reviewUndo.value)
        assertEquals(listOf("Server error"), rig.shown)
    }
}
