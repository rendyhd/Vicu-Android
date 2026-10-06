package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.time.Duration.Companion.seconds

/** One alarm that should exist for one reminder of one task. */
data class ReminderAlarmSpec(
    val taskId: Long,
    val reminderIndex: Int,
    val requestCode: Int,
    val triggerAtMillis: Long,
)

enum class ReminderSkipReason { TASK_MISSING, TASK_DONE, REMINDER_REMOVED }

/** What the alarm receiver does with a fired reminder. */
sealed interface ReminderFireDecision {
    /** Show the notification, titled with the task's current [title]. */
    data class Show(val title: String) : ReminderFireDecision

    /** The reminder is stale: show nothing. */
    data class Skip(val reason: ReminderSkipReason) : ReminderFireDecision
}

/**
 * What a registry pass must do: cancel [cancelCodes] (registered, no longer wanted) and persist
 * [registry] (task id to request codes) once the wanted alarms are scheduled.
 */
data class ReminderRegistryDiff(
    val cancelCodes: Set<Int>,
    val registry: Map<Long, Set<Int>>,
)

/**
 * Pure rules for task reminder alarms, shared by the scheduler, the receiver and the tests.
 * Nothing here touches AlarmManager.
 */
object ReminderAlarms {

    /**
     * Stable PendingIntent request code for (task, reminder index). Hashing the 64-bit
     * `taskId * 100 + index` avoids the Int truncation of large server ids. Unchanged from the
     * original scheme so alarms scheduled by older versions are replaced, not duplicated.
     */
    fun requestCode(taskId: Long, reminderIndex: Int): Int = (taskId * 100 + reminderIndex).hashCode()

    /**
     * When [reminder] fires for a task due at [dueDate], or null if it cannot be resolved.
     * An absolute reminder is its own timestamp; a relative one is an offset (seconds, negative
     * for before) from the due date. A period of 0 with a `relative_to` means "at due time".
     */
    fun resolveTriggerMillis(reminder: TaskReminder, dueDate: String): Long? {
        if (reminder.reminder.isNotBlank()) {
            val instant = DateUtils.parseIsoDate(reminder.reminder)
            if (instant != null) return instant.toEpochMilliseconds()
        }
        if (reminder.relativePeriod != 0L || reminder.relativeTo.isNotBlank()) {
            val baseDate = DateUtils.parseIsoDate(dueDate)
            if (baseDate != null) {
                return (baseDate + reminder.relativePeriod.seconds).toEpochMilliseconds()
            }
        }
        return null
    }

    /** The alarms [task] should have right now: none for a done task or a reminder in the past. */
    fun desiredAlarms(task: Task, nowMillis: Long): List<ReminderAlarmSpec> {
        if (task.done) return emptyList()
        return task.reminders.mapIndexedNotNull { index, reminder ->
            val trigger = resolveTriggerMillis(reminder, task.dueDate) ?: return@mapIndexedNotNull null
            if (trigger <= nowMillis) return@mapIndexedNotNull null
            ReminderAlarmSpec(task.id, index, requestCode(task.id, index), trigger)
        }
    }

    /**
     * Re-checks a fired alarm against the task as it is stored now, so an alarm that outlived its
     * reason (task completed or deleted on another device, reminder removed or moved) shows
     * nothing.
     *
     * [triggerAtMillis] is the time the alarm was scheduled for; the task must still have a
     * reminder that resolves to it. Alarms scheduled by older versions carry no trigger time and
     * only need the task to be open with some reminder. A snooze is the user's explicit request,
     * so it only needs the task to exist and be open.
     */
    fun decideFire(task: Task?, triggerAtMillis: Long?, isSnooze: Boolean): ReminderFireDecision {
        if (task == null) return ReminderFireDecision.Skip(ReminderSkipReason.TASK_MISSING)
        if (task.done) return ReminderFireDecision.Skip(ReminderSkipReason.TASK_DONE)
        if (isSnooze) return ReminderFireDecision.Show(task.title)
        val stillHasReminder = if (triggerAtMillis == null) {
            task.reminders.isNotEmpty()
        } else {
            task.reminders.any { resolveTriggerMillis(it, task.dueDate) == triggerAtMillis }
        }
        return if (stillHasReminder) {
            ReminderFireDecision.Show(task.title)
        } else {
            ReminderFireDecision.Skip(ReminderSkipReason.REMINDER_REMOVED)
        }
    }

    /**
     * Compares the persisted registry ([registered]: task id to request codes) with the alarms
     * that should exist ([desired], same shape).
     *
     * With [scope] null the pass owns every task: any registered code that is no longer desired
     * is cancelled, which is how alarms of completed, deleted or edited tasks disappear. With a
     * [scope], only those tasks are touched and the rest of the registry is carried over.
     * A code another task still wants is never cancelled, so a hash collision cannot kill a
     * live alarm.
     */
    fun diffRegistry(
        registered: Map<Long, Set<Int>>,
        desired: Map<Long, Set<Int>>,
        scope: Set<Long>? = null,
    ): ReminderRegistryDiff {
        fun inScope(taskId: Long) = scope == null || taskId in scope
        val carriedOver = registered.filterKeys { !inScope(it) }
        val wanted = desired.filterKeys { inScope(it) }.filterValues { it.isNotEmpty() }
        val newRegistry = carriedOver + wanted
        val stillWanted = newRegistry.values.flatten().toSet()
        val cancel = registered.filterKeys { inScope(it) }.values.flatten().toSet() - stillWanted
        return ReminderRegistryDiff(cancelCodes = cancel, registry = newRegistry)
    }
}
