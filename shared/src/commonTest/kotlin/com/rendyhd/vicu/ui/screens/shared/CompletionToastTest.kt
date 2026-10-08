package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessage
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionToastTest {

    private class TestTime(var millis: Long = 1_000_000L) : TimeSource {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
        override fun zone(): TimeZone = TimeZone.UTC
    }

    private class Rig(
        val received: MutableList<AppMessage>,
        val time: TestTime,
        val tasks: FakeTaskRepository,
        val toast: CompletionToastCenter,
    )

    private fun TestScope.rig(): Rig {
        val messages = AppMessages()
        val received = mutableListOf<AppMessage>()
        backgroundScope.launch { messages.messages.collect { received += it } }
        val time = TestTime()
        val tasks = FakeTaskRepository()
        return Rig(received, time, tasks, CompletionToastCenter(messages, tasks, time, backgroundScope))
    }

    private fun task(id: Long) = Task(id = id, title = "Task $id", projectId = 1, done = true)

    @Test
    fun `a row whose hold ends posts Completed with Undo for six seconds`() = runTest {
        val rig = rig()
        val hold = CompletionHold(backgroundScope, toast = rig.toast)
        hold.merge(listOf(task(1)))
        hold.hold(task(1))

        advanceTimeBy(CompletionHold.HOLD_MILLIS - 1)
        runCurrent()
        assertTrue(rig.received.isEmpty(), "nothing is said while the row is held")

        advanceTimeBy(1)
        runCurrent()
        val message = rig.received.single()
        assertEquals("Completed", message.text)
        assertEquals("Undo", message.actionLabel)
        assertEquals(6_000L, message.durationMillis)
    }

    @Test
    fun `more rows within the window merge into one count`() = runTest {
        val rig = rig()

        rig.toast.collapsed(1)
        rig.time.millis += 2_000
        rig.toast.collapsed(2)
        rig.time.millis += 2_000
        rig.toast.collapsed(3)
        runCurrent()

        assertEquals(listOf("Completed", "2 completed", "3 completed"), rig.received.map { it.text })
    }

    @Test
    fun `a completion after the toast ran out starts a new Completed`() = runTest {
        val rig = rig()

        rig.toast.collapsed(1)
        rig.time.millis += CompletionHold.TOAST_MILLIS
        rig.toast.collapsed(2)
        runCurrent()

        assertEquals(listOf("Completed", "Completed"), rig.received.map { it.text })
    }

    @Test
    fun `Undo reopens every task the toast covers and nothing else`() = runTest {
        val rig = rig()
        listOf(1L, 2L, 3L).forEach { rig.tasks.put(task(it)) }

        rig.toast.collapsed(1)
        rig.toast.collapsed(2)
        runCurrent()
        rig.received.last().onAction?.invoke()
        runCurrent()

        assertEquals(setOf(1L, 2L), rig.tasks.setDoneCalls.map { it.first }.toSet())
        assertTrue(rig.tasks.setDoneCalls.none { it.second })
        assertTrue(rig.tasks.current(3)!!.done)
    }

    @Test
    fun `leaving the screen collapses the held rows into one toast`() = runTest {
        val rig = rig()
        val hold = CompletionHold(backgroundScope, toast = rig.toast)
        hold.merge(listOf(task(1), task(2)))
        hold.hold(task(1))
        hold.hold(task(2))

        hold.releaseAll()
        runCurrent()

        assertEquals(listOf("Completed", "2 completed"), rig.received.map { it.text })
    }

    @Test
    fun `an undone or failed row never reaches the toast`() = runTest {
        val rig = rig()
        val hold = CompletionHold(backgroundScope, toast = rig.toast)
        hold.merge(listOf(task(1), task(2), task(3)))
        hold.hold(task(1))
        hold.hold(task(2))
        hold.hold(task(3))
        hold.undoing(1)
        hold.release(2)

        advanceTimeBy(CompletionHold.HOLD_MILLIS + 1)
        runCurrent()

        assertEquals(listOf("Completed"), rig.received.map { it.text }, "only row 3 completed and stayed")
        assertNull(hold.state.value[3])
    }
}
