package com.rendyhd.vicu.ui.screens.taskdetail

/** Which part of a task a [DetailValue] shows, and so which picker tapping it opens. */
enum class DetailField(val label: String) {
    DUE_DATE("Due"),
    PROJECT("Project"),
    PRIORITY("Priority"),
    RECURRENCE("Repeats"),
    REMINDERS("Reminders"),
}

/**
 * One current value of the open task, shown as text so the icon buttons are not the only place it
 * can be read. [emphasis] marks a value that needs attention (an overdue date).
 */
data class DetailValue(
    val field: DetailField,
    val label: String,
    val text: String,
    val emphasis: Boolean = false,
) {
    /** What a screen reader says for the value. */
    val description: String get() = "$label: $text"
}

/**
 * The values the detail screen shows under its icon buttons, in a fixed order. The project is
 * always there (every task lives in one); the rest only when they are set.
 *
 * @param dueText the formatted due date, or null when there is none
 * @param projectName the project's title, or null when it is not known
 * @param recurrence the formatted recurrence, blank when the task does not repeat
 * @param reminders the formatted reminders, blank when there are none
 */
fun taskDetailValues(
    dueText: String?,
    dueOverdue: Boolean,
    projectName: String?,
    priority: Int,
    recurrence: String,
    reminders: String,
): List<DetailValue> = buildList {
    if (dueText != null) {
        add(DetailValue(DetailField.DUE_DATE, DetailField.DUE_DATE.label, dueText, emphasis = dueOverdue))
    }
    add(DetailValue(DetailField.PROJECT, DetailField.PROJECT.label, projectName ?: "No project"))
    priorityName(priority)?.let {
        add(DetailValue(DetailField.PRIORITY, DetailField.PRIORITY.label, it))
    }
    if (recurrence.isNotBlank()) {
        add(DetailValue(DetailField.RECURRENCE, DetailField.RECURRENCE.label, recurrence))
    }
    if (reminders.isNotBlank()) {
        add(DetailValue(DetailField.REMINDERS, DetailField.REMINDERS.label, reminders))
    }
}

/** The name of a priority level, or null for none (0) and for numbers outside Vikunja's range. */
fun priorityName(priority: Int): String? = when (priority) {
    1 -> "Low"
    2 -> "Medium"
    3 -> "High"
    4 -> "Urgent"
    5 -> "Do now"
    else -> null
}

private val LINE_BREAK = Regex("\r\n|\r|\n")

/** A title is one line: every line break (typed or pasted) becomes a space. */
fun String.withoutLineBreaks(): String = if ('\n' in this || '\r' in this) replace(LINE_BREAK, " ") else this
