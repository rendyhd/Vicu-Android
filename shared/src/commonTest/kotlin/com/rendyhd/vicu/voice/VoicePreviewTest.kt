package com.rendyhd.vicu.voice

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.location.SavedPlace
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The voice card reads the words the way the task will be added. */
class VoicePreviewTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    // Tuesday 20 October 2026, 10:00.
    private val now = LocalDateTime(2026, 10, 20, 10, 0)
    private val day = ClockDay(LocalDate(2026, 10, 20), zone)
    private val projects = listOf(Project(id = 1, title = "Inbox"), Project(id = 2, title = "Work"))
    private val format = DateDisplayFormat.system(is24Hour = true)

    private fun preview(text: String, config: ParserConfig = ParserConfig(), defaults: TaskDefaults = TaskDefaults()) =
        voicePreview(text, config, now, day, projects, inboxId = 1, format = format, defaults = defaults)

    @Test
    fun theDateAndProjectBecomePills() {
        val result = preview("call mom tomorrow #Work")
        assertEquals(listOf(TokenType.DATE, TokenType.PROJECT), result.pills.map { it.type })
        assertEquals("Work", result.pills[1].label)
        assertEquals(setOf(TokenType.DATE, TokenType.PROJECT), result.tokens.map { it.type }.toSet())
    }

    @Test
    fun aTimeBlockHasItsOwnPill() {
        val result = preview("call mom tomorrow at 3pm for 30 minutes")
        assertEquals(listOf(TokenType.DATE, TokenType.LENGTH), result.pills.map { it.type })
        assertEquals(setOf(TokenType.DATE, TokenType.LENGTH), result.tokens.map { it.type }.toSet())
        assertTrue(result.pills[1].label.contains('–'), result.pills[1].label)
    }

    @Test
    fun aProjectThatDoesNotExistIsNotHighlighted() {
        val result = preview("call mom #Garden")
        assertTrue(result.pills.none { it.type == TokenType.PROJECT })
        assertTrue(result.tokens.none { it.type == TokenType.PROJECT })
    }

    @Test
    fun plainWordsAndParsingOffShowNothing() {
        assertEquals(VoicePreview.EMPTY, preview("water the plants"))
        assertEquals(VoicePreview.EMPTY, preview("call mom tomorrow", ParserConfig(enabled = false)))
        assertEquals(VoicePreview.EMPTY, preview("  "))
    }

    @Test
    fun theListsProjectAndDateShowBeforeAnythingIsSaid() {
        val today = DueDates.today(LocalDate(2026, 10, 20), zone).toString()
        val result = preview("", defaults = TaskDefaults(projectId = 2, dueDate = today))
        assertEquals(listOf(TokenType.DATE, TokenType.PROJECT), result.pills.map { it.type })
        assertEquals("Work", result.pills[1].label)
        assertTrue(result.tokens.isEmpty())
    }

    @Test
    fun theInboxNeedsNoPill() {
        assertTrue(preview("", defaults = TaskDefaults(projectId = 1)).pills.isEmpty())
    }

    @Test
    fun aSavedPlaceIsHighlightedAndBecomesAPill() {
        val result = voicePreview(
            "call mom when I get home",
            ParserConfig(),
            now,
            day,
            projects,
            inboxId = 1,
            format = format,
            places = listOf(SavedPlace("h", "Home")),
        )
        assertEquals(listOf(VoicePill(TokenType.PLACE, "Arrive: Home")), result.pills)
        assertTrue(result.tokens.any { it.type == TokenType.PLACE })
    }
}
