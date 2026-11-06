package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.createApiJson
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.screens.taskdetail.applyTaskEditShortcuts
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.TextTaskCreator
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TaskParser
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/** A time block from quick add reaches the task (contract section 5.4). */
class TaskEntryBlockTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    // Tuesday 6 October 2026, 10:00.
    private val now = LocalDateTime(2026, 10, 6, 10, 0)
    private val config = ParserConfig(locale = "en-GB")
    private val start = LocalDateTime(2026, 10, 7, 15, 0).toInstant(zone).toString()
    private val end = LocalDateTime(2026, 10, 7, 15, 30).toInstant(zone).toString()
    private val dateOnly = DueDates.dateOnlyDue(LocalDate(2026, 10, 7), zone).toString()

    private fun parsed(text: String) = TaskParser.parse(text, config, now, zone)
    private fun state(text: String) = TaskEntryUiState(title = text, parseResult = parsed(text), parserConfig = config)

    @Test
    fun theFieldsHoldTheBlockAndADateOnlyDue() {
        val fields = resolveEntryFields(state("Call tomorrow at 3pm for 30m"), zone)
        assertEquals(dateOnly, fields.dueDate)
        assertEquals(FieldSource.TEXT, fields.dueSource)
        assertEquals(start, fields.blockStart)
        assertEquals(end, fields.blockEnd)
    }

    @Test
    fun aDatePickedByHandReplacesTheBlock() {
        val picked = DueDates.dateOnlyDue(LocalDate(2026, 10, 9), zone).toString()
        val fields = resolveEntryFields(
            state("Call tomorrow at 3pm for 30m").copy(dueDate = picked, dueDateIsManual = true),
            zone,
        )
        assertEquals(picked, fields.dueDate)
        assertEquals("", fields.blockStart)
        assertEquals("", fields.blockEnd)
    }

    @Test
    fun aDueTimeHasNoBlock() {
        val fields = resolveEntryFields(state("Call tomorrow at 3pm"), zone)
        assertEquals(start, fields.dueDate)
        assertEquals("", fields.blockStart)
    }

    @Test
    fun theTitleEditorWritesTheBlock() {
        val task = Task(id = 1, title = "x", projectId = 2)
        val updated = applyTaskEditShortcuts(task, parsed("Call tomorrow at 3pm for 30m"), emptyList(), zone).task
        assertEquals("Call", updated.title)
        assertEquals(dateOnly, updated.dueDate)
        assertEquals(start, updated.startDate)
        assertEquals(end, updated.endDate)
    }

    @Test
    fun theCreateSendsBothEnds() {
        val dto = with(TaskMapper(createApiJson())) { Task(id = 0, title = "Call", startDate = start, endDate = end).toCreateDto() }
        assertEquals(start, dto.startDate)
        assertEquals(end, dto.endDate)
    }

    @Test
    fun theConfirmationSaysItsTimes() {
        val day = ClockDay(LocalDate(2026, 10, 6), zone)
        val format = DateDisplayFormat("en-GB", hour12 = false)
        assertEquals(
            "Added “Call”, tomorrow, 15:00–15:30",
            TextTaskCreator.confirmation("Call", dateOnly, null, day, format, block = parsed("Call tomorrow at 3pm for 30m").block),
        )
        assertEquals(
            "Added “Call”, 15:00–15:30",
            TextTaskCreator.confirmation("Call", dateOnly, null, day, format, block = parsed("Call at 3pm for 30m").block),
        )
    }
}
