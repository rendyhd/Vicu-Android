package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParsedToken
import com.rendyhd.vicu.util.parser.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The words on the chips of the new-task sheet. */
class TaskEntryChipsTest {

    private fun fields(parsedProject: String? = null, notFound: Boolean = false) = EntryFields(
        dueDate = "",
        dueSource = null,
        priority = 0,
        prioritySource = null,
        projectId = 1,
        projectSource = FieldSource.DEFAULT,
        parsedProjectName = parsedProject,
        projectNotFound = notFound,
        recurrence = RecurrenceValue.NONE,
        recurrenceSource = null,
    )

    private fun parsed(title: String, project: String): ParseResult {
        val start = title.indexOf('#')
        return ParseResult(
            title = title.substring(0, start).trim(),
            project = project,
            tokens = listOf(ParsedToken(TokenType.PROJECT, start, start + 1 + project.length, project, "#$project")),
        )
    }

    @Test
    fun `priorities are named in words`() {
        assertNull(entryPriorityName(0))
        assertEquals(listOf("Low", "Medium", "High", "Urgent"), (1..4).map { entryPriorityName(it) })
    }

    @Test
    fun `the project chip names the project the task goes to`() {
        assertEquals("Inbox", entryProjectChipLabel(fields(), "Inbox", "Call Ana", null))
        assertEquals("Personal", entryProjectChipLabel(fields("Personal"), "Personal", "Call Ana #Personal", null))
    }

    @Test
    fun `a project that does not exist says so once the word is finished`() {
        val unknown = fields("Nowhere", notFound = true)
        val finished = "Call Ana #Nowhere now"
        assertEquals("Nowhere (no such project)", entryProjectChipLabel(unknown, "Inbox", finished, parsed(finished, "Nowhere")))
    }

    @Test
    fun `while the caret is still in the project word the chip does not accuse it`() {
        val unknown = fields("Per", notFound = true)
        val typing = "Call Ana #Per"
        assertEquals("Per", entryProjectChipLabel(unknown, "Inbox", typing, parsed(typing, "Per")))
    }

    @Test
    fun `tags are listed up to two and counted after that, each once`() {
        assertNull(entryLabelsWords(emptyList(), emptyList()))
        assertEquals("errand", entryLabelsWords(listOf("errand"), emptyList()))
        assertEquals("errand, call", entryLabelsWords(listOf("errand"), listOf("Errand", "call")))
        assertEquals("3 tags", entryLabelsWords(listOf("a", "b"), listOf("c")))
    }
}
