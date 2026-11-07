package com.rendyhd.vicu.ai.ask

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A date changed on a card and what the model hears about it (`docs/ai-assistant-v1.md`, section 5.4). */
class ProposalCardDateTest {
    private fun at(hour: Int, minute: Int): LocalDateTime = LocalDate(2026, 11, 9).atTime(LocalTime(hour, minute))

    @Test
    fun aDateWithATimeKeepsItsTimeWithSeconds() {
        assertEquals("2026-11-09T14:30:00", cardDueText(at(14, 30) to true))
    }

    @Test
    fun aDateOnlyDateIsTheEndOfItsDay() {
        assertEquals("2026-11-09T23:59:59", cardDueText(at(14, 30) to false))
    }

    @Test
    fun aRemovedDateIsNull() {
        assertEquals(null, cardDueText(null))
    }

    @Test
    fun theResultLeavesEditedOutWhenNoDateChanged() {
        val result = Json.parseToJsonElement(Applied("applied", listOf(1L), emptyList(), null).toolResult()).jsonObject
        assertFalse("edited" in result)
    }

    @Test
    fun theResultSaysWhichDatesTheUserChanged() {
        val applied = Applied(
            "applied",
            listOf(1L),
            emptyList(),
            null,
            edited = listOf(
                EditedDue(taskId = 1L, due = "2026-11-09T23:59:59"),
                EditedDue(ref = "t2", due = null),
            ),
        )
        assertEquals(
            """{"status":"applied","applied":[1],"skipped":[],"edited":[{"task_id":1,"due":"2026-11-09T23:59:59"},{"ref":"t2","due":null}]}""",
            applied.toolResult(),
        )
    }
}
