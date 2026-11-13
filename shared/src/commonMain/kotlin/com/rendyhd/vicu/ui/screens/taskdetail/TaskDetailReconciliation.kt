package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.domain.model.Task

/**
 * Reconciles a fresh Room value with an editor session.
 *
 * [baseline] is the last Room value shown to the editor. A writable field is kept from
 * [current] only when it differs from that baseline, which means the user changed it locally.
 * Everything else is adopted from [incoming], preventing a later save from writing stale values
 * back over changes made by another client.
 *
 * Labels are intentionally server-owned here. Label edits use their own optimistic repository
 * operations and Room emissions, so retaining an older editor copy would bring back issue #6.
 */
internal fun reconcileTaskEditor(
    current: Task,
    baseline: Task,
    incoming: Task,
): Task {
    // These fields form one recurrence value. Mixing a locally edited half with a remotely
    // updated half can silently change its meaning (for example, weekly into monthly mode).
    val localRecurrenceChanged = current.repeatAfter != baseline.repeatAfter ||
        current.repeatMode != baseline.repeatMode
    val recurrence = if (localRecurrenceChanged) current else incoming

    return incoming.copy(
        title = current.title.takeIf { it != baseline.title } ?: incoming.title,
        description = current.description.takeIf { it != baseline.description } ?: incoming.description,
        done = current.done.takeIf { it != baseline.done } ?: incoming.done,
        dueDate = current.dueDate.takeIf { it != baseline.dueDate } ?: incoming.dueDate,
        priority = current.priority.takeIf { it != baseline.priority } ?: incoming.priority,
        projectId = current.projectId.takeIf { it != baseline.projectId } ?: incoming.projectId,
        repeatAfter = recurrence.repeatAfter,
        repeatMode = recurrence.repeatMode,
        startDate = current.startDate.takeIf { it != baseline.startDate } ?: incoming.startDate,
        endDate = current.endDate.takeIf { it != baseline.endDate } ?: incoming.endDate,
        hexColor = current.hexColor.takeIf { it != baseline.hexColor } ?: incoming.hexColor,
        percentDone = current.percentDone.takeIf { it != baseline.percentDone } ?: incoming.percentDone,
        bucketId = current.bucketId.takeIf { it != baseline.bucketId } ?: incoming.bucketId,
        reminders = current.reminders.takeIf { it != baseline.reminders } ?: incoming.reminders,
        isFavorite = current.isFavorite.takeIf { it != baseline.isFavorite } ?: incoming.isFavorite,
    )
}
