package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.data.repository.ProCapture
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.recurrenceToVikunja
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

internal data class TaskEditShortcutResult(
    val task: Task,
    val labelNames: List<String>,
)

/**
 * Applies title shortcuts to an existing task while leaving manually edited fields alone. A parsed
 * date is stored date-only unless the text named a time; [zone] is the device's current zone.
 * With Pro on ([pro]), `starts monday` sets a lone start date (not over a block), `!flag` flags
 * the task and `Jan:` adds that person's label; with Pro off nothing of it is read.
 */
internal fun applyTaskEditShortcuts(
    task: Task,
    parseResult: ParseResult,
    projects: List<Project>,
    zone: TimeZone,
    manuallyEditedTypes: Set<TokenType> = emptySet(),
    pro: ProCapture = ProCapture.OFF,
): TaskEditShortcutResult {
    var updated = task.copy(title = parseResult.title)

    if (TokenType.DATE !in manuallyEditedTypes) {
        parseResult.dueDate?.let { dueDate ->
            updated = updated.copy(
                dueDate = DueDates.fromParsed(dueDate, parseResult.dueDateHasTime, zone).toString(),
            )
        }
        // "at 4pm for 30m" makes a time block, which holds the time.
        parseResult.block?.let { block ->
            updated = updated.copy(
                startDate = block.start.toInstant(zone).toString(),
                endDate = block.end.toInstant(zone).toString(),
            )
        }
    }

    if (TokenType.PRIORITY !in manuallyEditedTypes) {
        parseResult.priority?.let { priority ->
            updated = updated.copy(priority = priority)
        }
    }

    if (TokenType.PROJECT !in manuallyEditedTypes) {
        parseResult.project?.let { projectName ->
            projects.firstOrNull { it.title.equals(projectName, ignoreCase = true) }
                ?.let { project -> updated = updated.copy(projectId = project.id) }
        }
    }

    if (TokenType.RECURRENCE !in manuallyEditedTypes) {
        parseResult.recurrence?.let { recurrence ->
            val vikunjaRecurrence = recurrenceToVikunja(recurrence)
            updated = updated.copy(
                repeatAfter = vikunjaRecurrence.repeatAfter,
                repeatMode = vikunjaRecurrence.repeatMode,
            )
        }
    }

    // A block holds the start already (decision 17): a start date never goes over one.
    pro.startDateOf(parseResult, zone)?.let { start ->
        if (!TimeBlocks.isBlock(updated.startDate, updated.endDate, zone)) updated = updated.copy(startDate = start)
    }
    if (pro.flagOf(parseResult)) updated = updated.copy(isFavorite = true)
    val person = pro.personLabelOf(parseResult)?.let { id -> pro.people.firstOrNull { it.id == id }?.title }

    val labelNames = if (TokenType.LABEL in manuallyEditedTypes) {
        emptyList()
    } else {
        parseResult.labels.distinctBy { it.lowercase() }
    }
    return TaskEditShortcutResult(
        task = updated,
        labelNames = if (person != null && labelNames.none { it.equals(person, ignoreCase = true) }) labelNames + person else labelNames,
    )
}
