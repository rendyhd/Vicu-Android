package com.rendyhd.vicu.ui.components.selection

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.util.AppMessage
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionViewModelTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private class Rig(
        val tasks: FakeTaskRepository,
        val vm: SelectionViewModel,
        val received: MutableList<AppMessage>,
    )

    private fun TestScope.rig(vararg ids: Long): Rig {
        val tasks = FakeTaskRepository()
        ids.forEach { tasks.put(Task(id = it, title = "Task $it", projectId = 1)) }
        val messages = AppMessages()
        val received = mutableListOf<AppMessage>()
        backgroundScope.launch { messages.messages.collect { received += it } }
        val vm = SelectionViewModel(
            taskRepository = tasks,
            projectRepository = FakeProjectRepository(),
            labelRepository = FakeLabelRepository(),
            appMessages = messages,
            appScope = backgroundScope,
        )
        return Rig(tasks, vm, received)
    }

    private fun Rig.select(vararg ids: Long) = ids.forEach { vm.toggle(it) }

    @Test
    fun `completing the selection completes every task and offers one undo`() = runTest {
        val rig = rig(1, 2, 3)
        rig.select(1, 2, 3)
        runCurrent()

        rig.vm.bulkComplete()
        runCurrent()

        assertEquals(setOf(1L, 2L, 3L), rig.tasks.toggled.toSet())
        assertTrue(listOf(1L, 2L, 3L).all { rig.tasks.current(it)!!.done })
        assertEquals(emptySet(), rig.vm.selectedIds.value)
        assertEquals(1, rig.received.size)
        assertEquals("3 tasks completed", rig.received.single().text)
        assertEquals("Undo", rig.received.single().actionLabel)
    }

    @Test
    fun `the single undo reopens exactly the tasks that were completed`() = runTest {
        val rig = rig(1, 2, 3)
        rig.select(1, 2, 3)
        runCurrent()
        rig.vm.bulkComplete()
        runCurrent()

        rig.received.single().onAction?.invoke()
        runCurrent()

        assertEquals(setOf(1L, 2L, 3L), rig.tasks.setDoneCalls.map { it.first }.toSet())
        assertTrue(rig.tasks.setDoneCalls.all { !it.second })
        assertTrue(listOf(1L, 2L, 3L).none { rig.tasks.current(it)!!.done })
    }

    @Test
    fun `completed rows are held on the screen and let go again by the undo`() = runTest {
        val rig = rig(1, 2, 3)
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(1L, 2L, 3L).map { rig.tasks.current(it)!! })
        rig.select(1, 2)
        runCurrent()

        rig.vm.bulkComplete(hold)
        runCurrent()
        assertEquals(setOf(1L, 2L), hold.state.value.keys)

        rig.received.single().onAction?.invoke()
        runCurrent()
        assertTrue(hold.state.value.isEmpty())
    }

    @Test
    fun `a task that fails is reported, stays selected and is not counted as completed`() = runTest {
        val rig = rig(1, 2, 3)
        rig.tasks.completionOutcome = { id -> if (id == 2L) NetworkResult.Error("boom") else null }
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(1L, 2L, 3L).map { rig.tasks.current(it)!! })
        rig.select(1, 2, 3)
        runCurrent()

        rig.vm.bulkComplete(hold)
        runCurrent()

        assertEquals(
            listOf("Could not complete 1 of 3 tasks: boom", "2 tasks completed"),
            rig.received.map { it.text },
        )
        assertEquals(setOf(2L), rig.vm.selectedIds.value, "only the failed task stays selected")
        assertEquals(setOf(1L, 3L), hold.state.value.keys, "the failed row is not held")
        assertFalse(rig.tasks.current(2)!!.done)
    }

    @Test
    fun `other bulk actions run a few at a time and report what failed`() = runTest {
        val ids = (1L..8L).toList().toLongArray()
        val rig = rig(*ids)
        var active = 0
        var peak = 0
        rig.tasks.updateHandler = { task ->
            active++
            peak = maxOf(peak, active)
            delay(100)
            active--
            if (task.id == 3L) NetworkResult.Error("nope") else NetworkResult.Success(task)
        }
        rig.select(*ids)
        runCurrent()

        rig.vm.bulkSetPriority(4)
        // The work runs in a background scope, which advanceUntilIdle ignores.
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(4, peak, "requests overlap, but only a few at a time")
        assertEquals(8, rig.tasks.updates.size)
        assertEquals(
            listOf("Could not change the priority of 1 of 8 tasks: nope"),
            rig.received.map { it.text },
        )
        assertEquals(setOf(3L), rig.vm.selectedIds.value)
        assertEquals(4, rig.tasks.current(1)!!.priority)
        assertEquals(0, rig.tasks.current(3)!!.priority, "the failed task is as it was")
    }

    @Test
    fun `a bulk action with no failures clears the selection quietly`() = runTest {
        val rig = rig(1, 2)
        rig.select(1, 2)
        runCurrent()

        rig.vm.bulkSchedule("2026-10-07T23:59:59Z")
        runCurrent()

        assertEquals(emptySet(), rig.vm.selectedIds.value)
        assertTrue(rig.received.isEmpty())
        assertEquals("2026-10-07T23:59:59Z", rig.tasks.current(1)!!.dueDate)
    }

    @Test
    fun `removing the selection deletes every task and clears the selection`() = runTest {
        val rig = rig(1, 2)
        rig.select(1, 2)
        runCurrent()

        rig.vm.bulkRemove()
        runCurrent()

        assertEquals(setOf(1L, 2L), rig.tasks.deleted.toSet())
        assertEquals(emptySet(), rig.vm.selectedIds.value)
    }
}
