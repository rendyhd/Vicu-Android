package com.rendyhd.vicu.ui.screens.guided

import com.rendyhd.vicu.ai.ask.Proposal
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.ask.TaskChange
import com.rendyhd.vicu.ai.dictation.DraftWriter
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Undo of an Ask card or of a guided session puts back only the fields the change wrote (review
 * C-9). A rename or a note made on another device during a long review stays.
 */
class UndoTouchesOnlyWhatItWroteTest {

    private val zone = TimeZone.of("Europe/Amsterdam")
    private val before = Task(
        id = 1,
        title = "Write",
        description = "Notes",
        projectId = 1,
        priority = 2,
        dueDate = "2026-10-06T21:59:59Z",
    )

    private val tasks = FakeTaskRepository().apply { put(before) }
    private val labels = FakeLabelRepository()
    private val proposals = Proposals(tasks, labels, DraftWriter(tasks, labels, NotificationPrefsStore(InMemoryPreferencesDataStore()), null))

    @Test
    fun anAskCardThatMovedTheDateLeavesALaterRename() = runTest {
        val change = TaskChange(before, due = LocalDateTime(2026, 10, 9, 23, 59, 59) to false)
        val applied = proposals.apply(Proposal.Update("call", listOf(change), emptyList()), zone)
        val moved = tasks.current(1)!!
        // Another device renames the task and changes its notes and priority meanwhile.
        tasks.put(moved.copy(title = "Write the report", description = "New notes", priority = 4))

        applied.undo!!.invoke()

        val now = tasks.current(1)!!
        assertEquals(before.dueDate, now.dueDate, "the date the card wrote goes back")
        assertEquals("Write the report", now.title)
        assertEquals("New notes", now.description)
        assertEquals(4, now.priority)
    }

    @Test
    fun anAskCardThatRenamedPutsOnlyTheTitleBack() = runTest {
        val change = TaskChange(before, title = "Write draft")
        val applied = proposals.apply(Proposal.Update("call", listOf(change), emptyList()), zone)
        tasks.put(tasks.current(1)!!.copy(dueDate = "2026-10-20T21:59:59Z"))

        applied.undo!!.invoke()

        val now = tasks.current(1)!!
        assertEquals("Write", now.title)
        assertEquals("2026-10-20T21:59:59Z", now.dueDate, "a date set elsewhere stays")
    }

    @Test
    fun aGuidedSessionThatMovedTheDateLeavesALaterRename() = runTest {
        val change = TaskChange(before, clearDue = true)
        val log = GuidedLog().apply { changed(change, zone) }
        tasks.put(before.copy(dueDate = "", title = "Renamed elsewhere"))
        val clock = DayClock(backgroundScope, FixedTimeSource(Instant.parse("2026-10-06T06:00:00Z"), zone), ticking = false)

        GuidedWriter(tasks, proposals, clock).undo(log)

        val now = tasks.current(1)!!
        assertEquals(before.dueDate, now.dueDate)
        assertEquals("Renamed elsewhere", now.title)
    }
}
