package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderAlarmsTest {

    private val now = Instant.parse("2026-10-06T08:00:00Z").toEpochMilliseconds()
    private val due = "2026-10-06T12:00:00Z"
    private val dueMillis = Instant.parse(due).toEpochMilliseconds()

    private val absolute = TaskReminder(reminder = "2026-10-06T10:30:00Z")
    private val absoluteMillis = Instant.parse("2026-10-06T10:30:00Z").toEpochMilliseconds()
    private val oneHourBeforeDue = TaskReminder(relativePeriod = -3_600, relativeTo = "due_date")

    private fun task(
        id: Long = 42,
        done: Boolean = false,
        reminders: List<TaskReminder> = listOf(absolute),
        dueDate: String = due,
        title: String = "Pay rent",
    ) = Task(id = id, title = title, done = done, reminders = reminders, dueDate = dueDate)

    // --- resolveTriggerMillis ---

    @Test
    fun `an absolute reminder resolves to its own timestamp`() {
        assertEquals(absoluteMillis, ReminderAlarms.resolveTriggerMillis(absolute, due))
    }

    @Test
    fun `a relative reminder is an offset from the due date`() {
        assertEquals(dueMillis - 3_600_000, ReminderAlarms.resolveTriggerMillis(oneHourBeforeDue, due))
        val atDueTime = TaskReminder(relativePeriod = 0, relativeTo = "due_date")
        assertEquals(dueMillis, ReminderAlarms.resolveTriggerMillis(atDueTime, due))
    }

    @Test
    fun `a relative reminder without a due date cannot be resolved`() {
        assertNull(ReminderAlarms.resolveTriggerMillis(oneHourBeforeDue, ""))
    }

    // --- relative_to (A-ALARM-3) ---

    private val start = "2026-10-06T09:00:00Z"
    private val startMillis = Instant.parse(start).toEpochMilliseconds()
    private val end = "2026-10-06T17:00:00Z"
    private val endMillis = Instant.parse(end).toEpochMilliseconds()

    @Test
    fun `a relative reminder counts from the field named in relative_to`() {
        val fromStart = TaskReminder(relativePeriod = -600, relativeTo = "start_date")
        val fromEnd = TaskReminder(relativePeriod = 1_800, relativeTo = "end_date")
        val fromDue = TaskReminder(relativePeriod = -600, relativeTo = "due_date")

        assertEquals(startMillis - 600_000, ReminderAlarms.resolveTriggerMillis(fromStart, due, start, end))
        assertEquals(endMillis + 1_800_000, ReminderAlarms.resolveTriggerMillis(fromEnd, due, start, end))
        assertEquals(dueMillis - 600_000, ReminderAlarms.resolveTriggerMillis(fromDue, due, start, end))
    }

    @Test
    fun `a relative reminder never falls back to another date than the one it names`() {
        val fromStart = TaskReminder(relativePeriod = -600, relativeTo = "start_date")
        val fromEnd = TaskReminder(relativePeriod = -600, relativeTo = "end_date")

        // The task has a due date but no start or end date: nothing to count from.
        assertNull(ReminderAlarms.resolveTriggerMillis(fromStart, due, "", ""))
        assertNull(ReminderAlarms.resolveTriggerMillis(fromEnd, due, "0001-01-01T00:00:00Z", "0001-01-01T00:00:00Z"))
    }

    @Test
    fun `a relative reminder without a relative_to still counts from the due date`() {
        val legacy = TaskReminder(relativePeriod = -3_600)

        assertEquals(dueMillis - 3_600_000, ReminderAlarms.resolveTriggerMillis(legacy, due, start, end))
    }

    @Test
    fun `an unknown relative_to cannot be resolved`() {
        val odd = TaskReminder(relativePeriod = -600, relativeTo = "created")

        assertNull(ReminderAlarms.resolveTriggerMillis(odd, due, start, end))
    }

    @Test
    fun `an absolute reminder time wins over the relative fields`() {
        val both = TaskReminder(reminder = "2026-10-06T10:30:00Z", relativePeriod = -600, relativeTo = "start_date")

        assertEquals(absoluteMillis, ReminderAlarms.resolveTriggerMillis(both, due, start, end))
    }

    @Test
    fun `desired alarms for a start-based reminder use the start date and not the due date`() {
        val t = task(reminders = listOf(TaskReminder(relativePeriod = -900, relativeTo = "start_date")))
            .copy(startDate = start)

        val alarms = ReminderAlarms.desiredAlarms(t, now)

        assertEquals(listOf(startMillis - 900_000), alarms.map { it.triggerAtMillis })
    }

    @Test
    fun `a start-based reminder on a task without a start date gets no alarm`() {
        val t = task(reminders = listOf(TaskReminder(relativePeriod = -900, relativeTo = "start_date")))

        assertTrue(ReminderAlarms.desiredAlarms(t, now).isEmpty())
    }

    @Test
    fun `a fired start-based reminder follows the start date it was scheduled for`() {
        val reminder = TaskReminder(relativePeriod = -900, relativeTo = "start_date")
        val scheduledFor = startMillis - 900_000
        val same = task(reminders = listOf(reminder)).copy(startDate = start)
        val moved = same.copy(startDate = "2026-10-08T09:00:00Z")

        assertEquals(ReminderFireDecision.Show("Pay rent"), ReminderAlarms.decideFire(same, scheduledFor, false))
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.REMINDER_REMOVED),
            ReminderAlarms.decideFire(moved, scheduledFor, false),
        )
    }

    // --- desiredAlarms / requestCode ---

    @Test
    fun `an open task gets one alarm per future reminder with a stable code`() {
        val alarms = ReminderAlarms.desiredAlarms(task(reminders = listOf(absolute, oneHourBeforeDue)), now)

        assertEquals(listOf(absoluteMillis, dueMillis - 3_600_000), alarms.map { it.triggerAtMillis })
        assertEquals(listOf(0, 1), alarms.map { it.reminderIndex })
        assertEquals(ReminderAlarms.requestCode(42, 1), alarms[1].requestCode)
    }

    @Test
    fun `a done task or a past reminder gets no alarm`() {
        assertTrue(ReminderAlarms.desiredAlarms(task(done = true), now).isEmpty())
        assertTrue(ReminderAlarms.desiredAlarms(task(), absoluteMillis + 1).isEmpty())
    }

    @Test
    fun `request codes differ across reminders of a task and across neighbouring tasks`() {
        val codes = (1L..200L).flatMap { id -> (0..9).map { ReminderAlarms.requestCode(id, it) } }
        assertEquals(codes.size, codes.toSet().size)
    }

    // --- decideFire ---

    @Test
    fun `fires for an open task that still has the scheduled reminder`() {
        val decision = ReminderAlarms.decideFire(task(), absoluteMillis, isSnooze = false)

        assertEquals(ReminderFireDecision.Show("Pay rent"), decision)
    }

    @Test
    fun `uses the task's current title`() {
        val decision = ReminderAlarms.decideFire(task(title = "Pay rent and deposit"), absoluteMillis, isSnooze = false)

        assertEquals(ReminderFireDecision.Show("Pay rent and deposit"), decision)
    }

    @Test
    fun `shows nothing when the task no longer exists`() {
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.TASK_MISSING),
            ReminderAlarms.decideFire(null, absoluteMillis, isSnooze = false),
        )
    }

    @Test
    fun `shows nothing when the task was completed elsewhere`() {
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.TASK_DONE),
            ReminderAlarms.decideFire(task(done = true), absoluteMillis, isSnooze = false),
        )
    }

    @Test
    fun `shows nothing when the reminder was removed`() {
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.REMINDER_REMOVED),
            ReminderAlarms.decideFire(task(reminders = emptyList()), absoluteMillis, isSnooze = false),
        )
    }

    @Test
    fun `shows nothing when the reminder moved to another time`() {
        val moved = task(reminders = listOf(TaskReminder(reminder = "2026-10-07T10:30:00Z")))

        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.REMINDER_REMOVED),
            ReminderAlarms.decideFire(moved, absoluteMillis, isSnooze = false),
        )
    }

    @Test
    fun `a relative reminder follows the due date it was scheduled for`() {
        val scheduledFor = dueMillis - 3_600_000
        val sameDue = task(reminders = listOf(oneHourBeforeDue))
        val dueMoved = sameDue.copy(dueDate = "2026-10-08T12:00:00Z")

        assertEquals(ReminderFireDecision.Show("Pay rent"), ReminderAlarms.decideFire(sameDue, scheduledFor, false))
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.REMINDER_REMOVED),
            ReminderAlarms.decideFire(dueMoved, scheduledFor, false),
        )
    }

    @Test
    fun `one of several reminders firing is checked against its own time`() {
        val t = task(reminders = listOf(absolute, oneHourBeforeDue))

        assertEquals(ReminderFireDecision.Show("Pay rent"), ReminderAlarms.decideFire(t, dueMillis - 3_600_000, false))
        assertEquals(ReminderFireDecision.Show("Pay rent"), ReminderAlarms.decideFire(t, absoluteMillis, false))
    }

    @Test
    fun `an alarm from an older version without a trigger time needs any reminder on an open task`() {
        assertEquals(ReminderFireDecision.Show("Pay rent"), ReminderAlarms.decideFire(task(), null, false))
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.REMINDER_REMOVED),
            ReminderAlarms.decideFire(task(reminders = emptyList()), null, false),
        )
    }

    @Test
    fun `a snooze only needs the task to exist and be open`() {
        val noReminders = task(reminders = emptyList())

        assertEquals(ReminderFireDecision.Show("Pay rent"), ReminderAlarms.decideFire(noReminders, null, isSnooze = true))
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.TASK_DONE),
            ReminderAlarms.decideFire(task(done = true), null, isSnooze = true),
        )
        assertEquals(
            ReminderFireDecision.Skip(ReminderSkipReason.TASK_MISSING),
            ReminderAlarms.decideFire(null, null, isSnooze = true),
        )
    }

    // --- diffRegistry ---

    @Test
    fun `a full pass cancels codes of tasks that are gone and of reminders that were dropped`() {
        val registered = mapOf(1L to setOf(10, 11), 2L to setOf(20))

        val diff = ReminderAlarms.diffRegistry(registered, desired = mapOf(1L to setOf(10)))

        assertEquals(setOf(11, 20), diff.cancelCodes)
        assertEquals(mapOf(1L to setOf(10)), diff.registry)
    }

    @Test
    fun `a full pass over an empty registry cancels nothing and registers what is wanted`() {
        val diff = ReminderAlarms.diffRegistry(emptyMap(), desired = mapOf(1L to setOf(10)))

        assertTrue(diff.cancelCodes.isEmpty())
        assertEquals(mapOf(1L to setOf(10)), diff.registry)
    }

    @Test
    fun `a scoped pass leaves other tasks alone`() {
        val registered = mapOf(1L to setOf(10, 11), 2L to setOf(20))

        val diff = ReminderAlarms.diffRegistry(registered, desired = mapOf(1L to setOf(10)), scope = setOf(1L))

        assertEquals(setOf(11), diff.cancelCodes)
        assertEquals(mapOf(1L to setOf(10), 2L to setOf(20)), diff.registry)
    }

    @Test
    fun `a scoped pass with nothing wanted forgets the task`() {
        val registered = mapOf(1L to setOf(10, 11), 2L to setOf(20))

        val diff = ReminderAlarms.diffRegistry(registered, desired = emptyMap(), scope = setOf(1L))

        assertEquals(setOf(10, 11), diff.cancelCodes)
        assertEquals(mapOf(2L to setOf(20)), diff.registry)
    }

    @Test
    fun `a code another task still wants is never cancelled`() {
        val diff = ReminderAlarms.diffRegistry(mapOf(1L to setOf(10)), desired = mapOf(2L to setOf(10)))

        assertTrue(diff.cancelCodes.isEmpty())
        assertEquals(mapOf(2L to setOf(10)), diff.registry)
    }
}
