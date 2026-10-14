package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrence
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RoutinePrefsStoreTest {

    @Test
    fun `routines and their Today section are on until turned off`() = runTest {
        val store = RoutinePrefsStore(InMemoryPreferencesDataStore())

        assertEquals(RoutineVisibility(enabled = true, showInToday = true), store.visibility.first())
        assertTrue(store.enabled.first())
    }

    @Test
    fun `Today shows routines only while both switches are on`() = runTest {
        val store = RoutinePrefsStore(InMemoryPreferencesDataStore())

        store.setShowInToday(false)
        assertTrue(store.enabled.first())
        assertFalse(store.visibility.first().inToday)

        store.setShowInToday(true)
        store.setEnabled(false)
        assertFalse(store.enabled.first())
        assertFalse(store.visibility.first().inToday, "turning routines off also hides them from Today")
    }

    @Test
    fun `an account wipe keeps the switches but not the device id`() = runTest {
        val store = RoutinePrefsStore(InMemoryPreferencesDataStore())
        val deviceId = store.getOrCreateDeviceId()
        store.setEnabled(false)
        store.setShowInToday(false)
        store.setRemindersEnabled(false)

        store.clear()

        assertEquals(RoutineVisibility(enabled = false, showInToday = false), store.visibility.first())
        assertTrue(store.remindersEnabled.first())
        assertNotEquals(deviceId, store.getOrCreateDeviceId())
    }

    @Test
    fun `completed and skipped occurrences are finished, the others stay open`() {
        val routine = Routine(
            taskId = 42,
            payload = RoutinePayload(
                definition = RoutineDefinition(
                    id = "routine-1",
                    name = "Creatine",
                    kind = RoutineKind.HEALTH,
                    schedule = RoutineSchedule.Calendar(anchorDate = "2026-10-01"),
                    slots = listOf(RoutineSlot("morning", "Morning", RoutinePeriod.MORNING)),
                    activeFrom = "2026-10-01",
                    createdAt = "2026-10-01T08:00:00Z",
                    updatedAt = "2026-10-01T08:00:00Z",
                    updatedBy = "phone",
                ),
            ),
        )
        val day = RoutineDay(
            "2026-10-07",
            OccurrenceStatus.entries.map { status ->
                RoutineOccurrence(routine, "routine-1:2026-10-07:$status", routine.definition.slots.single(), "2026-10-07", status)
            },
        )

        assertEquals(listOf(OccurrenceStatus.PENDING, OccurrenceStatus.NOT_LOGGED), day.open.map { it.status })
        assertEquals(4, day.scheduledCount, "the count still covers the whole day")
        assertEquals(1, day.completedCount)
    }
}
