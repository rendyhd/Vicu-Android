package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.screens.taskdetail.applyTaskEditShortcuts
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.pro.Person
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Quick add with Pro's phrases (cross-app semantics 5.5): read and written only while Pro is on. */
class ProCaptureTest {

    private val zone = TimeZone.of("Pacific/Auckland")

    /** Thursday 12 November 2026, 10:00 in Auckland. */
    private val now = LocalDateTime.parse("2026-11-12T10:00")
    private val jan = Person(id = 31, title = "Jan de Vries")
    private val on = ProCapture(on = true, people = listOf(jan))

    private fun parse(text: String, pro: ProCapture): ParseResult =
        TaskParser.parse(text, pro.config(ParserConfig()), reference = now, zone = zone, people = pro.parserPeople)

    @Test
    fun `off reads the text as before and writes nothing`() {
        val result = parse("Jan: send the plan starts monday !flag", ProCapture.OFF)

        assertFalse(ProCapture.OFF.config(ParserConfig()).pro)
        assertTrue("starts" in result.title && "!flag" in result.title && result.title.startsWith("Jan:"), result.title)
        val task = ProCapture.OFF.applyToNew(Task(id = 0, title = "x"), result, zone)
        assertEquals("", task.startDate)
        assertFalse(task.isFavorite)
        assertNull(ProCapture.OFF.personLabelOf(result))
    }

    @Test
    fun `on, a start date, a flag and a person`() {
        val result = parse("Jan: send the plan starts monday !flag", on)

        assertEquals("send the plan", result.title)
        val task = on.applyToNew(Task(id = 0, title = result.title), result, zone)
        // Monday 16 November, local midnight in Auckland.
        assertEquals("2026-11-15T11:00:00Z", task.startDate)
        assertEquals("", task.endDate)
        assertTrue(task.isFavorite)
        assertEquals(31L, on.personLabelOf(result))
    }

    @Test
    fun `a block holds the start, so no lone start date is written`() {
        val result = parse("Write report starts monday at 9am for 30m", on)
        val blocked = Task(id = 0, title = "x", startDate = "2026-11-15T20:00:00Z", endDate = "2026-11-15T20:30:00Z")

        assertEquals("2026-11-15T20:00:00Z", on.applyToNew(blocked, result, zone).startDate)
        // A start date read alone does not go over a block chosen by hand either.
        val lone = parse("Write report starts monday", on)
        assertEquals("2026-11-15T20:00:00Z", on.applyToNew(blocked, lone, zone).startDate)
    }

    @Test
    fun `a person label that is no longer a person is not added`() {
        val result = parse("Jan: send the plan", on)
        assertNull(ProCapture(on = true).personLabelOf(result))
    }

    @Test
    fun `editing a title sets the start date and flag and adds the person`() {
        val original = Task(id = 7, title = "old")
        val result = parse("Jan: send the plan starts monday !flag", on)

        val edited = applyTaskEditShortcuts(original, result, emptyList(), zone, pro = on)

        assertEquals("2026-11-15T11:00:00Z", edited.task.startDate)
        assertTrue(edited.task.isFavorite)
        assertEquals(listOf("Jan de Vries"), edited.labelNames)

        // Off: the same text changes no field.
        val off = applyTaskEditShortcuts(original, parse("Jan: send the plan starts monday !flag", ProCapture.OFF), emptyList(), zone)
        assertEquals("", off.task.startDate)
        assertFalse(off.task.isFavorite)
        assertTrue(off.labelNames.isEmpty())
    }

    @Test
    fun `a person makes their promise, for a person yours, with the entry sheet's words`() {
        val theirs = on.promiseOf(parse("Jan: send the plan", on), words = "  I'll send it Friday ", doneWhen = "")
        assertEquals("""{"dir":"theirs","person":31,"words":"I'll send it Friday"}""", theirs.toString())

        val mine = on.promiseOf(parse("for Jan: review the deck", on), words = null, doneWhen = "Jan says it reads well")
        assertEquals("""{"dir":"mine","person":31,"done_when":"Jan says it reads well"}""", mine.toString())

        // No person, Pro off, or a label that is no longer a person: no promise.
        assertNull(on.promiseOf(parse("send the plan", on)))
        assertNull(ProCapture.OFF.promiseOf(parse("Jan: send the plan", ProCapture.OFF)))
        assertNull(ProCapture(on = true).promiseOf(parse("Jan: send the plan", on)))
    }

    @Test
    fun `an estimate is read only while Pro is on`() {
        assertEquals(90, on.estimateOf(parse("Write the report ~1h30", on)))
        assertNull(on.estimateOf(parse("Write the report", on)))
        assertNull(ProCapture.OFF.estimateOf(parse("Write the report ~1h30", ProCapture.OFF)))
    }
}
