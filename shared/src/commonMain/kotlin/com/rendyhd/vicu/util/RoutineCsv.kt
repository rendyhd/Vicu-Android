package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord

/** The routine history export. Both apps write the same columns. */
object RoutineCsv {
    const val HEADER = "routine,scheduled_date,scheduled_time,status,logged_at,time_zone,note"

    data class Entry(val name: String, val occurrences: Collection<RoutineOccurrenceRecord>)

    /** Characters that make a spreadsheet read a cell as a formula. */
    private val formulaStarts = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * One quoted CSV cell. A cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return
     * gets a leading `'`, so a spreadsheet shows it as text instead of running it as a formula.
     */
    fun cell(value: String): String {
        val text = if (value.isNotEmpty() && value[0] in formulaStarts) "'$value" else value
        return "\"${text.replace("\"", "\"\"")}\""
    }

    fun timeLabel(minutes: Int): String =
        "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    fun build(entries: List<Entry>): String {
        val rows = entries.flatMap { (name, occurrences) ->
            occurrences.map { occurrence ->
                listOf(
                    name,
                    occurrence.scheduledDate,
                    timeLabel(occurrence.scheduledMinutes),
                    occurrence.status.name,
                    occurrence.loggedAt,
                    occurrence.timeZoneId,
                    occurrence.note,
                ).joinToString(",") { cell(it) }
            }
        }
        return buildString {
            appendLine(HEADER)
            rows.sorted().forEach(::appendLine)
        }
    }
}
