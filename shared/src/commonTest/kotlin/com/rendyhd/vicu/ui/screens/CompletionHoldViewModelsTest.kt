package com.rendyhd.vicu.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.logbook.LogbookViewModel
import com.rendyhd.vicu.ui.screens.tag.TagViewModel
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Completing a task changes the stored task at once, which takes it out of every list of open
 * tasks. The list screens keep the row on screen, struck through, for a few seconds so a second
 * tap can undo it, and put it back to normal when the change fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionHoldViewModelsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val label = Label(id = 7, title = "errands")

    private fun openTask(id: Long) = Task(id = id, title = "Task $id", projectId = 1, labels = listOf(label))
    private fun doneTask(id: Long) = Task(id = id, title = "Task $id", projectId = 1, done = true)

    private fun freshSync() = SyncStaleness().apply { markSynced() }

    private fun tagViewModel(tasks: FakeTaskRepository) = TagViewModel(
        savedStateHandle = SavedStateHandle(mapOf("labelId" to label.id)),
        taskRepository = tasks,
        projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Project"))),
        labelRepository = FakeLabelRepository(listOf(label)),
        syncStaleness = freshSync(),
    )

    private fun logbookViewModel(tasks: FakeTaskRepository, sync: SyncStaleness = freshSync()) = LogbookViewModel(
        taskRepository = tasks,
        projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Project"))),
        labelRepository = FakeLabelRepository(),
        syncStaleness = sync,
    )

    private fun FakeTaskRepository.withOpenTasks(vararg ids: Long) = apply { ids.forEach { put(openTask(it)) } }

    private fun TagViewModel.shownIds() = uiState.value.tasks.map { it.id }

    @Test
    fun `a completed row stays on screen, struck through, after the stored task is done`() = runTest {
        val tasks = FakeTaskRepository().withOpenTasks(1, 2, 3)
        val vm = tagViewModel(tasks)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), vm.shownIds())

        vm.toggleDone(vm.uiState.value.tasks[1])
        runCurrent()

        assertTrue(tasks.current(2)!!.done, "the completion is stored")
        assertEquals(listOf(1L, 2L, 3L), vm.shownIds(), "the row is still in its place")
        assertEquals(setOf(2L), vm.uiState.value.completedTaskIds)
    }

    @Test
    fun `the row goes away a few seconds later`() = runTest {
        val tasks = FakeTaskRepository().withOpenTasks(1, 2, 3)
        val vm = tagViewModel(tasks)
        runCurrent()
        vm.toggleDone(vm.uiState.value.tasks[1])
        runCurrent()

        advanceTimeBy(4_900)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), vm.shownIds())

        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(1L, 3L), vm.shownIds())
        assertEquals(emptySet(), vm.uiState.value.completedTaskIds)
    }

    @Test
    fun `a failed completion puts the row back to normal and reports the error`() = runTest {
        val tasks = FakeTaskRepository().withOpenTasks(1, 2, 3)
        tasks.completionOutcome = { NetworkResult.Error("Server said no") }
        val vm = tagViewModel(tasks)
        runCurrent()

        vm.toggleDone(vm.uiState.value.tasks[1])
        runCurrent()

        assertEquals("Server said no", vm.uiState.value.error)
        assertEquals(emptySet(), vm.uiState.value.completedTaskIds)
        assertFalse(tasks.current(2)!!.done)
        assertEquals(listOf(1L, 2L, 3L), vm.shownIds())
    }

    @Test
    fun `tapping the struck row again reopens the task and leaves it in place`() = runTest {
        val tasks = FakeTaskRepository().withOpenTasks(1, 2, 3)
        val vm = tagViewModel(tasks)
        runCurrent()
        vm.toggleDone(vm.uiState.value.tasks[1])
        runCurrent()

        vm.undoComplete(vm.uiState.value.tasks[1])
        runCurrent()

        assertEquals(listOf(2L to false), tasks.setDoneCalls, "undo is an explicit reopen, not another toggle")
        assertFalse(tasks.current(2)!!.done)
        assertEquals(listOf(1L, 2L, 3L), vm.shownIds())
        assertEquals(emptySet(), vm.uiState.value.completedTaskIds)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), vm.shownIds(), "the reopened task is a normal row again")
    }

    @Test
    fun `leaving the screen lets the held rows go`() = runTest {
        val tasks = FakeTaskRepository().withOpenTasks(1, 2, 3)
        val vm = tagViewModel(tasks)
        runCurrent()
        vm.toggleDone(vm.uiState.value.tasks[0])
        runCurrent()

        vm.completions.releaseAll()
        runCurrent()

        assertEquals(listOf(2L, 3L), vm.shownIds())
        assertEquals(emptySet(), vm.uiState.value.completedTaskIds)
    }

    @Test
    fun `a refresh does not drop the row that is being held`() = runTest {
        val tasks = FakeTaskRepository().withOpenTasks(1, 2, 3)
        val vm = tagViewModel(tasks)
        runCurrent()
        vm.toggleDone(vm.uiState.value.tasks[1])
        runCurrent()

        vm.refresh()
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), vm.shownIds())
        assertEquals(setOf(2L), vm.uiState.value.completedTaskIds)
    }

    @Test
    fun `logbook keeps a reopened task in place and undo completes it again`() = runTest {
        val tasks = FakeTaskRepository().apply { put(doneTask(1)); put(doneTask(2)) }
        val vm = logbookViewModel(tasks)
        runCurrent()
        assertEquals(listOf(1L, 2L), vm.uiState.value.tasks.map { it.id })

        vm.toggleDone(vm.uiState.value.tasks[0])
        runCurrent()
        assertFalse(tasks.current(1)!!.done, "the task is open again")
        assertEquals(listOf(1L, 2L), vm.uiState.value.tasks.map { it.id }, "the row stays in the logbook for now")
        assertEquals(setOf(1L), vm.uiState.value.uncompletedTaskIds)

        vm.undoUncomplete(vm.uiState.value.tasks[0])
        runCurrent()

        assertEquals(listOf(1L to true), tasks.setDoneCalls, "undo completes the task, it does not toggle it")
        assertEquals(listOf(1L), tasks.toggled, "only the first tap was a toggle")
        assertTrue(tasks.current(1)!!.done)
        assertEquals(listOf(1L, 2L), vm.uiState.value.tasks.map { it.id })
        assertEquals(emptySet(), vm.uiState.value.uncompletedTaskIds)
    }

    @Test
    fun `logbook puts the row back when reopening fails`() = runTest {
        val tasks = FakeTaskRepository().apply { put(doneTask(1)) }
        tasks.completionOutcome = { NetworkResult.Error("Offline") }
        val vm = logbookViewModel(tasks)
        runCurrent()

        vm.toggleDone(vm.uiState.value.tasks[0])
        runCurrent()

        assertEquals("Offline", vm.uiState.value.error)
        assertEquals(emptySet(), vm.uiState.value.uncompletedTaskIds)
        assertTrue(tasks.current(1)!!.done)
    }

    @Test
    fun `logbook does not download every task when the data is fresh`() = runTest {
        val tasks = FakeTaskRepository().apply { put(doneTask(1)) }
        logbookViewModel(tasks, sync = freshSync())
        runCurrent()

        assertTrue(tasks.refreshes.isEmpty())
    }

    @Test
    fun `logbook refreshes once with the normal refresh when the data is stale`() = runTest {
        val tasks = FakeTaskRepository().apply { put(doneTask(1)) }
        logbookViewModel(tasks, sync = SyncStaleness())
        runCurrent()

        assertEquals(listOf(emptyMap<String, String>()), tasks.refreshes)
    }
}
