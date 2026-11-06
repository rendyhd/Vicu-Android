package com.rendyhd.vicu.ui.screens.guided

import com.rendyhd.vicu.ai.ask.Proposal
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.ask.TaskChange
import com.rendyhd.vicu.ai.dictation.DraftWriter
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * A due date set by a guided session or an Ask card moves or removes a block on its day (contract
 * 16.3), so its Undo writes the block and the reminders back along with the date.
 */
class UndoPutsTheBlockBackTest {

    private val zone = TimeZone.of("Europe/Amsterdam")
    private val before = Task(
        id = 1,
        title = "Write",
        projectId = 1,
        dueDate = "2026-10-06T21:59:59Z",
        startDate = "2026-10-06T07:00:00Z",
        endDate = "2026-10-06T07:30:00Z",
        reminders = listOf(TaskReminder(relativePeriod = -600, relativeTo = "start_date")),
    )

    /** What the repository leaves when the due date is cleared. */
    private val afterTheRule = before.copy(dueDate = "", startDate = "", endDate = "", reminders = emptyList())

    private val tasks = FakeTaskRepository().apply { put(before) }
    private val labels = FakeLabelRepository()
    private val proposals = Proposals(tasks, labels, DraftWriter(tasks, labels, NotificationPrefsStore(InMemoryPreferencesDataStore()), null))

    private fun assertRestored() {
        val now = tasks.current(1)
        assertEquals(before.dueDate, now?.dueDate)
        assertEquals(before.startDate, now?.startDate)
        assertEquals(before.endDate, now?.endDate)
        assertEquals(before.reminders, now?.reminders)
    }

    @Test
    fun undoAllOfAGuidedSession() = runTest {
        val change = TaskChange(before, clearDue = true)
        val log = GuidedLog().apply { changed(change, zone) }
        tasks.put(afterTheRule)
        val clock = DayClock(backgroundScope, FixedTimeSource(Instant.parse("2026-10-06T06:00:00Z"), zone), ticking = false)

        GuidedWriter(tasks, proposals, clock).undo(log)

        assertRestored()
    }

    @Test
    fun undoOfAnAskCard() = runTest {
        val change = TaskChange(before, due = LocalDateTime(2026, 10, 9, 23, 59, 59) to false)
        val applied = proposals.apply(Proposal.Update("call", listOf(change), emptyList()), zone)
        tasks.put(afterTheRule)

        applied.undo!!.invoke()

        assertRestored()
    }
}
