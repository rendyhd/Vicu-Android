package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** The timeline's rules, the same cases as the desktop's `planner-timeline.test.ts`, in two zones. */
class PlannerTimelineTest {

    private val zones = listOf(TimeZone.of("Pacific/Auckland"), TimeZone.of("America/New_York"))
    private val day = LocalDate(2026, 10, 6)

    private fun iso(local: String, zone: TimeZone): String = LocalDateTime.parse(local).toInstant(zone).toString()
    private fun dateOnly(date: String, zone: TimeZone): String = DueDates.dateOnlyDue(LocalDate.parse(date), zone).toString()

    private fun task(id: Long, due: String = "", start: String = "", end: String = "", done: Boolean = false) =
        Task(id = id, title = "Task $id", projectId = 1, dueDate = due, startDate = start, endDate = end, done = done)

    private fun eachZone(check: (TimeZone) -> Unit) = zones.forEach(check)

    @Test
    fun aBlockSitsAtItsWallClockMinutesAndAnEndAtMidnightIsTheEndOfTheDay() = eachZone { zone ->
        val (block, late) = PlannerTimeline.dayBlocks(
            listOf(
                task(1, start = iso("2026-10-06T09:15:00", zone), end = iso("2026-10-06T10:00:00", zone)),
                task(2, start = iso("2026-10-06T23:00:00", zone), end = iso("2026-10-07T00:00:00", zone)),
            ),
            day,
            zone,
        )
        assertEquals(listOf(555, 600, 0, 1), listOf(block.startMinute, block.endMinute, block.column, block.columnCount))
        assertEquals(listOf(1380, 1440), listOf(late.startMinute, late.endMinute))
    }

    @Test
    fun anotherDayAndTwoDatesThatAreNotABlockAreLeftOut() = eachZone { zone ->
        val shown = PlannerTimeline.dayBlocks(
            listOf(
                task(1, start = iso("2026-10-07T09:00:00", zone), end = iso("2026-10-07T10:00:00", zone)),
                task(2, start = iso("2026-10-06T09:00:00", zone)),
                task(3, start = iso("2026-10-06T09:00:00", zone), end = iso("2026-10-09T10:00:00", zone)),
            ),
            day,
            zone,
        )
        assertTrue(shown.isEmpty())
    }

    @Test
    fun overlappingBlocksSitSideBySideAndALaterOneOnItsOwn() = eachZone { zone ->
        val shown = PlannerTimeline.dayBlocks(
            listOf(
                task(1, start = iso("2026-10-06T09:00:00", zone), end = iso("2026-10-06T10:00:00", zone)),
                task(2, start = iso("2026-10-06T09:30:00", zone), end = iso("2026-10-06T10:30:00", zone)),
                task(3, start = iso("2026-10-06T11:00:00", zone), end = iso("2026-10-06T11:30:00", zone)),
            ),
            day,
            zone,
        )
        assertEquals(
            listOf(Triple(1L, 0, 2), Triple(2L, 1, 2), Triple(3L, 0, 1)),
            shown.map { Triple(it.task.id, it.column, it.columnCount) },
        )
    }

    @Test
    fun aShortBlockKeepsTheColumnOfTheBlockThatStartsWhereItEnds() = eachZone { zone ->
        val (short, next) = PlannerTimeline.dayBlocks(
            listOf(
                task(1, start = iso("2026-10-06T08:00:00", zone), end = iso("2026-10-06T08:15:00", zone)),
                task(2, start = iso("2026-10-06T08:15:00", zone), end = iso("2026-10-06T08:45:00", zone)),
            ),
            day,
            zone,
        )
        assertEquals(listOf(0, 1, 0, 1), listOf(short.column, short.columnCount, next.column, next.columnCount))
        assertEquals(15, short.drawnMinutes)
        assertEquals(30, next.drawnMinutes)
    }

    @Test
    fun aShortBlockIsDrawnTallerOnlyWhileTheRoomIsFreeAndNeverPastTheDay() = eachZone { zone ->
        val shown = PlannerTimeline.dayBlocks(
            listOf(
                task(1, start = iso("2026-10-06T08:00:00", zone), end = iso("2026-10-06T08:15:00", zone)),
                task(2, start = iso("2026-10-06T10:00:00", zone), end = iso("2026-10-06T10:15:00", zone)),
                task(3, start = iso("2026-10-06T10:20:00", zone), end = iso("2026-10-06T11:00:00", zone)),
                task(4, start = iso("2026-10-06T23:45:00", zone), end = iso("2026-10-07T00:00:00", zone)),
            ),
            day,
            zone,
        )
        assertEquals(listOf(PlannerTimeline.MIN_ITEM_MINUTES, 20, 40, 15), shown.map { it.drawnMinutes })
    }

