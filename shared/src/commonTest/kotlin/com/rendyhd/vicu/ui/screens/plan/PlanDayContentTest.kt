package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.PlannerHours
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class PlanDayContentTest {

    private val zone = TimeZone.of("Pacific/Auckland")
    private val day = LocalDate(2026, 10, 6)
    private val hours = PlannerHours("09:00", "17:00")

    private fun at(local: String): Instant = LocalDateTime.parse(local).toInstant(zone)
    private fun block(id: Long, start: String, end: String, done: Boolean = false, due: String = "") = Task(
        id = id,
        title = "Task $id",
        projectId = 1,
        startDate = at(start).toString(),
        endDate = at(end).toString(),
        dueDate = due,
        done = done,
    )

    @Test
    fun aHandleWithFreeRoomHasTheFullTouchSize() {
        val alone = handleReach(height = 28.dp, roomBelow = 0.dp, nextHeight = null)
        assertEquals(48.dp, alone.height)
        assertEquals(12.dp, alone.inside)
        val roomy = handleReach(height = 28.dp, roomBelow = 56.dp, nextHeight = 28.dp)
        assertEquals(48.dp, roomy.height)
    }

    @Test
    fun aHandleInAStackStopsShortOfBothMiddles() {
        val stacked = handleReach(height = 28.dp, roomBelow = 0.dp, nextHeight = 28.dp)
        assertTrue(stacked.inside < 14.dp, "this block's middle stays free")
        assertTrue(stacked.below < 14.dp, "the next block's middle stays free")
        val tiny = handleReach(height = 14.dp, roomBelow = 0.dp, nextHeight = 14.dp)
        assertTrue(tiny.inside < 7.dp && tiny.below < 7.dp, "so it does in a 15-minute stack")
    }

    @Test
    fun aTallBlocksHandleLiesHalfInside() {
        val tall = handleReach(height = 112.dp, roomBelow = 0.dp, nextHeight = 28.dp)
        assertEquals(24.dp, tall.inside)
        assertEquals(12.dp, tall.below)
    }

    @Test
    fun aDayReadsItsBlocksMarkersAndTasksWithoutATime() {
        val tasks = listOf(
            block(1, "2026-10-06T09:00:00", "2026-10-06T09:45:00"),
            block(2, "2026-10-06T10:00:00", "2026-10-06T10:30:00", done = true),
            Task(id = 3, title = "Marker", projectId = 1, dueDate = at("2026-10-06T14:00:00").toString()),
            Task(id = 4, title = "Date only", projectId = 1, dueDate = DueDates.dateOnlyDue(day, zone).toString()),
        )
        val content = planDayContent(tasks, day, at("2026-10-06T08:00:00"), hours, zone)
        assertEquals(listOf(1L, 2L), content.blocks.map { it.task.id })
        assertEquals(listOf(3L), content.markers.map { it.task.id })
        assertEquals(listOf(4L), content.withoutTime.map { it.id })
        // Done blocks are drawn but not counted, and a marker takes no time.
        assertEquals(45, content.plannedMinutes)
        assertEquals(8 * 60 - 45, content.freeMinutes)
    }

    @Test
    fun theStripCoversSevenDaysWithTheLoadOfTheirWorkHours() {
        val tasks = listOf(
            block(1, "2026-10-06T09:00:00", "2026-10-06T13:00:00"),
            block(2, "2026-10-08T08:00:00", "2026-10-08T20:00:00"),
            block(3, "2026-10-09T09:00:00", "2026-10-09T10:00:00", done = true),
        )
        val strip = planStrip(day, tasks, hours, zone)
        assertEquals(PLAN_STRIP_DAYS, strip.size)
        assertEquals(day, strip.first().day)
        assertEquals(LocalDate(2026, 10, 12), strip.last().day)
        assertEquals(240, strip[0].plannedMinutes)
        assertEquals(0.5f, strip[0].load)
        // Twelve hours against eight of work: the bar stops at full.
        assertEquals(1f, strip[2].load)
        assertEquals(0, strip[3].plannedMinutes)
    }

    @Test
    fun theTotalsReadLikeTheDesktops() {
        assertEquals("45m planned · 4h 20m free", planTotalsText(45, 260))
        assertEquals("0m planned · 8h free", planTotalsText(0, 480))
    }

    @Test
    fun theDrawerListsEveryProjectWithItsSubprojectsAndWithoutTheInboxOrArchived() {
        val rows = PlanViewModel.projectRows(
            listOf(
                Project(id = 1, title = "Inbox", position = 1.0),
                Project(id = 2, title = "Home", position = 2.0),
                Project(id = 3, title = "Garden", parentProjectId = 2, position = 1.0),
                Project(id = 4, title = "Beds", parentProjectId = 3, position = 1.0),
                Project(id = 5, title = "Work", position = 3.0),
                Project(id = 6, title = "Old", isArchived = true, position = 4.0),
            ),
            inboxId = 1,
        )
        assertEquals(
            listOf("Home" to 0, "Garden" to 1, "Beds" to 2, "Work" to 0),
            rows.map { it.project.title to it.depth },
        )
    }

    @Test
    fun aFingerNearAnEdgeScrollsTheGridAndTheMiddleLeavesItAlone() {
        assertEquals(0f, autoScrollDelta(300f, 600f, 50f))
        assertTrue(autoScrollDelta(10f, 600f, 50f) < 0f)
        assertTrue(autoScrollDelta(590f, 600f, 50f) > 0f)
        assertEquals(-PLAN_AUTO_SCROLL_SPEED, autoScrollDelta(-20f, 600f, 50f))
        assertEquals(PLAN_AUTO_SCROLL_SPEED, autoScrollDelta(640f, 600f, 50f))
    }
}
