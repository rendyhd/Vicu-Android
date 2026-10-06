package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineScheduleEngineTest {
    @Test
    fun fortnightlyCalendarUsesAnchorWeek() {
        val schedule = RoutineSchedule.Calendar(
            weekdays = setOf(2),
            weekInterval = 2,
            anchorDate = "2026-08-03",
        )

        assertTrue(RoutineScheduleEngine.isScheduledOn(schedule, LocalDate(2026, 8, 4)))
        assertFalse(RoutineScheduleEngine.isScheduledOn(schedule, LocalDate(2026, 8, 11)))
        assertTrue(RoutineScheduleEngine.isScheduledOn(schedule, LocalDate(2026, 8, 18)))
    }

    @Test
    fun fortnightlyCalendarDoesNotTreatNextMondayAsTheAnchorWeek() {
        val schedule = RoutineSchedule.Calendar(
            weekdays = setOf(1),
            weekInterval = 2,
            anchorDate = "2026-08-04",
        )

        assertFalse(RoutineScheduleEngine.isScheduledOn(schedule, LocalDate(2026, 8, 10)))
        assertTrue(RoutineScheduleEngine.isScheduledOn(schedule, LocalDate(2026, 8, 17)))
    }

    @Test
    fun completionBasedChoreStaysVisibleAsOneOverdueOccurrence() {
        val routine = chore(
            schedule = RoutineSchedule.AfterCompletion(intervalDays = 14, firstDueDate = "2026-08-01"),
        )

        val occurrences = RoutineScheduleEngine.occurrencesForDate(
            routine,
            date = LocalDate(2026, 8, 11),
            today = LocalDate(2026, 8, 11),
        )

        assertEquals(1, occurrences.size)
        assertEquals("2026-08-01", occurrences.single().scheduledDate)
        assertTrue(occurrences.single().overdue)
    }

    @Test
    fun nextCompletionBasedDueDateMovesFromActualCompletion() {
        val completed = RoutineOccurrenceRecord(
            key = "routine-1:2026-08-03:home",
            routineId = "routine-1",
            slotId = "home",
            scheduledDate = "2026-08-03",
            scheduledMinutes = 1080,
            timeZoneId = "Europe/Amsterdam",
            status = OccurrenceStatus.COMPLETED,
            modifiedAt = "2026-08-04T08:00:00Z",
            modifiedBy = "phone",
        )
        val routine = chore(
            schedule = RoutineSchedule.AfterCompletion(intervalDays = 14, firstDueDate = "2026-08-01"),
            occurrences = mapOf(completed.key to completed),
        )

        assertTrue(RoutineScheduleEngine.occurrencesForDate(
            routine,
            date = LocalDate(2026, 8, 17),
            today = LocalDate(2026, 8, 11),
        ).isNotEmpty())
    }

    @Test
    fun completionBasedChoreCountsFromTheDateItWasLogged() {
        // Due 08-03, but done on 08-10: the next one is 14 days after the 10th, not the 3rd.
        val completed = completedRecord(
            scheduledDate = "2026-08-03",
            loggedAt = "2026-08-10T07:00:00.000Z",
            timeZoneId = "Europe/Amsterdam",
        )
        val routine = chore(
            schedule = RoutineSchedule.AfterCompletion(intervalDays = 14, firstDueDate = "2026-08-01"),
            occurrences = mapOf(completed.key to completed),
        )
        val today = LocalDate(2026, 8, 11)

        assertEquals(
            emptyList(),
            RoutineScheduleEngine.occurrencesForDate(routine, LocalDate(2026, 8, 17), today = today),
        )
        assertEquals(
            "2026-08-24",
            RoutineScheduleEngine.occurrencesForDate(routine, LocalDate(2026, 8, 24), today = today)
                .single().scheduledDate,
        )
    }

    @Test
    fun theCompletionDateIsTheLocalDateInTheOccurrencesOwnZone() {
        // 23:30 UTC on the 10th is already the 11th in Tokyo.
        val record = completedRecord("2026-08-03", "2026-08-10T23:30:00.000Z", "Asia/Tokyo")

        assertEquals(LocalDate(2026, 8, 11), RoutineScheduleEngine.completionDate(record, TimeZone.of("America/New_York")))
    }

    @Test
    fun aMissingOrUnknownZoneFallsBackToTheDeviceZone() {
        val withoutZone = completedRecord("2026-08-03", "2026-08-10T23:30:00.000Z", "")
        val unknownZone = completedRecord("2026-08-03", "2026-08-10T23:30:00.000Z", "Mars/Olympus")

        assertEquals(LocalDate(2026, 8, 11), RoutineScheduleEngine.completionDate(withoutZone, TimeZone.of("Asia/Tokyo")))
        assertEquals(LocalDate(2026, 8, 10), RoutineScheduleEngine.completionDate(withoutZone, TimeZone.of("America/New_York")))
        assertEquals(LocalDate(2026, 8, 11), RoutineScheduleEngine.completionDate(unknownZone, TimeZone.of("Asia/Tokyo")))
    }

    @Test
    fun aCompletionWithoutALoggedTimeCountsFromItsScheduledDate() {
        val record = completedRecord("2026-08-03", "", "Europe/Amsterdam")

        assertEquals(LocalDate(2026, 8, 3), RoutineScheduleEngine.completionDate(record, TimeZone.UTC))
    }

    @Test
    fun onlyCompletedOccurrencesCountAsACompletion() {
        val skipped = completedRecord("2026-08-09", "2026-08-09T07:00:00.000Z", "UTC")
            .copy(status = OccurrenceStatus.SKIPPED)
        val done = completedRecord("2026-08-03", "2026-08-04T07:00:00.000Z", "UTC")

        assertEquals(
            LocalDate(2026, 8, 4),
            RoutineScheduleEngine.latestCompletionDate(listOf(skipped, done), TimeZone.UTC),
        )
    }

    private fun completedRecord(scheduledDate: String, loggedAt: String, timeZoneId: String) =
        RoutineOccurrenceRecord(
            key = "routine-1:$scheduledDate:home",
            routineId = "routine-1",
            slotId = "home",
            scheduledDate = scheduledDate,
            scheduledMinutes = 1080,
            timeZoneId = timeZoneId,
            status = OccurrenceStatus.COMPLETED,
            loggedAt = loggedAt,
            modifiedAt = "2026-08-04T08:00:00.000Z",
            modifiedBy = "phone",
        )

    private fun chore(
        schedule: RoutineSchedule,
        occurrences: Map<String, RoutineOccurrenceRecord> = emptyMap(),
    ): Routine = Routine(
        taskId = 42,
        payload = RoutinePayload(
            definition = RoutineDefinition(
                id = "routine-1",
                name = "Take out trash",
                kind = RoutineKind.CHORE,
                schedule = schedule,
                slots = listOf(RoutineSlot("home", "Home", RoutinePeriod.HOME)),
                activeFrom = "2026-07-01",
                createdAt = "2026-07-01T08:00:00Z",
                updatedAt = "2026-07-01T08:00:00Z",
                updatedBy = "phone",
            ),
            occurrences = occurrences,
        ),
    )
}