    @Test
    fun aColumnThatHasBecomeFreeInAGroupIsUsedAgain() {
        val zone = TimeZone.UTC
        fun item(id: Long, start: Int, end: Int): PlacedBlock {
            val block = TimeBlock(TimeBlocks.atClock(day, start, zone), TimeBlocks.atClock(day, end, zone), day, end - start)
            return PlacedBlock(task(id), block, start, end)
        }
        assertEquals(
            listOf(0 to 2, 1 to 2, 0 to 2),
            PlannerTimeline.layoutOverlaps(listOf(item(1, 0, 60), item(2, 30, 120), item(3, 60, 90)))
                .map { it.column to it.columnCount },
        )
    }

    @Test
    fun aMarkerKnowsWhenItLiesOverABlock() = eachZone { zone ->
        val blocks = PlannerTimeline.dayBlocks(
            listOf(task(1, start = iso("2026-10-06T09:15:00", zone), end = iso("2026-10-06T10:00:00", zone))),
            day,
            zone,
        )
        assertTrue(PlannerTimeline.markerOverBlock(570, blocks))
        assertTrue(PlannerTimeline.markerOverBlock(550, blocks))
        assertTrue(PlannerTimeline.markerOverBlock(605, blocks))
        assertFalse(PlannerTimeline.markerOverBlock(540, blocks))
        assertFalse(PlannerTimeline.markerOverBlock(610, blocks))
        assertFalse(PlannerTimeline.markerOverBlock(570, emptyList()))
    }

    @Test
    fun readyListsATaskByItsDueTimeOnlyOnThatDay() = eachZone { zone ->
        assertEquals(LocalTime(9, 30), PlanDay.dueTimeOn(task(1, due = iso("2026-10-06T09:30:00", zone)), day, zone))
        assertEquals(null, PlanDay.dueTimeOn(task(2, due = dateOnly("2026-10-06", zone)), day, zone))
        assertEquals(null, PlanDay.dueTimeOn(task(3, due = iso("2026-10-05T09:30:00", zone)), day, zone))
        assertEquals(null, PlanDay.dueTimeOn(task(4), day, zone))
    }

    @Test
    fun aDueTimeWithNoBlockIsAMarkerButADateOnlyOrDoneOneIsNot() = eachZone { zone ->
        val markers = PlannerTimeline.dayMarkers(
            listOf(
                task(1, due = iso("2026-10-06T14:00:00", zone)),
                task(2, due = dateOnly("2026-10-06", zone)),
                task(3, due = iso("2026-10-06T15:00:00", zone), done = true),
                task(
                    4,
                    due = iso("2026-10-06T16:00:00", zone),
                    start = iso("2026-10-06T09:00:00", zone),
                    end = iso("2026-10-06T10:00:00", zone),
                ),
                task(5, due = iso("2026-10-07T14:00:00", zone)),
            ),
            day,
            zone,
        )
        assertEquals(listOf(1L to 840), markers.map { it.task.id to it.minute })
    }

    @Test
    fun theFirstListHoldsTheDayWithoutATimeAndOverdueOnlyOnToday() = eachZone { zone ->
        val tasks = listOf(
            task(1, due = dateOnly("2026-10-06", zone)),
            task(2, due = dateOnly("2026-10-05", zone)),
            task(3, due = iso("2026-10-05T14:00:00", zone)),
            task(4, due = iso("2026-10-06T14:00:00", zone)),
            task(
                5,
                due = dateOnly("2026-10-06", zone),
                start = iso("2026-10-06T09:00:00", zone),
                end = iso("2026-10-06T10:00:00", zone),
            ),
            task(6, due = dateOnly("2026-10-06", zone), done = true),
            task(7, due = dateOnly("2026-10-07", zone)),
            task(8),
        )
        assertEquals(listOf(1L, 2L, 3L), PlannerTimeline.dayWithoutTime(tasks, day, day, zone).map { it.id })
        assertEquals(listOf(7L), PlannerTimeline.dayWithoutTime(tasks, LocalDate(2026, 10, 7), day, zone).map { it.id })
        assertEquals(
            listOf(1L, 2L, 3L, 6L),
            PlannerTimeline.dayWithoutTime(tasks, day, day, zone) { it.id == 6L }.map { it.id },
        )
    }

