package com.rendyhd.vicu.util.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskParserTest {

    private val todoist = ParserConfig(enabled = true, syntaxMode = SyntaxMode.TODOIST)
    private val vikunja = ParserConfig(enabled = true, syntaxMode = SyntaxMode.VIKUNJA)
    private val disabled = ParserConfig(enabled = false, syntaxMode = SyntaxMode.TODOIST)

    // ─── Labels ───────────────────────────────────────────────

    @Test
    fun `extracts single label in Todoist mode`() {
        val r = TaskParser.parse("buy groceries @shopping", todoist)
        assertEquals(listOf("shopping"), r.labels)
        assertEquals("buy groceries", r.title)
    }

    @Test
    fun `extracts multiple labels in Todoist mode`() {
        val r = TaskParser.parse("task @work @urgent", todoist)
        assertEquals(listOf("work", "urgent"), r.labels)
        assertEquals("task", r.title)
    }

    @Test
    fun `extracts labels with Vikunja prefix star`() {
        val r = TaskParser.parse("task *work *urgent", vikunja)
        assertEquals(listOf("work", "urgent"), r.labels)
        assertEquals("task", r.title)
    }

    @Test
    fun `ignores at in Vikunja mode`() {
        val r = TaskParser.parse("email @john", vikunja)
        assertEquals(emptyList<String>(), r.labels)
        assertEquals("email @john", r.title)
    }

    @Test
    fun `handles quoted labels`() {
        val r = TaskParser.parse("""task @"multi word"""", todoist)
        assertEquals(listOf("multi word"), r.labels)
        assertEquals("task", r.title)
    }

    @Test
    fun `ignores dangling at`() {
        val r = TaskParser.parse("dangling @", todoist)
        assertEquals(emptyList<String>(), r.labels)
        assertEquals("dangling @", r.title)
    }

    @Test
    fun `handles label with hyphens`() {
        val r = TaskParser.parse("task @follow-up", todoist)
        assertEquals(listOf("follow-up"), r.labels)
        assertEquals("task", r.title)
    }

    // ─── Projects ─────────────────────────────────────────────

    @Test
    fun `extracts project in Todoist mode`() {
        val r = TaskParser.parse("buy groceries #shopping", todoist)
        assertEquals("shopping", r.project)
        assertEquals("buy groceries", r.title)
    }

    @Test
    fun `ignores hash followed by number (issue reference)`() {
        val r = TaskParser.parse("review PR #142", todoist)
        assertNull(r.project)
        assertEquals("review PR #142", r.title)
    }

    @Test
    fun `extracts project with Vikunja prefix plus`() {
        val r = TaskParser.parse("task +work", vikunja)
        assertEquals("work", r.project)
        assertEquals("task", r.title)
    }

    @Test
    fun `handles quoted project`() {
        val r = TaskParser.parse("""task #"my project"""", todoist)
        assertEquals("my project", r.project)
        assertEquals("task", r.title)
    }

    @Test
    fun `takes only first project`() {
        val r = TaskParser.parse("task #work #personal", todoist)
        assertEquals("work", r.project)
        assertEquals("task #personal", r.title)
    }

    // ─── Priority ─────────────────────────────────────────────

    @Test
    fun `extracts Todoist p1 as Vikunja 4 (urgent)`() {
        val r = TaskParser.parse("urgent task p1", todoist)
        assertEquals(4, r.priority)
        assertEquals("urgent task", r.title)
    }

    @Test
    fun `extracts Todoist p3 as Vikunja 2 (medium)`() {
        val r = TaskParser.parse("task p3", todoist)
        assertEquals(2, r.priority)
        assertEquals("task", r.title)
    }

    @Test
    fun `does not match p2p (word boundary)`() {
        val r = TaskParser.parse("p3 talk about p2p", todoist)
        assertEquals(2, r.priority)
        assertEquals("talk about p2p", r.title)
    }

    @Test
    fun `extracts Vikunja bang3 as priority 3`() {
        val r = TaskParser.parse("task !3", vikunja)
        assertEquals(3, r.priority)
        assertEquals("task", r.title)
    }

    @Test
    fun `does not treat bang4 as priority in Todoist mode`() {
        val r = TaskParser.parse("task !4", todoist)
        assertNull(r.priority)
        assertEquals("task !4", r.title)
    }

    @Test
    fun `extracts bang-urgent word priority in both modes`() {
        val r = TaskParser.parse("task !urgent", todoist)
        assertEquals(4, r.priority)
        assertEquals("task", r.title)
    }

    @Test
    fun `extracts bang-low word priority`() {
        val r = TaskParser.parse("task !low", vikunja)
        assertEquals(1, r.priority)
        assertEquals("task", r.title)
    }

    // ─── Recurrence ───────────────────────────────────────────

    @Test
    fun `extracts every 2 weeks`() {
        val r = TaskParser.parse("standup every 2 weeks", todoist)
        assertEquals(ParsedRecurrence(2, RecurrenceUnit.WEEK), r.recurrence)
        assertEquals("standup", r.title)
    }

    @Test
    fun `extracts every day`() {
        val r = TaskParser.parse("journal every day", todoist)
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.DAY), r.recurrence)
        assertEquals("journal", r.title)
    }

    @Test
    fun `does NOT extract weekly from weekly standup`() {
        val r = TaskParser.parse("weekly standup", todoist)
        assertNull(r.recurrence)
        assertEquals("weekly standup", r.title)
    }

    @Test
    fun `extracts standalone daily when it is the only text`() {
        val r = TaskParser.parse("daily", todoist)
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.DAY), r.recurrence)
        assertEquals("", r.title)
    }

    @Test
    fun `extracts monthly as standalone with other tokens consumed`() {
        val r = TaskParser.parse("monthly @work", todoist)
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.MONTH), r.recurrence)
        assertEquals(listOf("work"), r.labels)
        assertEquals("", r.title)
    }

    // ─── Dates ────────────────────────────────────────────────

    @Test
    fun `extracts tomorrow as a date`() {
        val r = TaskParser.parse("buy groceries tomorrow", todoist)
        assertNotNull(r.dueDate)
        val tomorrow = LocalDate.now().plusDays(1)
        assertEquals(tomorrow.dayOfMonth, r.dueDate!!.dayOfMonth)
        assertEquals("buy groceries", r.title)
    }

    @Test
    fun `extracts tomorrow 3pm`() {
        val r = TaskParser.parse("buy groceries tomorrow 3pm", todoist)
        assertNotNull(r.dueDate)
        assertEquals(15, r.dueDate!!.hour)
        assertEquals("buy groceries", r.title)
    }

    @Test
    fun `does not extract date from consumed regions`() {
        // "monday" in a label shouldn't be parsed as a date
        val r = TaskParser.parse("task @monday", todoist)
        assertEquals(listOf("monday"), r.labels)
        assertNull(r.dueDate)
        assertEquals("task", r.title)
    }

    // ─── Bang Today (! → today) ──────────────────────────────

    @Test
    fun `converts trailing bang to today`() {
        val r = extractBangToday("call dentist !")
        assertNotNull(r.dueDate)
        assertEquals(0, r.dueDate!!.hour)
        assertEquals("call dentist", r.title)
    }

    @Test
    fun `converts trailing bang without space to today`() {
        val r = extractBangToday("call dentist!")
        assertNotNull(r.dueDate)
        assertEquals("call dentist", r.title)
    }

    @Test
    fun `returns null for no trailing bang`() {
        val r = extractBangToday("call dentist")
        assertNull(r.dueDate)
        assertEquals("call dentist", r.title)
    }

    @Test
    fun `does not treat bang-word as bang-today`() {
        val r = extractBangToday("task !urgent")
        assertNull(r.dueDate)
        assertEquals("task !urgent", r.title)
    }

    // ─── Disabled Parser ──────────────────────────────────────

    @Test
    fun `returns raw input as title when disabled`() {
        val r = TaskParser.parse("buy groceries tomorrow @shopping p1", disabled)
        assertEquals("buy groceries tomorrow @shopping p1", r.title)
        assertNull(r.dueDate)
        assertNull(r.priority)
        assertEquals(emptyList<String>(), r.labels)
        assertNull(r.project)
        assertNull(r.recurrence)
        assertTrue(r.tokens.isEmpty())
    }

    // ─── Suppress Types ───────────────────────────────────────

    @Test
    fun `suppresses date extraction when specified`() {
        val cfg = ParserConfig(enabled = true, syntaxMode = SyntaxMode.TODOIST, suppressTypes = setOf(TokenType.DATE))
        val r = TaskParser.parse("task tomorrow @work p1", cfg)
        assertNull(r.dueDate)
        assertEquals(listOf("work"), r.labels)
        assertEquals(4, r.priority)
        assertEquals("task tomorrow", r.title)
    }

    @Test
    fun `suppresses labels when specified`() {
        val cfg = ParserConfig(enabled = true, syntaxMode = SyntaxMode.TODOIST, suppressTypes = setOf(TokenType.LABEL))
        val r = TaskParser.parse("task @work p1", cfg)
        assertEquals(emptyList<String>(), r.labels)
        assertEquals(4, r.priority)
        assertEquals("task @work", r.title)
    }

    // ─── Combined Parsing ─────────────────────────────────────

    @Test
    fun `parses full Todoist-style input`() {
        val r = TaskParser.parse("buy groceries tomorrow 3pm #shopping @errands p3", todoist)
        assertEquals("shopping", r.project)
        assertEquals(listOf("errands"), r.labels)
        assertEquals(2, r.priority) // p3 → Vikunja 2
        assertNotNull(r.dueDate)
        assertEquals("buy groceries", r.title)
    }

    @Test
    fun `parses full Vikunja-style input`() {
        val r = TaskParser.parse("buy groceries tomorrow +shopping *errands !3", vikunja)
        assertEquals("shopping", r.project)
        assertEquals(listOf("errands"), r.labels)
        assertEquals(3, r.priority)
        assertNotNull(r.dueDate)
        assertEquals("buy groceries", r.title)
    }

    @Test
    fun `handles empty input`() {
        val r = TaskParser.parse("", todoist)
        assertEquals("", r.title)
        assertTrue(r.tokens.isEmpty())
    }

    @Test
    fun `handles whitespace-only input`() {
        val r = TaskParser.parse("   ", todoist)
        assertEquals("   ", r.title)
        assertTrue(r.tokens.isEmpty())
    }

    // ─── Edge Cases ───────────────────────────────────────────

    @Test
    fun `task bang4 in Vikunja = priority 4, NOT today shortcut`() {
        val r = TaskParser.parse("task !4", vikunja)
        assertEquals(4, r.priority)
        assertEquals("task", r.title)
    }

    @Test
    fun `task bang4 in Todoist = bang4 stays in title`() {
        val r = TaskParser.parse("task !4", todoist)
        assertNull(r.priority)
        assertEquals("task !4", r.title)
    }

    @Test
    fun `review PR hash142 = no project, hash142 stays in title`() {
        val r = TaskParser.parse("review PR #142", todoist)
        assertNull(r.project)
        assertEquals("review PR #142", r.title)
    }

    @Test
    fun `p3 talk about p2p = priority 2, p2p in title`() {
        val r = TaskParser.parse("p3 talk about p2p", todoist)
        assertEquals(2, r.priority) // p3 → Vikunja 2
        assertEquals("talk about p2p", r.title)
    }

    @Test
    fun `weekly standup = no recurrence`() {
        val r = TaskParser.parse("weekly standup", todoist)
        assertNull(r.recurrence)
        assertEquals("weekly standup", r.title)
    }

    @Test
    fun `email at-john in Vikunja = no labels, at-john in title`() {
        val r = TaskParser.parse("email @john", vikunja)
        assertEquals(emptyList<String>(), r.labels)
        assertEquals("email @john", r.title)
    }

    @Test
    fun `dangling at in Todoist = no labels, at in title`() {
        val r = TaskParser.parse("dangling @", todoist)
        assertEquals(emptyList<String>(), r.labels)
        assertEquals("dangling @", r.title)
    }
}
