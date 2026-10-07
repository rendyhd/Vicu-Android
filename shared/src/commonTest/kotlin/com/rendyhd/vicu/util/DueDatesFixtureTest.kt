package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.CrossAppFixture.date
import com.rendyhd.vicu.util.CrossAppFixture.fixture
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.CrossAppFixture.wall
import com.rendyhd.vicu.util.CrossAppFixture.zones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Runs the vectors of `test-fixtures/cross-app-semantics-v1.json` that the due-date policy owns
 * (`dueDates.display`, `dueDates.setters`, `weeks`, `smartLists`, `review`) in several time
 * zones. Custom lists (`customLists`) belong to the custom-list window work.
 */
class DueDatesFixtureTest {

    @Test
    fun `the fixture is the version this code implements`() {
        assertEquals(1, fixture.contractVersion)
    }

    @Test
    fun `dueDates display`() {
        for (zone in zones) for (v in fixture.dueDates.display) {
            assertEquals(
                v.dateOnly,
                DueDates.isDateOnly(local(v.local, zone), zone),
                "${v.local} in $zone",
            )
        }
    }

    @Test
    fun `dueDates setters`() {
        for (zone in zones) for (v in fixture.dueDates.setters) {
            val reference = local(v.reference, zone)
            val today = DueDates.localDateOf(reference, zone)
            val result: Instant = when (v.action) {
                "today" -> DueDates.today(today, zone)
                "tomorrow" -> DueDates.tomorrow(today, zone)
                "nextWeek" -> DueDates.nextWeek(today, zone)
                "pickDate" -> DueDates.pickDate(date(v.date!!), zone)
                "bang" -> DueDates.bang(today, zone)
                "postponeDays" -> DueDates.postponeDays(local(v.from!!, zone), v.days!!, today, zone)
                else -> error("Unknown setter action ${v.action}")
            }
            assertEquals(v.expect, wall(result, zone), "${v.action} from ${v.reference} in $zone")
        }
    }

    @Test
    fun weeks() {
        for (v in fixture.weeks) {
            val today = date(v.today)
            assertEquals(date(v.thisWeekEnd), DueDates.endOfWeek(today), "week end of ${v.today}")
            assertEquals(date(v.thisMonthEnd), DueDates.endOfMonth(today), "month end of ${v.today}")
            assertEquals(date(v.nextWeekStart), DueDates.nextWeekStart(today), "next week of ${v.today}")
            assertEquals(
                date(v.nextWeekStart),
                date(v.thisWeekEnd).plus(1, DateTimeUnit.DAY),
                "week end + 1 is the next week start (${v.today})",
            )
        }
    }

    @Test
    fun `smartLists classify by local date whatever the time of day`() {
        val open = fixture.tasks.filter { !it.done && it.due != null }
        for (zone in zones) for (v in fixture.smartLists) {
            val today = date(v.today)
            fun ids(bucket: DueDates.Bucket) =
                open.filter { DueDates.bucket(local(it.due!!, zone), today, zone) == bucket }.map { it.id }
            assertEquals(v.todayOverdue, ids(DueDates.Bucket.OVERDUE), "overdue on ${v.today} in $zone")
            assertEquals(v.todayToday, ids(DueDates.Bucket.TODAY), "today on ${v.today} in $zone")
            assertEquals(v.upcoming, ids(DueDates.Bucket.UPCOMING), "upcoming on ${v.today} in $zone")
        }
    }

    /**
     * The Room queries compare ISO strings with `dueDate < :startOfTomorrow` (Today) and
     * `dueDate >= :startOfTomorrow` (Upcoming). This runs that comparison on the fixture tasks as
     * strings, exactly as stored, so the boundary format and the operators are covered too.
     */
    @Test
    fun `the Today and Upcoming query boundary agrees with the smart lists`() {
        val open = fixture.tasks.filter { !it.done && it.due != null }
        for (zone in zones) for (v in fixture.smartLists) {
            val boundary = DueDates.startOfDay(date(v.today).plus(1, DateTimeUnit.DAY), zone).toString()
            fun stored(id: Long) = local(open.first { it.id == id }.due!!, zone).toString()
            val dueBeforeTomorrow = open.filter { stored(it.id) < boundary }.map { it.id }
            val dueFromTomorrow = open.filter { stored(it.id) >= boundary }.map { it.id }
            assertEquals((v.todayOverdue + v.todayToday).sorted(), dueBeforeTomorrow.sorted(), "Today query on ${v.today} in $zone")
            assertEquals(v.upcoming, dueFromTomorrow.sorted(), "Upcoming query on ${v.today} in $zone")
        }
    }

    @Test
    fun `review status matches the vectors`() {
        for (v in fixture.review) {
            val meta = ReviewMetadata(
                state = when (v.meta.state) {
                    "never" -> ReviewState.NEVER
                    "excluded" -> ReviewState.EXCLUDED
                    "reviewed" -> ReviewState.REVIEWED
                    else -> error("Unknown review state ${v.meta.state}")
                },
                lastReviewedAt = v.meta.lastReviewedAt,
                cadenceDaysOverride = v.meta.cadenceDaysOverride,
            )
            val status = ReviewMetadata.computeStatus(meta, v.defaultCadence, date(v.today))
            assertEquals(v.expect.isOverdue, status.isOverdue, "${v.name}: isOverdue")
            assertEquals(v.expect.daysSince, status.daysSinceReviewed, "${v.name}: daysSince")
            assertEquals(v.expect.daysUntil, status.daysUntilDue, "${v.name}: daysUntil")
            assertEquals(v.expect.next, status.nextReviewAt?.toString(), "${v.name}: next")
            if (v.expect.next == null) assertNull(status.nextReviewAt, "${v.name}: no next date")
        }
    }

    @Test
    fun `review status takes today from the clock's local day in every zone`() {
        // Late in the evening local time it is already the next day in UTC for the Americas, and
        // early morning here is still the previous UTC day for Auckland: the clock must still
        // answer with the local date.
        val scope = CoroutineScope(Job())
        for (zone in zones) for (v in fixture.review) {
            for (clock in listOf("00:30:00", "10:00:00", "23:30:00")) {
                val now = local("${v.today}T$clock", zone)
                val dayClock = DayClock(scope, FixedTimeSource(now, zone), ticking = false)
                assertEquals(date(v.today), dayClock.day.value.date, "${v.today} $clock in $zone")
                val meta = ReviewMetadata(
                    state = when (v.meta.state) {
                        "excluded" -> ReviewState.EXCLUDED
                        "reviewed" -> ReviewState.REVIEWED
                        else -> ReviewState.NEVER
                    },
                    lastReviewedAt = v.meta.lastReviewedAt,
                    cadenceDaysOverride = v.meta.cadenceDaysOverride,
                )
                val status = ReviewMetadata.computeStatus(meta, v.defaultCadence, dayClock.day.value.date)
                assertEquals(v.expect.isOverdue, status.isOverdue, "${v.name} $clock in $zone")
                assertEquals(v.expect.daysUntil, status.daysUntilDue, "${v.name} $clock in $zone")
            }
        }
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `the zones in use really differ`() {
        // Guards against a time zone database that silently maps every id to UTC.
        val offsets = zones.map { zone ->
            local("2026-10-06T12:00:00", zone).toLocalDateTime(TimeZone.UTC).hour
        }
        assertEquals(listOf(10, 16, 23), offsets)
    }
}
