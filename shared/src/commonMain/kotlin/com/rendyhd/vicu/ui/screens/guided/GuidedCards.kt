package com.rendyhd.vicu.ui.screens.guided

import com.rendyhd.vicu.ai.ask.TaskChange
import com.rendyhd.vicu.ai.organize.Suggestion
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/** A card's choice of due date: a new one, or none. */
sealed interface DueChoice {
    /** Local wall-clock; a date-only one is 23:59:59 with [hasTime] false. */
    data class On(val local: LocalDateTime, val hasTime: Boolean) : DueChoice

    data object Clear : DueChoice
}

/**
 * What a card writes when the person goes on (docs/cross-app-semantics-v1.md, section 14.3); a null
 * field is kept as it is. [suggested] names the fields a suggestion filled in that the person has
 * not changed since (`title`, `when`, `project`), drawn with the dashed outline.
 */
data class CardDraft(
    val title: String? = null,
    val due: DueChoice? = null,
    val projectId: Long? = null,
    val suggested: Set<String> = emptySet(),
) {
    fun withTitle(value: String) = copy(title = value, suggested = suggested - FIELD_TITLE)

    fun withDue(value: DueChoice?) = copy(due = value, suggested = suggested - FIELD_WHEN)

    fun withProject(value: Long?) = copy(projectId = value, suggested = suggested - FIELD_PROJECT)

    /** The change this draft makes to [task], or null when it changes nothing. */
    fun changeOf(task: Task): TaskChange? {
        val title = title?.trim()?.takeIf { it.isNotEmpty() && it != task.title }
        val hasDue = DateUtils.parseIsoDate(task.dueDate) != null
        val change = TaskChange(
            before = task,
            title = title,
            due = (due as? DueChoice.On)?.let { it.local to it.hasTime },
            clearDue = due == DueChoice.Clear && hasDue,
            projectId = projectId?.takeIf { it != task.projectId },
        )
        return change.takeIf { it.title != null || it.due != null || it.clearDue || it.projectId != null }
    }

    companion object {
        const val FIELD_TITLE = "title"
        const val FIELD_WHEN = "when"
        const val FIELD_PROJECT = "project"

        /** A suggestion's choices, each marked as suggested (ai-assistant 9.4). */
        fun of(suggestion: Suggestion): CardDraft {
            val due = suggestion.due?.let { DueChoice.On(it, suggestion.dueHasTime) } ?: if (suggestion.clearDue) DueChoice.Clear else null
            return CardDraft(
                title = suggestion.title,
                due = due,
                projectId = suggestion.projectId,
                suggested = buildSet {
                    if (suggestion.title != null) add(FIELD_TITLE)
                    if (due != null) add(FIELD_WHEN)
                    if (suggestion.projectId != null) add(FIELD_PROJECT)
                },
            )
        }
    }
}

/** How a session left things, for its summary line (section 14.3; the desktop's `sessionCounts`). */
data class GuidedCounts(
    val sorted: Int = 0,
    val scheduled: Int = 0,
    val unscheduled: Int = 0,
    val moved: Int = 0,
    val renamed: Int = 0,
    val completed: Int = 0,
    val deleted: Int = 0,
) {
    /** "Sorted 12: 5 scheduled, 4 moved, 2 completed, 1 deleted", or "Nothing changed". */
    val text: String
        get() {
            if (sorted == 0) return "Nothing changed"
            val parts = listOfNotNull(
                "$scheduled scheduled".takeIf { scheduled > 0 },
                "$unscheduled without a date now".takeIf { unscheduled > 0 },
                "$moved moved".takeIf { moved > 0 },
                "$renamed renamed".takeIf { renamed > 0 },
                "$completed completed".takeIf { completed > 0 },
                "$deleted deleted".takeIf { deleted > 0 },
            )
            return "Sorted $sorted: ${parts.joinToString(", ")}"
        }
}

/**
 * What a session changed, one entry per task: the task as it was before the session's first change
 * to it, and how it is now. A later change to the same task (after Back) updates the entry, so each
 * task counts once, and Undo all puts back the values from before the session.
 */
class GuidedLog {
    data class Entry(val before: Task, val after: Task, val completed: Boolean = false, val deleted: Boolean = false)

    private val entries = LinkedHashMap<Long, Entry>()

    val all: List<Entry> get() = entries.values.toList()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun changed(change: TaskChange, zone: TimeZone) {
        val task = change.before
        var after = task
        change.title?.let { after = after.copy(title = it) }
        change.due?.let { (local, hasTime) -> after = after.copy(dueDate = DueDates.fromParsed(local, hasTime, zone).toString()) }
        if (change.clearDue) after = after.copy(dueDate = "")
        change.projectId?.let { after = after.copy(projectId = it) }
        val first = entries[task.id]?.before ?: task
        entries[task.id] = Entry(first, after)
    }

    fun completed(task: Task) {
        // What the session changed before completing it stays in [Entry.after], for Undo all.
        val earlier = entries[task.id]
        entries[task.id] = Entry(earlier?.before ?: task, (earlier?.after ?: task).copy(done = true), completed = true)
    }

    fun deleted(task: Task) {
        entries[task.id] = Entry(entries[task.id]?.before ?: task, task, deleted = true)
    }

    fun clear() = entries.clear()

    fun counts(): GuidedCounts {
        var c = GuidedCounts()
        for (e in entries.values) {
            when {
                e.deleted -> c = c.copy(sorted = c.sorted + 1, deleted = c.deleted + 1)
                e.completed -> c = c.copy(sorted = c.sorted + 1, completed = c.completed + 1)
                else -> {
                    val dueChanged = DateUtils.parseIsoDate(e.before.dueDate) != DateUtils.parseIsoDate(e.after.dueDate)
                    val moved = e.before.projectId != e.after.projectId
                    val renamed = e.before.title != e.after.title
                    if (dueChanged) {
                        c = if (DateUtils.parseIsoDate(e.after.dueDate) == null) c.copy(unscheduled = c.unscheduled + 1) else c.copy(scheduled = c.scheduled + 1)
                    }
                    if (moved) c = c.copy(moved = c.moved + 1)
                    if (renamed) c = c.copy(renamed = c.renamed + 1)
                    if (dueChanged || moved || renamed) c = c.copy(sorted = c.sorted + 1)
                }
            }
        }
        return c
    }
}
