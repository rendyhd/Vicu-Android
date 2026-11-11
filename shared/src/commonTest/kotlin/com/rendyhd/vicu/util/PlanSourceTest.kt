package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The drawer's lists, the same cases as the desktop's `plan-source.test.ts`. */
class PlanSourceTest {

    @Test
    fun aStoredSourceIsReadAndAnythingElseIsTheFirstList() {
        assertEquals(PlanSource.Inbox, PlanSource.read("inbox"))
        assertEquals(PlanSource.NextSeven, PlanSource.read("next7"))
        assertEquals(PlanSource.NoDate, PlanSource.read("nodate"))
        assertEquals(PlanSource.Project(12), PlanSource.read("project:12"))
        listOf(null, "", "project:0", "project:x", "someday").forEach {
            assertEquals(PlanSource.Day, PlanSource.read(it), "read $it")
        }
    }

    @Test
    fun aKeyReadsBackAsTheSameSource() {
        listOf(PlanSource.Day, PlanSource.Inbox, PlanSource.NextSeven, PlanSource.NoDate, PlanSource.Project(7)).forEach {
            assertEquals(it, PlanSource.read(it.key))
        }
    }

    @Test
    fun aSourceKnowsItsProject() {
        assertEquals(3L, PlanSource.Inbox.projectId(3))
        assertNull(PlanSource.Inbox.projectId(0))
        assertNull(PlanSource.Inbox.projectId(null))
        assertEquals(12L, PlanSource.Project(12).projectId(3))
        assertNull(PlanSource.Day.projectId(3))
        assertNull(PlanSource.NextSeven.projectId(3))
    }

    @Test
    fun theListsAreNamedTheFirstAfterTheSelectedDay() {
        assertEquals(
            listOf("Today", "Inbox", "Next 7 days", "No date"),
            PlanSource.lists("Today").map { it.second },
        )
    }

    @Test
    fun nextSevenDaysIsAfterTodayAndAtMostSevenDaysOn() {
        for (zone in listOf(TimeZone.of("Pacific/Auckland"), TimeZone.of("America/New_York"))) {
            val today = LocalDate(2026, 10, 6)
            fun due(date: String) = DueDates.dateOnlyDue(LocalDate.parse(date), zone).toString()
            assertFalse(PlanSource.inNextSevenDays(due("2026-10-06"), today, zone))
            assertTrue(PlanSource.inNextSevenDays(due("2026-10-07"), today, zone))
            assertTrue(PlanSource.inNextSevenDays(due("2026-10-13"), today, zone))
            assertFalse(PlanSource.inNextSevenDays(due("2026-10-14"), today, zone))
            assertFalse(PlanSource.inNextSevenDays("0001-01-01T00:00:00Z", today, zone))
            assertFalse(PlanSource.inNextSevenDays("", today, zone))
        }
    }

    @Test
    fun theFilterMatchesTheTitleInAnyCase() {
        assertTrue(PlanSource.matchesTitleFilter("Call the Dentist", "dent"))
        assertTrue(PlanSource.matchesTitleFilter("Call the Dentist", "  "))
        assertFalse(PlanSource.matchesTitleFilter("Call the Dentist", "plumber"))
    }
}
