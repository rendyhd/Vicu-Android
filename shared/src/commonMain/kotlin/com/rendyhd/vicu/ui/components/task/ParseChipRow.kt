package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParsedRecurrence
import com.rendyhd.vicu.util.parser.RecurrenceUnit
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParseChipRow(
    parseResult: ParseResult,
    isDarkTheme: Boolean,
    onDismiss: (TokenType) -> Unit,
) {
    val today = LocalClockDay.current.date
    val is24Hour = LocalIs24Hour.current
    val chips = buildChipList(parseResult, today, is24Hour)
    if (chips.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((type, label) in chips) {
            val chipColor = tokenChipColor(type, isDarkTheme)
            InputChip(
                selected = false,
                onClick = { onDismiss(type) },
                label = { Text(label, maxLines = 1) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        // The chip is the button; the icon only says what pressing it removes.
                        contentDescription = chipDismissDescription(type, label),
                        modifier = Modifier.size(14.dp),
                    )
                },
                colors = InputChipDefaults.inputChipColors(
                    labelColor = chipColor,
                    trailingIconColor = chipColor,
                ),
                border = InputChipDefaults.inputChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = chipColor.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

private data class ChipInfo(val type: TokenType, val label: String)

private fun buildChipList(result: ParseResult, today: LocalDate, is24Hour: Boolean): List<ChipInfo> {
    val chips = mutableListOf<ChipInfo>()

    if (result.dueDate != null) {
        chips.add(ChipInfo(TokenType.DATE, formatDateChip(result.dueDate, result.dueDateHasTime, today, is24Hour)))
    }
    if (result.priority != null) {
        chips.add(ChipInfo(TokenType.PRIORITY, formatPriorityChip(result.priority)))
    }
    for (label in result.labels) {
        chips.add(ChipInfo(TokenType.LABEL, label))
    }
    if (result.project != null) {
        chips.add(ChipInfo(TokenType.PROJECT, result.project))
    }
    if (result.recurrence != null) {
        chips.add(ChipInfo(TokenType.RECURRENCE, formatRecurrenceChip(result.recurrence)))
    }
    return chips
}

/** What a screen reader says for the close icon of a chip: it names what is removed. */
internal fun chipDismissDescription(type: TokenType, label: String): String = when (type) {
    TokenType.DATE -> "Remove due date"
    TokenType.PRIORITY -> "Remove priority"
    TokenType.LABEL -> "Remove label $label"
    TokenType.PROJECT -> "Remove project $label"
    TokenType.RECURRENCE -> "Remove repeat"
}

/** The date, plus the time of day only when the text named one (date-only values show no time). */
internal fun formatDateChip(date: LocalDateTime, hasTime: Boolean, today: LocalDate, is24Hour: Boolean): String {
    val dateOnly = date.date
    val day = when (dateOnly) {
        today -> "Today"
        today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        else -> dateOnly.toString()
    }
    return if (hasTime) "$day ${DateUtils.formatClockTime(date.time, is24Hour)}" else day
}

private fun formatPriorityChip(priority: Int): String = when (priority) {
    1 -> "Low"
    2 -> "Medium"
    3 -> "High"
    4 -> "Urgent"
    else -> "P$priority"
}

private fun formatRecurrenceChip(r: ParsedRecurrence): String {
    val unitStr = when (r.unit) {
        RecurrenceUnit.DAY -> if (r.interval == 1) "day" else "days"
        RecurrenceUnit.WEEK -> if (r.interval == 1) "week" else "weeks"
        RecurrenceUnit.MONTH -> if (r.interval == 1) "month" else "months"
        RecurrenceUnit.YEAR -> if (r.interval == 1) "year" else "years"
    }
    return if (r.interval == 1) "Every $unitStr" else "Every ${r.interval} $unitStr"
}
