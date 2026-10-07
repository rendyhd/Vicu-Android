package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The fields the editor can change; everything else on a task is owned by the server. */
@Serializable
internal data class DraftFields(
    val title: String,
    val description: String,
    val dueDate: String,
    val priority: Int,
    val projectId: Long,
    val repeatAfter: Long,
    val repeatMode: Int,
    val reminders: List<TaskReminder>,
)

internal fun Task.draftFields() = DraftFields(
    title = title,
    description = description,
    dueDate = dueDate,
    priority = priority,
    projectId = projectId,
    repeatAfter = repeatAfter,
    repeatMode = repeatMode,
    reminders = reminders,
)

internal fun Task.withDraftFields(fields: DraftFields) = copy(
    title = fields.title,
    description = fields.description,
    dueDate = fields.dueDate,
    priority = fields.priority,
    projectId = fields.projectId,
    repeatAfter = fields.repeatAfter,
    repeatMode = fields.repeatMode,
    reminders = fields.reminders,
)

/**
 * Unsaved edits of one task, small enough to live in the instance state that survives process
 * death. [base] is what the editor started from, so a restored draft can tell which fields the
 * user changed even if the task moved on in the meantime.
 */
@Serializable
internal data class TaskDetailDraft(
    val taskId: Long,
    val edited: DraftFields,
    val base: DraftFields,
    val manuallyEditedTypes: List<String> = emptyList(),
)

internal object TaskDetailDraftCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Instance state is capped near 1 MB for everything; a draft beyond this is not stored. */
    const val MAX_ENCODED_CHARS = 150_000

    fun encode(draft: TaskDetailDraft): String? =
        json.encodeToString(TaskDetailDraft.serializer(), draft).takeIf { it.length <= MAX_ENCODED_CHARS }

    fun decode(encoded: String?): TaskDetailDraft? {
        if (encoded.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(TaskDetailDraft.serializer(), encoded) }.getOrNull()
    }
}

/**
 * Puts a restored [draft] on top of the task as it is now: a field the user changed (it differs
 * from the draft's base) keeps the draft's value, every other field takes the current one.
 */
internal fun applyDraft(draft: TaskDetailDraft, incoming: Task): Task =
    reconcileTaskEditor(
        current = incoming.withDraftFields(draft.edited),
        baseline = incoming.withDraftFields(draft.base),
        incoming = incoming,
    )
