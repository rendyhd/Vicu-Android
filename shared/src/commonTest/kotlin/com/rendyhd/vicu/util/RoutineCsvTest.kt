package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineCsvTest {

    @Test
    fun `a cell is quoted and inner quotes are doubled`() {
        assertEquals("\"plain\"", RoutineCsv.cell("plain"))
        assertEquals("\"say \"\"hi\"\"\"", RoutineCsv.cell("say \"hi\""))
        assertEquals("\"\"", RoutineCsv.cell(""))
        assertEquals("\"a,b\"", RoutineCsv.cell("a,b"))
    }

    @Test
    fun `a cell that starts like a formula gets a leading apostrophe`() {
        for (start in listOf("=", "+", "-", "@", "\t", "\r")) {
            val cell = RoutineCsv.cell("${start}1+1")
            assertEquals("\"'${start}1+1\"", cell, "cell starting with ${start.encodeToByteArray().joinToString()}")
        }
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", RoutineCsv.cell("=HYPERLINK(\"http://x\")"))
    }

    @Test
    fun `only the first character decides`() {
        assertEquals("\"a=b\"", RoutineCsv.cell("a=b"))
        assertEquals("\"1-2\"", RoutineCsv.cell("1-2"))
        assertEquals("\" =x\"", RoutineCsv.cell(" =x"))
    }

    private fun record(date: String, note: String = "", status: OccurrenceStatus = OccurrenceStatus.COMPLETED) =
        RoutineOccurrenceRecord(
            key = "r:$date:s",
            routineId = "r",
            slotId = "s",
            scheduledDate = date,
            scheduledMinutes = 485,
            timeZoneId = "Europe/Amsterdam",
            status = status,
            loggedAt = "${date}T07:00:00.000Z",
            modifiedAt = "${date}T07:00:00.000Z",
            modifiedBy = "phone",
            note = note,
        )

    @Test
    fun `rows are guarded sorted and written under the header`() {
        val csv = RoutineCsv.build(
            listOf(
                RoutineCsv.Entry("=Vitamin", listOf(record("2026-10-02", note = "-1 pill"), record("2026-10-01"))),
            ),
        )

        val lines = csv.trimEnd().lines()
        assertEquals("routine,scheduled_date,scheduled_time,status,logged_at,time_zone,note", lines.first())
        assertEquals(
            "\"'=Vitamin\",\"2026-10-01\",\"08:05\",\"COMPLETED\",\"2026-10-01T07:00:00.000Z\",\"Europe/Amsterdam\",\"\"",
            lines[1],
        )
        assertEquals(
            "\"'=Vitamin\",\"2026-10-02\",\"08:05\",\"COMPLETED\",\"2026-10-02T07:00:00.000Z\",\"Europe/Amsterdam\",\"'-1 pill\"",
            lines[2],
        )
        assertEquals(3, lines.size)
    }
}
