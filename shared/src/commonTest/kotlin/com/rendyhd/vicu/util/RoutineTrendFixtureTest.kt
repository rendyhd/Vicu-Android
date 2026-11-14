package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutineSchedule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `trend` vectors of routine-archive-v1.json (docs/cross-app-semantics-v1.md, section 6.11). */
class RoutineTrendFixtureTest {

    @Serializable
    private data class Occurrence(
        val date: String,
        val status: OccurrenceStatus,
        val slot: String = RoutineArchiveFixture.SLOT_ID,
        val loggedAt: String = "",
        val timeZoneId: String = "UTC",
    )

    @Serializable
    private data class Expect(
        val days: Map<String, String>,
        val streak: Int?,
        val done: Int,
        val expected: Int?,
        val weeks: List<List<Int>>?,
        val summary: String,
    )

    @Serializable
    private data class Case(
        val name: String,
        val today: String,
        val schedule: RoutineSchedule? = null,
        val slots: List<String> = listOf(RoutineArchiveFixture.SLOT_ID),
        val activeFrom: String? = null,
        val occurrences: List<Occurrence>,
        val from: String,
        val to: String,
        val expect: Expect,
    )

    @Serializable
    private data class Block(val windowDays: Int, val weeks: Int, val cases: List<Case>)

    @Serializable
    private data class File(val trend: Block)

    private val block: Block = Json { ignoreUnknownKeys = true }
        .decodeFromString(File.serializer(), CrossAppFixture.readTestFixture("routine-archive-v1.json")).trend

    private fun trendOf(case: Case, zone: kotlinx.datetime.TimeZone): RoutineTrend {
        val base = RoutineArchiveFixture.fixture.baseDefinition
        val slot = base.slots.single()
        val schedule = case.schedule ?: base.schedule
        val definition = base.copy(
            kind = if (schedule is RoutineSchedule.AfterCompletion) RoutineKind.CHORE else base.kind,
            schedule = schedule,
            slots = case.slots.mapIndexed { index, id -> slot.copy(id = id, reminderMinutes = slot.reminderMinutes + index * 60) },
            activeFrom = case.activeFrom ?: base.activeFrom,
        )
        val records = case.occurrences.map { occurrence ->
            RoutineOccurrenceRecord(
                key = "${RoutineArchiveFixture.ROUTINE_ID}:${occurrence.date}:${occurrence.slot}",
                routineId = RoutineArchiveFixture.ROUTINE_ID,
                slotId = occurrence.slot,
                scheduledDate = occurrence.date,
                scheduledMinutes = 480,
                timeZoneId = occurrence.timeZoneId,
                status = occurrence.status,
                loggedAt = occurrence.loggedAt,
                modifiedAt = "2026-11-13T08:00:00.000Z",
                modifiedBy = "a",
            )
        }
        return RoutineTrend.of(definition, records, LocalDate.parse(case.today), zone)
    }

    @Test
    fun constantsMatch() {
        assertEquals(block.windowDays, RoutineTrend.WINDOW_DAYS)
        assertEquals(block.weeks, RoutineTrend.WEEKS)
    }

    @Test
    fun everyCaseInEveryZone() = RoutineArchiveFixture.inEachZone { zone ->
        for (case in block.cases) {
            val where = "${case.name} (${zone.id})"
            val trend = trendOf(case, zone)

            val days = buildMap {
                var date = LocalDate.parse(case.from)
                val to = LocalDate.parse(case.to)
                while (date <= to) {
                    val state = trend.state(date)
                    if (state != RoutineTrend.DayState.NONE) put(date.toString(), state.key)
                    date = date.plus(1, DateTimeUnit.DAY)
                }
            }
            assertEquals(case.expect.days, days, "days: $where")
            assertEquals(case.expect.streak, trend.streak, "streak: $where")
            assertEquals(case.expect.done, trend.window.done, "done: $where")
            // Nothing is expected of an after-completion routine: its tally then counts only what was done.
            assertEquals(case.expect.expected ?: trend.window.done, trend.window.expected, "expected: $where")
            assertEquals(case.expect.weeks, trend.weeks?.map { listOf(it.done, it.expected) }, "weeks: $where")
            assertEquals(case.expect.summary, trend.summary, "summary: $where")
        }
    }

    @Test
    fun `the day picked lists every time of that day in time order`() {
        val case = block.cases.first { it.slots.size == 2 }
        val trend = trendOf(case, kotlinx.datetime.TimeZone.UTC)

        // 10 November: the first time was done, the second has no record and is missed.
        val day = trend.occurrencesOn(LocalDate(2026, 11, 10))
        assertEquals(listOf("s1", "s2"), day.map { it.slotId })
        assertEquals(listOf(OccurrenceStatus.COMPLETED, OccurrenceStatus.NOT_LOGGED), day.map { it.status })
        assertEquals(listOf(true, false), day.map { it.record != null })
        // Today the second time is still to do; the day before the routine started holds nothing.
        assertEquals(OccurrenceStatus.PENDING, trend.occurrencesOn(LocalDate(2026, 11, 13)).last().status)
        assertEquals(emptyList(), trend.occurrencesOn(LocalDate(2026, 11, 8)))
        assertEquals(emptyList(), trend.occurrencesOn(LocalDate(2026, 11, 14)), "a day after today")
    }
}
