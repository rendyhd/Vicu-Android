package com.rendyhd.vicu.util

import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.ReminderAlarmRegistry
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderAlarmCoordinatorTest {

    private class FakeBackend : ReminderAlarmBackend {
        /** Request codes of the alarms that currently exist, with the title they would show. */
        val live = LinkedHashMap<Int, String>()
        val cancelled = mutableListOf<Int>()

        override fun schedule(alarm: ReminderAlarmSpec, taskTitle: String) {
            live[alarm.requestCode] = taskTitle
        }

        override fun cancel(requestCode: Int) {
            cancelled += requestCode
            live.remove(requestCode)
        }
    }

    private val now = Instant.parse("2026-10-06T08:00:00Z").toEpochMilliseconds()
    private val store = InMemoryPreferencesDataStore()
    private val registry = ReminderAlarmRegistry(store, authTestJson)
    private val backend = FakeBackend()
    private val coordinator = ReminderAlarmCoordinator(registry, backend, nowMillis = { now })

    private fun reminder(hour: Int) = TaskReminder(reminder = "2026-10-06T${hour.toString().padStart(2, '0')}:00:00Z")

    private fun task(id: Long, vararg hours: Int, done: Boolean = false, title: String = "Task $id") =
        Task(id = id, title = title, done = done, reminders = hours.map { reminder(it) })

    private fun code(taskId: Long, index: Int) = ReminderAlarms.requestCode(taskId, index)

    @Test
    fun `scheduling a task registers its alarms`() = runTest {
        coordinator.scheduleForTask(task(1, 10, 11))

        assertEquals(setOf(code(1, 0), code(1, 1)), backend.live.keys)
        assertEquals(mapOf(1L to setOf(code(1, 0), code(1, 1))), registry.all())
    }

    @Test
    fun `rescheduling a task whose reminder was removed cancels exactly that alarm`() = runTest {
        coordinator.scheduleForTask(task(1, 10, 11))

        coordinator.scheduleForTask(task(1, 10))

        assertEquals(listOf(code(1, 1)), backend.cancelled)
        assertEquals(setOf(code(1, 0)), backend.live.keys)
        assertEquals(mapOf(1L to setOf(code(1, 0))), registry.all())
    }

    @Test
    fun `reconciling some tasks leaves every other registered alarm alone`() = runTest {
        coordinator.reconcileAll(listOf(task(1, 10), task(2, 10), task(3, 10)))
        backend.cancelled.clear()

        // Task 1 got a later reminder on another device, task 3 was deleted there.
        coordinator.reconcileTasks(changed = listOf(task(1, 12)), goneTaskIds = setOf(3L))

        assertEquals(setOf(code(1, 0), code(2, 0)), backend.live.keys)
        assertEquals(listOf(code(3, 0)), backend.cancelled, "only the deleted task's alarm is cancelled")
        assertEquals(mapOf(1L to setOf(code(1, 0)), 2L to setOf(code(2, 0))), registry.all())
    }

    @Test
    fun `a changed task that is now done or has no reminders has its alarms cancelled`() = runTest {
        coordinator.reconcileAll(listOf(task(1, 10, 11), task(2, 10)))
        backend.cancelled.clear()

        coordinator.reconcileTasks(changed = listOf(task(1, 10, 11, done = true), task(2)), goneTaskIds = emptySet())

        assertTrue(backend.live.isEmpty())
        assertEquals(setOf(code(1, 0), code(1, 1), code(2, 0)), backend.cancelled.toSet())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `a full pass cancels alarms of tasks completed or deleted on another device`() = runTest {
        coordinator.reconcileAll(listOf(task(1, 10), task(2, 10), task(3, 10)))
        assertEquals(3, backend.live.size)

        // Task 2 was completed elsewhere (no longer an open task with reminders), task 3 deleted.
        coordinator.reconcileAll(listOf(task(1, 10)))

        assertEquals(setOf(code(1, 0)), backend.live.keys)
        assertEquals(setOf(code(2, 0), code(3, 0)), backend.cancelled.toSet())
        assertEquals(mapOf(1L to setOf(code(1, 0))), registry.all())
    }

    @Test
    fun `a full pass cancels only what changed and reschedules the rest in place`() = runTest {
        coordinator.reconcileAll(listOf(task(1, 10), task(2, 10)))
        backend.cancelled.clear()

        coordinator.reconcileAll(listOf(task(1, 10), task(2, 10)))

        assertTrue(backend.cancelled.isEmpty(), "unchanged alarms are replaced by the platform, not cancelled")
        assertEquals(2, backend.live.size)
    }

    @Test
    fun `a task that is done schedules nothing and clears its old alarms`() = runTest {
        coordinator.scheduleForTask(task(1, 10))

        coordinator.scheduleForTask(task(1, 10, done = true))

        assertTrue(backend.live.isEmpty())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `cancelling one task leaves the others`() = runTest {
        coordinator.reconcileAll(listOf(task(1, 10), task(2, 10)))

        coordinator.cancelForTask(1)

        assertEquals(setOf(code(2, 0)), backend.live.keys)
        assertEquals(mapOf(2L to setOf(code(2, 0))), registry.all())
    }

    @Test
    fun `cancel all removes every registered alarm and empties the registry`() = runTest {
        coordinator.reconcileAll(listOf(task(1, 10, 11), task(2, 10)))

        coordinator.cancelAll()

        assertTrue(backend.live.isEmpty())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `past reminders are not scheduled`() = runTest {
        coordinator.scheduleForTask(task(1, 6, 10))

        assertEquals(setOf(code(1, 1)), backend.live.keys)
    }

    @Test
    fun `alarms use the title the task has now`() = runTest {
        coordinator.scheduleForTask(task(1, 10, title = "Old"))
        coordinator.scheduleForTask(task(1, 10, title = "New"))

        assertEquals("New", backend.live[code(1, 0)])
    }

    @Test
    fun `the registry survives a new coordinator over the same store`() = runTest {
        coordinator.scheduleForTask(task(1, 10))

        val restarted = ReminderAlarmCoordinator(ReminderAlarmRegistry(store, authTestJson), backend, nowMillis = { now })
        restarted.reconcileAll(emptyList())

        assertTrue(backend.live.isEmpty(), "a registry from an earlier process still lets the alarm be cancelled")
    }

    @Test
    fun `a damaged registry reads as empty`() = runTest {
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(stringPreferencesKey("reminder_alarm_registry"), "not json")
            }
        }

        assertTrue(registry.all().isEmpty())
    }
}
