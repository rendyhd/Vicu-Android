package com.rendyhd.vicu.util.parser

private const val DAY = 86400L
private const val WEEK = 604800L
private const val YEAR = 365 * DAY

data class VikunjaRecurrence(
    val repeatAfter: Long,
    val repeatMode: Int,
)

fun recurrenceToVikunja(r: ParsedRecurrence): VikunjaRecurrence {
    return when (r.unit) {
        RecurrenceUnit.DAY -> VikunjaRecurrence(r.interval * DAY, 0)
        RecurrenceUnit.WEEK -> VikunjaRecurrence(r.interval * WEEK, 0)
        RecurrenceUnit.MONTH -> {
            if (r.interval == 1) {
                VikunjaRecurrence(0, 1)
            } else {
                // Multi-month: approximate with days
                VikunjaRecurrence(r.interval * 30 * DAY, 0)
            }
        }
        RecurrenceUnit.YEAR -> VikunjaRecurrence(r.interval * YEAR, 0)
    }
}
