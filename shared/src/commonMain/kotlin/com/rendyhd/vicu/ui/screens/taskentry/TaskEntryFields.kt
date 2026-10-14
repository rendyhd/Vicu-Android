package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.RecurrenceValue
import kotlinx.datetime.TimeZone

// One source of truth for the new-task sheet, the same rule as the desktop composer
// (`src/renderer/lib/composer-fields.ts`, card 3.5): a field shows, and saves, what the parser read
// from the typed text unless the user set that field with its own control (a chip's picker), in
// which case the control wins and its token loses the highlight (the view model pins the type, so
// the parser stops reading it). The chips, the title highlights and the save all read the result of
// [resolveEntryFields], so nothing is saved that was not on screen.

/** Where a field's value comes from. */
enum class FieldSource {
    /** Set by hand with the field's own control. */
    CHIP,

    /** Read from the typed title by the parser. */
    TEXT,

    /** What the screen that opened the sheet gave (Today's date, the project of a project screen). */
    DEFAULT,
}

/** What the new task is going to get, and where each value came from. */
data class EntryFields(
    /** The due date to save as the ISO instant the task stores, or "" for none. */
    val dueDate: String,
    /** Null when there is no due date. */
    val dueSource: FieldSource?,
    /** 0 for none. */
    val priority: Int,
    val prioritySource: FieldSource?,
    val projectId: Long,
    val projectSource: FieldSource,
    /** The project the text names when it exists, not found otherwise (see [projectNotFound]). */
    val parsedProjectName: String?,
    /** True when the text names a project that does not exist, so the task goes to [projectId]. */
    val projectNotFound: Boolean,
    val recurrence: RecurrenceValue,
    /** Null when the task does not repeat. */
    val recurrenceSource: FieldSource?,
)

internal fun resolveEntryFields(state: TaskEntryUiState, zone: TimeZone): EntryFields {
    val enabled = state.parserConfig.enabled
    val parsed = state.parseResult.takeIf { enabled }

    fun hasDate(value: String) = value.isNotBlank() && !DateUtils.isNullDate(value)

    // Due date: picked or cleared by hand > typed in the title > the date the screen seeded.
    var dueDate = ""
    var dueSource: FieldSource? = null
    if (state.dueDateIsManual) {
        if (hasDate(state.dueDate)) {
            dueDate = state.dueDate
            dueSource = FieldSource.CHIP
        }
    } else if (parsed?.dueDate != null) {
        dueDate = DueDates.fromParsed(parsed.dueDate, parsed.dueDateHasTime, zone).toString()
        dueSource = FieldSource.TEXT
    } else if (hasDate(state.dueDate)) {
        dueDate = state.dueDate
        dueSource = FieldSource.DEFAULT
    }

    // Priority: picked by hand (None included) > typed in the title.
    var priority = 0
    var prioritySource: FieldSource? = null
    if (state.priorityIsManual) {
        priority = state.priority
        prioritySource = if (priority > 0) FieldSource.CHIP else null
    } else if (parsed?.priority != null && parsed.priority > 0) {
        priority = parsed.priority
        prioritySource = FieldSource.TEXT
    }

    // Project: picked by hand > named in the title (when it exists) > the one the screen gave.
    var projectId = state.projectId
    var projectSource = if (state.projectIsManual) FieldSource.CHIP else FieldSource.DEFAULT
    var projectNotFound = false
    val parsedProject = if (state.projectIsManual) null else parsed?.project
    if (parsedProject != null) {
        val found = state.allProjects.find { it.title.equals(parsedProject, ignoreCase = true) }
        if (found != null) {
            projectId = found.id
            projectSource = FieldSource.TEXT
        } else {
            projectNotFound = true
        }
    }

    // Repeat: picked by hand (None included) > typed in the title.
    val recurrence = resolveTaskEntryRecurrence(
        manualRecurrence = state.manualRecurrence,
        parserEnabled = enabled,
        parsedRecurrence = parsed?.recurrence,
    )
    val recurrenceSource = when {
        !recurrence.isRecurring -> null
        state.manualRecurrence != null -> FieldSource.CHIP
        else -> FieldSource.TEXT
    }

    return EntryFields(
        dueDate = dueDate,
        dueSource = dueSource,
        priority = priority,
        prioritySource = prioritySource,
        projectId = projectId,
        projectSource = projectSource,
        parsedProjectName = parsedProject,
        projectNotFound = projectNotFound,
        recurrence = recurrence,
        recurrenceSource = recurrenceSource,
    )
}