    @Test
    fun theOpenBlocksOfADayAreAddedUp() = eachZone { zone ->
        val tasks = listOf(
            task(1, start = iso("2026-10-06T09:00:00", zone), end = iso("2026-10-06T09:45:00", zone)),
            task(2, start = iso("2026-10-06T11:00:00", zone), end = iso("2026-10-06T12:00:00", zone)),
            task(3, start = iso("2026-10-06T13:00:00", zone), end = iso("2026-10-06T14:00:00", zone), done = true),
        )
        assertEquals(105, PlannerTimeline.plannedMinutes(tasks, day, zone))
    }

    @Test
    fun lengthsReadShort() {
        assertEquals("1h 45m", PlannerTimeline.formatLength(105))
        assertEquals("45m", PlannerTimeline.formatLength(45))
        assertEquals("1h", PlannerTimeline.formatLength(60))
    }

    @Test
    fun theRequestTakesBlocksInRangeOpenTasksDueInItAndOverdueWhenTodayIsShown() = eachZone { zone ->
        val days = listOf(LocalDate(2026, 10, 6), LocalDate(2026, 10, 7), LocalDate(2026, 10, 8))
        val from = Instant.parse(iso("2026-10-06T00:00:00", zone))
        val to = Instant.parse(iso("2026-10-09T00:00:00", zone))
        val filter = PlannerTimeline.planRangeFilter(days, day, zone)
        assertEquals(
            "(start_date >= '$from' && start_date < '$to') || (done = false && " +
                "((due_date >= '$from' && due_date < '$to') || (due_date != '0001-01-01T00:00:00Z' && due_date < '$from')))",
            filter,
        )
        // Another day than today: no overdue.
        val later = PlannerTimeline.planRangeFilter(listOf(LocalDate(2026, 10, 7)), day, zone)
        assertFalse(later.contains("!="))
    }

    private val range = GestureRange(540, 570)

    @Test
    fun aMoveLandsOnAQuarterKeepsItsLengthAndStaysInsideTheDay() {
        assertEquals(540, PlannerTimeline.snapMinutes(547.0))
        assertEquals(555, PlannerTimeline.snapMinutes(548.0))
        assertEquals(GestureRange(555, 585), PlannerTimeline.movedRange(range, 16.0))
        assertEquals(GestureRange(555, 585), PlannerTimeline.movedRange(GestureRange(550, 580), 0.0))
        assertEquals(GestureRange(0, 30), PlannerTimeline.movedRange(range, -2000.0))
        assertEquals(GestureRange(1410, 1440), PlannerTimeline.movedRange(range, 2000.0))
    }

    @Test
    fun theTopEdgeMovesTheStartOnly() {
        assertEquals(GestureRange(495, 570), PlannerTimeline.startEdgeRange(range, 500.0))
        assertEquals(GestureRange(555, 570), PlannerTimeline.startEdgeRange(range, 600.0))
        assertEquals(GestureRange(180, 900), PlannerTimeline.startEdgeRange(GestureRange(800, 900), 0.0))
    }

    @Test
    fun theBottomEdgeMovesTheEndOnlyWithinTwelveHoursAndTheMidnight() {
        assertEquals(GestureRange(540, 615), PlannerTimeline.endEdgeRange(range, 610.0))
        assertEquals(GestureRange(540, 555), PlannerTimeline.endEdgeRange(range, 100.0))
        assertEquals(GestureRange(60, 780), PlannerTimeline.endEdgeRange(GestureRange(60, 90), 1440.0))
        assertEquals(GestureRange(1400, 1440), PlannerTimeline.endEdgeRange(GestureRange(1400, 1420), 1440.0))
    }

    @Test
    fun aPastDayTakesNoBlockAndTodayOpensNearNow() = eachZone { zone ->
        assertFalse(PlannerTimeline.canPlaceOn(LocalDate(2026, 10, 5), day))
        assertTrue(PlannerTimeline.canPlaceOn(day, day))
        val at = { local: String -> Instant.parse(iso(local, zone)) }
        assertEquals(540, PlannerTimeline.openingMinute(day, at("2026-10-06T10:00:00"), day, 480, zone))
        assertEquals(480, PlannerTimeline.openingMinute(LocalDate(2026, 10, 7), at("2026-10-06T10:00:00"), day, 480, zone))
        assertEquals(0, PlannerTimeline.openingMinute(day, at("2026-10-06T00:20:00"), day, 480, zone))
    }
}
