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
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParsedRecurrence
import com.rendyhd.vicu.util.parser.RecurrenceUnit
import com.rendyhd.vicu.util.parser.TokenType
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParseChipRow(
    parseResult: ParseResult,
    isDarkTheme: Boolean,
    onDismiss: (TokenType) -> Unit,
) {
    val chips = buildChipList(parseResult)
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
                        contentDescription = "Dismiss",
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

private fun buildChipList(result: ParseResult): List<ChipInfo> {
    val chips = mutableListOf<ChipInfo>()

    if (result.dueDate != null) {
        chips.add(ChipInfo(TokenType.DATE, formatDateChip(result.dueDate)))
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

private fun formatDateChip(date: LocalDateTime): String {
    val today = LocalDate.now()
    val dateOnly = date.toLocalDate()
    return when {
        dateOnly == today -> if (date.hour in 1..22) "Today ${date.hour}:00" else "Today"
        dateOnly == today.plusDays(1) -> if (date.hour != 12) "Tomorrow ${date.hour}:00" else "Tomorrow"
        else -> dateOnly.toString()
    }
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
