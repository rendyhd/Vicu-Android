package com.rendyhd.vicu.ui.components.selection

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.util.AppMessage
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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
        val labels: FakeLabelRepository,
        val navigation: NavigationTicker,
    )

    private fun TestScope.rig(vararg ids: Long): Rig {
        val tasks = FakeTaskRepository()
        ids.forEach { tasks.put(Task(id = it, title = "Task $it", projectId = 1)) }
        val messages = AppMessages()
        val received = mutableListOf<AppMessage>()
        backgroundScope.launch { messages.messages.collect { received += it } }
        val labels = FakeLabelRepository()
        val navigation = NavigationTicker()
        val vm = SelectionViewModel(
            taskRepository = tasks,
            projectRepository = FakeProjectRepository(),
            labelRepository = labels,
            appMessages = messages,
            appScope = backgroundScope,
            dayClock = DayClock(
                backgroundScope,
                FixedTimeSource(Instant.parse("2026-10-06T21:30:00Z"), TimeZone.of("Europe/Amsterdam")),
                ticking = false,
            ),
            navigationTicker = navigation,
        )
        return Rig(tasks, vm, received, labels, navigation)
    }

    private fun Rig.select(vararg ids: Long) = ids.forEach { vm.toggle(it) }

    @Test
    fun `going to another screen ends the selection`() = runTest {
        val rig = rig(1, 2)
        runCurrent()
        rig.select(1, 2)
        assertEquals(setOf(1L, 2L), rig.vm.selectedIds.value)

        rig.navigation.navigated()
        runCurrent()

        assertEquals(emptySet(), rig.vm.selectedIds.value)
        assertEquals(0, rig.vm.selectedDescendantCount.value)
    }

    @Test
    fun `a selection made after the last navigation stays`() = runTest {
        val rig = rig(1, 2)
        rig.navigation.navigated() // before the screen's selection existed
        runCurrent()
        rig.select(1)
        runCurrent()

        assertEquals(setOf(1L), rig.vm.selectedIds.value, "an old navigation does not clear a new selection")
    }

    @Test
    fun `each navigation clears whatever was selected since`() = runTest {
        val rig = rig(1, 2)
        runCurrent()
        rig.select(1)
        rig.navigation.navigated()
        runCurrent()
        rig.select(2)
        assertEquals(setOf(2L), rig.vm.selectedIds.value)

        rig.navigation.navigated()
        runCurrent()
        assertEquals(emptySet(), rig.vm.selectedIds.value)
    }

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
    fun `bulk Today sets local 23_59_59 of the clock's day`() = runTest {
        val rig = rig(1, 2)
        rig.select(1, 2)
        runCurrent()

        rig.vm.bulkToday()
        runCurrent()

        // 21:30Z on 6 October is 23:30 in Amsterdam (CEST): today there ends at 21:59:59Z.
        assertEquals("2026-10-06T21:59:59Z", rig.tasks.current(1)!!.dueDate)
        assertEquals("2026-10-06T21:59:59Z", rig.tasks.current(2)!!.dueDate)
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

    @Test
    fun `creating a label from the picker creates it and puts it on every selected task`() = runTest {
        val rig = rig(1, 2)
        rig.select(1, 2)
        runCurrent()

        rig.vm.createLabelAndApply("errand", "#20aaea")
        runCurrent()

        val created = rig.labels.created.single()
        assertEquals("errand", created.title)
        assertEquals("#20aaea", created.hexColor)
        assertEquals(setOf(1L to created.id, 2L to created.id), rig.labels.addedToTask.toSet())
        assertEquals(emptySet(), rig.vm.selectedIds.value)
        assertTrue(rig.received.isEmpty())
    }

    @Test
    fun `a label that cannot be created is reported and the selection stays`() = runTest {
        val rig = rig(1, 2)
        rig.labels.createError = "label already exists"
        rig.select(1, 2)
        runCurrent()

        rig.vm.createLabelAndApply("errand", "#20aaea")
        runCurrent()

        assertEquals(listOf("Could not create the label: label already exists"), rig.received.map { it.text })
        assertTrue(rig.labels.addedToTask.isEmpty())
        assertEquals(setOf(1L, 2L), rig.vm.selectedIds.value)
    }

    @Test
    fun `a failure to label one task leaves just that task selected`() = runTest {
        val rig = rig(1, 2)
        rig.labels.addToTaskError = { taskId -> if (taskId == 2L) "no access" else null }
        rig.select(1, 2)
        runCurrent()

        rig.vm.createLabelAndApply("errand", "#20aaea")
        runCurrent()

        assertEquals(listOf("Could not label 1 of 2 tasks: no access"), rig.received.map { it.text })
        assertEquals(setOf(2L), rig.vm.selectedIds.value)
    }
}
