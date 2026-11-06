package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.dictation.ContextProject
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.organize.OrganizeRequests
import com.rendyhd.vicu.ai.organize.OrganizeSession
import com.rendyhd.vicu.ai.organize.PlanDayRequest
import com.rendyhd.vicu.ai.organize.PlanDayRequests
import com.rendyhd.vicu.ui.screens.ask.AskViewModel
import com.rendyhd.vicu.ai.ask.AskList
import com.rendyhd.vicu.util.MinuteRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text of a suggestions request (`docs/ai-assistant-v1.md`, section 9.1), as the desktop sends it. */
class OrganizeRequestsTest {

    @Test
    fun longNotesAreCutAtASpaceWithAnEllipsis() {
        val max = AiContract.limits.organizeNotesMaxChars
        val words = "word ".repeat(200)
        val sent = OrganizeRequests.notes(words)
        assertTrue(sent.endsWith("word…"), sent.takeLast(10))
        assertTrue(sent.length <= max + 1)
        assertEquals("short note", OrganizeRequests.notes("short note"))
    }

    @Test
    fun theMessageHasTheContextTheSessionAndTheTasks() {
        val context = DictationContext(
            now = LocalDateTime(2026, 10, 27, 9, 30),
            zone = TimeZone.of("Europe/Amsterdam"),
            list = "Inbox",
            defaultProjectId = 1,
            defaultDue = null,
            projects = listOf(ContextProject(1, "Inbox", "Inbox")),
            labels = emptyList(),
            places = emptyList(),
        )
        val summary = buildJsonObject {
            put("id", 101)
            put("title", "dentist")
        }
        val entry = OrganizeRequests.taskEntry(summary, "2026-10-01", null, "")
        assertEquals(listOf("id", "title", "created", "changed", "notes"), entry.keys.toList())
        assertEquals(JsonPrimitive("2026-10-01"), entry["created"])
        val message = OrganizeRequests.userMessage(context, OrganizeSession.INBOX, listOf(entry))
        val lines = message.lines()
        assertEquals("<context>", lines[0])
        assertEquals("</context>", lines[2])
        assertEquals(listOf("<session>", "inbox", "</session>", "<tasks>"), lines.subList(3, 7))
        assertEquals("""[{"id":101,"title":"dentist","created":"2026-10-01","changed":null,"notes":""}]""", lines[7])
        assertEquals("</tasks>", lines[8])
    }

    @Test
    fun aPlanDayMessageHasTheContextThePlanAndTheTwoLists() {
        val context = DictationContext(
            now = LocalDateTime(2026, 10, 27, 9, 30),
            zone = TimeZone.of("Europe/Amsterdam"),
            list = PlanDayRequests.LIST_NAME,
            defaultProjectId = 1,
            defaultDue = null,
            projects = listOf(ContextProject(1, "Inbox", "Inbox")),
            labels = emptyList(),
            places = emptyList(),
        )
        val request = PlanDayRequest(
            day = LocalDate(2026, 10, 27),
            nowMinute = 9 * 60 + 30,
            defaultLength = 30,
            free = listOf(MinuteRange(570, 660), MinuteRange(720, 1080)),
            routineMinutes = listOf(1200, 480),
            leftovers = emptyList(),
            candidates = emptyList(),
        )
        val plan = PlanDayRequests.planBlock(request)
        val entry = PlanDayRequests.taskEntry(
            buildJsonObject {
                put("id", 101)
                put("title", "dentist")
            },
            "a note",
        )
        val lines = PlanDayRequests.userMessage(context, plan, listOf(entry), emptyList()).lines()
        assertEquals("<context>", lines[0])
        assertEquals("</context>", lines[2])
        assertEquals("<plan>", lines[3])
        assertEquals(
            """{"day":"2026-10-27","now":"09:30","default_length":30,""" +
                """"free":[{"start":"09:30","end":"11:00"},{"start":"12:00","end":"18:00"}],"routines":["08:00","20:00"]}""",
            lines[4],
        )
        assertEquals("</plan>", lines[5])
        assertEquals("<leftovers>", lines[6])
        assertEquals("""[{"id":101,"title":"dentist","notes":"a note"}]""", lines[7])
        assertEquals(listOf("</leftovers>", "<candidates>", "[]", "</candidates>"), lines.subList(8, 12))
    }

    @Test
    fun startersFollowTheListAskWasOpenedFrom() {
        assertEquals("today", AskViewModel.startersKind(AskList("Today"), inboxId = 1))
        assertEquals("upcoming", AskViewModel.startersKind(AskList("Upcoming"), inboxId = 1))
        assertEquals("inbox", AskViewModel.startersKind(AskList("Inbox", projectId = 1), inboxId = 1))
        assertEquals("project", AskViewModel.startersKind(AskList("Garden", projectId = 6), inboxId = 1))
        assertEquals("other", AskViewModel.startersKind(AskList("Errands", customListId = "abc"), inboxId = 1))
        assertEquals("other", AskViewModel.startersKind(AskList("Anytime"), inboxId = 1))
    }
}
