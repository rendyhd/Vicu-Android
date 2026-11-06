package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.local.DailySummaryContent
import com.rendyhd.vicu.data.local.NotificationPrefs
import com.rendyhd.vicu.data.local.SnoozeEntry
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LocalNotificationPlannerTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val now = at("2026-10-12T07:00")

    private fun at(text: String): Instant = LocalDateTime.parse(text).toInstant(zone)

    private fun task(id: Long, title: String, vararg reminders: String, done: Boolean = false) = Task(
        id = id,
        title = title,
        done = done,
        reminders = reminders.map { TaskReminder(reminder = at(it).toString()) },
    )

    private val nothingDue = DailySummaryContent(DailySummary.Counts(0, 0, 0), emptyList())

    private fun input(
        prefs: NotificationPrefs = NotificationPrefs(),
        tasks: List<Task> = emptyList(),
        snoozes: List<SnoozeEntry> = emptyList(),
        routines: List<RoutineAlarmSpec> = emptyList(),
        summaryFor: suspend (ClockDay) -> DailySummaryContent = { nothingDue },
    ) = LocalNotificationPlanner.Input(now, zone, prefs, tasks, snoozes, routines, summaryFor)

    @Test
    fun plannerNudgeIsOneEachEveningAndTheFirstIsRequired() = runTest {
        val base = input()
        val plan = LocalNotificationPlanner.plan(
            LocalNotificationPlanner.Input(base.now, base.zone, base.prefs, base.tasksWithReminders, base.snoozes, base.routineAlarms, base.summaryFor, plannerNudge = "21:30"),
        )
        val nudges = plan.filter { it.kind == LocalNotification.Kind.PLANNER_NUDGE }
        assertEquals(LocalNotificationPlanner.SUMMARY_DAYS, nudges.size)
        assertEquals(at("2026-10-12T21:30"), nudges.first().trigger.at)
        assertEquals(NotificationBudget.Priority.REQUIRED, nudges.first().priority)
        assertEquals(NotificationBudget.Priority.NORMAL, nudges[1].priority)
        assertEquals(PlannerNudge.TITLE, nudges.first().title)
    }

    @Test
    fun plannerNudgeThatHasPassedTodayStartsTomorrow() = runTest {
        val late = at("2026-10-12T22:00")
        val base = input()
        val plan = LocalNotificationPlanner.plan(
            LocalNotificationPlanner.Input(late, base.zone, base.prefs, base.tasksWithReminders, base.snoozes, base.routineAlarms, base.summaryFor, plannerNudge = "21:30"),
        )
        assertEquals(at("2026-10-13T21:30"), plan.first { it.kind == LocalNotification.Kind.PLANNER_NUDGE }.trigger.at)
    }

    @Test
    fun noPlannerNudgeWithoutATime() = runTest {
        assertTrue(LocalNotificationPlanner.plan(input()).none { it.kind == LocalNotification.Kind.PLANNER_NUDGE })
    }

    private fun note(id: String, at: String, priority: NotificationBudget.Priority) = LocalNotification(
        id = id,
        kind = LocalNotification.Kind.REMINDER,
        title = id,
        body = "",
        trigger = LocalNotification.Trigger.Absolute(at(at)),
        sound = true,
        priority = priority,
        data = emptyMap(),
    )

    @Test
    fun theBudgetKeepsRequiredOnesThenTheSoonest() {
        val required = note("snooze", "2026-10-20T09:00", NotificationBudget.Priority.REQUIRED)
        val soon = note("soon", "2026-10-12T09:00", NotificationBudget.Priority.NORMAL)
        val later = note("later", "2026-10-13T09:00", NotificationBudget.Priority.NORMAL)
        val filler = note("filler", "2026-10-12T08:00", NotificationBudget.Priority.FILLER)
        assertEquals(listOf("soon", "snooze"), NotificationBudget.select(listOf(later, filler, required, soon), 2).map { it.id })
        // Filler only takes room that is left, and the result is in time order.
        assertEquals(listOf("filler", "soon", "later", "snooze"), NotificationBudget.select(listOf(later, filler, required, soon), 10).map { it.id })
    }

    @Test
    fun reminderNotificationsFollowTheAndroidTexts() = runTest {
        val plan = LocalNotificationPlanner.plan(
            input(tasks = listOf(task(5, "Call the plumber", "2026-10-12T09:00", "2026-10-12T06:00"), task(6, "Done already", "2026-10-12T10:00", done = true))),
        )
        val reminder = plan.single()
        assertEquals("reminder:5:0", reminder.id)
        assertEquals("Call the plumber", reminder.title)
        assertEquals("Task reminder", reminder.body)
        assertEquals(LocalNotification.Trigger.Absolute(at("2026-10-12T09:00")), reminder.trigger)
        assertEquals("5", reminder.data[LocalNotification.KEY_TASK_ID])
        assertEquals("REMINDER", reminder.data[LocalNotification.KEY_KIND])
    }

    @Test
    fun remindersAndSnoozesStopWhenTaskRemindersAreOff() = runTest {
        val plan = LocalNotificationPlanner.plan(
            input(
                prefs = NotificationPrefs(taskRemindersEnabled = false),
                tasks = listOf(task(5, "Call", "2026-10-12T09:00")),
                snoozes = listOf(SnoozeEntry(5, "Call", at("2026-10-12T07:15").toEpochMilliseconds())),
            ),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun aSnoozeThatRangIsNotSetAgain() = runTest {
        val plan = LocalNotificationPlanner.plan(
            input(
                snoozes = listOf(
                    SnoozeEntry(5, "Call", at("2026-10-12T06:50").toEpochMilliseconds()),
                    SnoozeEntry(6, "Pay", at("2026-10-12T07:10").toEpochMilliseconds()),
                ),
            ),
        )
        assertEquals(listOf("snooze:6"), plan.map { it.id })
        assertEquals(NotificationBudget.Priority.REQUIRED, plan.single().priority)
    }

    @Test
    fun routineRemindersFollowTheLocalClock() = runTest {
        val alarm = RoutineAlarmSpec("vit:2026-10-12:am", "vit", "Vitamins", "2026-10-12", "am", at("2026-10-12T08:30"), LocalDateTime.parse("2026-10-12T08:30"), followUp = true)
        val note = LocalNotificationPlanner.plan(input(routines = listOf(alarm))).single()
        assertEquals("routine:vit:2026-10-12:am:1", note.id)
        assertEquals("Still time for Vitamins", note.title)
        assertEquals("Tap Complete when you’ve done it", note.body)
        val trigger = assertIs<LocalNotification.Trigger.Local>(note.trigger)
        assertEquals(LocalDateTime.parse("2026-10-12T08:30"), trigger.dateTime)
        assertEquals("am", note.data[LocalNotification.KEY_SLOT_ID])
    }

    @Test
    fun summariesHaveNumbersForThreeDaysThenAGeneralText() = runTest {
        val read = mutableListOf<LocalDate>()
        val plan = LocalNotificationPlanner.plan(
            input(
                prefs = NotificationPrefs(dailySummaryEnabled = true, dailySummaryHour = 8, dailySummaryMinute = 0),
                summaryFor = { day ->
                    read += day.date
                    when (day.date) {
                        // Nothing due on the 13th: no summary that day.
                        LocalDate(2026, 10, 13) -> nothingDue
                        else -> DailySummaryContent(DailySummary.Counts(overdue = 1, dueToday = 4, dueTomorrow = 2), listOf("A", "B", "C"))
                    }
                },
            ),
        )
        assertEquals(listOf(LocalDate(2026, 10, 12), LocalDate(2026, 10, 13), LocalDate(2026, 10, 14)), read)
        assertEquals(
            listOf("12", "14", "15", "16", "17", "18").map { "summary:morning:2026-10-$it" },
            plan.map { it.id },
        )
        val first = plan.first()
        assertEquals("Daily Summary", first.title)
        assertEquals("1 overdue, 4 today, 2 tomorrow", first.subtitle)
        assertEquals("A\nB\nC\n+1 more today\n1 overdue tasks need attention\n2 due tomorrow", first.body)
        assertEquals(NotificationBudget.Priority.REQUIRED, first.priority)
        assertEquals(LocalNotification.Trigger.Local(LocalDateTime.parse("2026-10-12T08:00"), at("2026-10-12T08:00")), first.trigger)
        assertEquals(NotificationBudget.Priority.NORMAL, plan[1].priority)
        val general = plan.last()
        assertEquals(LocalNotificationPlanner.GENERAL_SUMMARY_BODY, general.body)
        assertEquals(NotificationBudget.Priority.FILLER, general.priority)
    }

    @Test
    fun aSummaryWhoseTimeHasPassedTodayStartsTomorrow() = runTest {
        val plan = LocalNotificationPlanner.plan(
            input(
                prefs = NotificationPrefs(afternoonSummaryEnabled = true, afternoonSummaryHour = 6, afternoonSummaryMinute = 30),
                summaryFor = { DailySummaryContent(DailySummary.Counts(0, 1, 0), listOf("A")) },
            ),
        )
        assertEquals("summary:afternoon:2026-10-13", plan.first().id)
        assertEquals("Afternoon Summary", plan.first().title)
    }

    @Test
    fun aBusyWeekStaysWithinTheBudgetAndKeepsTheSnoozeAndTheNextSummary() = runTest {
        val tasks = (1L..80L).map { task(it, "Task $it", (now + (it * 30).minutes).toLocalDateTimeString()) }
        val plan = LocalNotificationPlanner.plan(
            input(
                prefs = NotificationPrefs(dailySummaryEnabled = true, dailySummaryHour = 23, dailySummaryMinute = 0),
                tasks = tasks,
                snoozes = listOf(SnoozeEntry(99, "Later", at("2026-10-20T07:00").toEpochMilliseconds())),
                summaryFor = { DailySummaryContent(DailySummary.Counts(0, 1, 0), listOf("A")) },
            ),
        )
        assertEquals(NotificationBudget.IOS_CAPACITY, plan.size)
        assertTrue(plan.any { it.id == "snooze:99" })
        assertTrue(plan.any { it.id == "summary:morning:2026-10-12" })
        assertTrue(plan.any { it.id == "reminder:1:0" })
        assertTrue(plan.none { it.id == "reminder:80:0" })
    }

    private fun Instant.toLocalDateTimeString(): String = toLocalDateTime(zone).toString()
}
