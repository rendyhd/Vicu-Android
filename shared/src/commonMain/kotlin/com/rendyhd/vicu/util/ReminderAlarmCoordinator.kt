package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.local.ReminderAlarmRegistry
import com.rendyhd.vicu.domain.model.Task
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/** The platform half of reminder alarms: set and cancel one alarm by request code. */
interface ReminderAlarmBackend {
    fun schedule(alarm: ReminderAlarmSpec, taskTitle: String)
    fun cancel(requestCode: Int)
}

/**
 * Keeps the system's reminder alarms and the persisted [ReminderAlarmRegistry] in step with the
 * tasks. All operations are serialized, so a reschedule triggered by a sync cannot interleave
 * with a cancel triggered by completing the task.
 */
class ReminderAlarmCoordinator(
    private val registry: ReminderAlarmRegistry,
    private val backend: ReminderAlarmBackend,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()

    /** Replaces the alarms of one task with the ones its reminders call for right now. */
    suspend fun scheduleForTask(task: Task) = mutex.withLock {
        val specs = ReminderAlarms.desiredAlarms(task, nowMillis())
        apply(
            desired = mapOf(task.id to specs.map { it.requestCode }.toSet()),
            scope = setOf(task.id),
            toSchedule = specs,
            titles = mapOf(task.id to task.title),
        )
    }

    /** Cancels every registered alarm of one task. */
    suspend fun cancelForTask(taskId: Long) = mutex.withLock {
        apply(desired = emptyMap(), scope = setOf(taskId), toSchedule = emptyList(), titles = emptyMap())
    }

    /**
     * Full pass over [openTasksWithReminders] (every open task that has reminders). Registered
     * alarms of any task not in the list, and alarms of listed tasks that no reminder calls for
     * any more, are cancelled.
     */
    suspend fun reconcileAll(openTasksWithReminders: List<Task>) = mutex.withLock {
        val now = nowMillis()
        val specs = openTasksWithReminders.flatMap { ReminderAlarms.desiredAlarms(it, now) }
        apply(
            desired = specs.groupBy({ it.taskId }, { it.requestCode }).mapValues { it.value.toSet() },
            scope = null,
            toSchedule = specs,
            titles = openTasksWithReminders.associate { it.id to it.title },
        )
    }

    /** Cancels everything registered and forgets it (sign-out). */
    suspend fun cancelAll() = mutex.withLock {
        apply(desired = emptyMap(), scope = null, toSchedule = emptyList(), titles = emptyMap())
    }

    private suspend fun apply(
        desired: Map<Long, Set<Int>>,
        scope: Set<Long>?,
        toSchedule: List<ReminderAlarmSpec>,
        titles: Map<Long, String>,
    ) {
        val diff = ReminderAlarms.diffRegistry(registry.all(), desired, scope)
        diff.cancelCodes.forEach { backend.cancel(it) }
        toSchedule.forEach { backend.schedule(it, titles[it.taskId].orEmpty()) }
        registry.replaceAll(diff.registry)
    }
}
