package com.rendyhd.vicu.ai.ask

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiText
import com.rendyhd.vicu.ai.dictation.AddedDrafts
import com.rendyhd.vicu.ai.dictation.CheckResult
import com.rendyhd.vicu.ai.dictation.DictationChecks
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DraftWriter
import com.rendyhd.vicu.ai.dictation.takeUtf16
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A task the card lists as skipped, never sent (`docs/ai-assistant-v1.md`, section 5.4). */
data class SkippedTask(val taskId: Long, val reason: String, val title: String?)

/** One task's change on an update card: the values before and after. */
data class TaskChange(
    val before: Task,
    val title: String? = null,
    val appendNotes: String? = null,
    /** A new due date (local, with whether it has a time). */
    val due: Pair<LocalDateTime, Boolean>? = null,
    val clearDue: Boolean = false,
    val projectId: Long? = null,
    val labelsAdd: List<String> = emptyList(),
    val labelsRemove: List<Label> = emptyList(),
    val priority: Int? = null,
)

/** A change the model proposed; the user applies or declines it on a card. */
sealed interface Proposal {
    val callId: String
    val skipped: List<SkippedTask>

    /** How many tasks the card would change (the second confirmation counts these). */
    val size: Int

    data class Create(
        override val callId: String,
        val result: CheckResult,
        val context: DictationContext,
        override val skipped: List<SkippedTask> = emptyList(),
    ) : Proposal {
        override val size: Int get() = result.drafts.size
    }

    data class Update(
        override val callId: String,
        val changes: List<TaskChange>,
        override val skipped: List<SkippedTask>,
    ) : Proposal {
        override val size: Int get() = changes.size
    }

    data class Complete(
        override val callId: String,
        val done: Boolean,
        val tasks: List<Task>,
        override val skipped: List<SkippedTask>,
    ) : Proposal {
        override val size: Int get() = tasks.size
    }
}

/** What applying did, for the tool result and the Undo. */
data class Applied(val status: String, val appliedIds: List<Long>, val skipped: List<SkippedTask>, val undo: (suspend () -> Unit)?) {
    fun toolResult(): String = buildJsonObject {
        put("status", status)
        put("applied", JsonArray(appliedIds.map { JsonPrimitive(it) }))
        put(
            "skipped",
            buildJsonArray {
                skipped.forEach { s -> add(buildJsonObject { put("task_id", s.taskId); put("reason", s.reason) }) }
            },
        )
    }.toString()

    companion object {
        fun declined(skipped: List<SkippedTask>) = Applied("declined", emptyList(), skipped, null)
    }
}

/** Builds proposals from tool arguments and applies them through the usual write paths. */
class Proposals(
    private val tasks: TaskRepository,
    private val labels: LabelRepository,
    private val drafts: DraftWriter,
) {
    /** A proposal, or the error to send back as the tool result. */
    sealed interface Built {
        data class Ok(val proposal: Proposal) : Built
        data class Error(val detail: String) : Built
    }

    fun build(name: String, callId: String, args: JsonObject, snapshot: AskSnapshot, context: DictationContext): Built {
        val max = AiContract.limits.proposalMaxTasks
        return when (name) {
            "propose_create_tasks" -> {
                val count = (args["tasks"] as? JsonArray)?.size ?: 0
                if (count > max) return Built.Error("at most $max tasks per proposal")
                val answer = buildJsonObject {
                    put("tasks", args["tasks"] ?: JsonArray(emptyList()))
                    put("unclear", JsonArray(emptyList()))
                }
                Built.Ok(Proposal.Create(callId, DictationChecks.check(answer, context), context))
            }
            "propose_update_tasks" -> {
                val list = (args["changes"] as? JsonArray).orEmpty()
                if (list.size > max) return Built.Error("at most $max tasks per proposal")
                val changes = mutableListOf<TaskChange>()
                val skipped = mutableListOf<SkippedTask>()
                for (element in list) {
                    val c = element as? JsonObject ?: continue
                    val id = (c["task_id"] as? JsonPrimitive)?.longOrNull ?: continue
                    val task = snapshot.byId[id]
                    if (task == null) {
                        skipped += SkippedTask(id, "not_found", null)
                        continue
                    }
                    if (!snapshot.canWrite(task.projectId)) {
                        skipped += SkippedTask(id, "read_only", task.title)
                        continue
                    }
                    val change = updateOf(task, c, snapshot, context)
                    if (change == null) skipped += SkippedTask(id, "invalid", task.title) else changes += change
                }
                Built.Ok(Proposal.Update(callId, changes, skipped))
            }
            "propose_complete_tasks" -> {
                val ids = (args["task_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }.distinct()
                if (ids.size > max) return Built.Error("at most $max tasks per proposal")
                val done = (args["done"] as? JsonPrimitive)?.booleanOrNull ?: true
                val found = mutableListOf<Task>()
                val skipped = mutableListOf<SkippedTask>()
                for (id in ids) {
                    val task = snapshot.byId[id]
                    when {
                        task == null -> skipped += SkippedTask(id, "not_found", null)
                        !snapshot.canWrite(task.projectId) -> skipped += SkippedTask(id, "read_only", task.title)
                        task.done == done -> skipped += SkippedTask(id, "invalid", task.title)
                        else -> found += task
                    }
                }
                Built.Ok(Proposal.Complete(callId, done, found, skipped))
            }
            else -> Built.Error("unknown tool $name")
        }
    }

    /** One update, or null when it is invalid (sets and clears the due date, or changes nothing). */
    private fun updateOf(task: Task, c: JsonObject, snapshot: AskSnapshot, context: DictationContext): TaskChange? {
        val whenObject = c["when"] as? JsonObject
        val clearDue = (c["clear_due"] as? JsonPrimitive)?.booleanOrNull == true
        if (whenObject != null && clearDue) return null
        val title = (c["title"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            ?.let { DictationChecks.oneLine(it).trim().takeUtf16(AiContract.limits.titleMaxChars) }
            ?.takeIf { it.isNotEmpty() && it != task.title }
        val notes = (c["append_notes"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val due = whenObject?.let { DictationChecks.readWhen(it, context) }?.let { r -> r.due?.let { it to r.hasTime } }
        if (whenObject != null && due == null) return null
        val projectId = (c["project_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
        if (projectId != null && !snapshot.canWrite(projectId)) return null
        val known = snapshot.labels
        val add = (c["labels_add"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(DictationChecks::stripPrefix)?.takeIf { n -> n.isNotEmpty() } }
            .distinctBy { it.lowercase() }
            .filter { name -> task.labels.none { it.title.equals(name, ignoreCase = true) } }
            .map { name -> known.firstOrNull { it.title.equals(name, ignoreCase = true) }?.title ?: name }
        val remove = (c["labels_remove"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(DictationChecks::stripPrefix) }
            .mapNotNull { name -> task.labels.firstOrNull { it.title.equals(name, ignoreCase = true) } }
            .distinctBy { it.id }
        val priorityValue = (c["priority"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
        if (priorityValue != null && priorityValue !in 0..4) return null
        val change = TaskChange(
            before = task,
            title = title,
            appendNotes = notes,
            due = due,
            clearDue = clearDue && task.dueDate.isNotBlank() && !task.dueDate.startsWith("0001-"),
            projectId = projectId?.takeIf { it != task.projectId },
            labelsAdd = add,
            labelsRemove = remove,
            priority = priorityValue?.takeIf { it != task.priority },
        )
        val changesSomething = change.title != null || change.appendNotes != null || change.due != null || change.clearDue ||
            change.projectId != null || change.labelsAdd.isNotEmpty() || change.labelsRemove.isNotEmpty() || change.priority != null
        return change.takeIf { changesSomething }
    }

    suspend fun apply(proposal: Proposal, zone: kotlinx.datetime.TimeZone, parentTaskId: Long? = null): Applied = when (proposal) {
        is Proposal.Create -> {
            val added = drafts.add(proposal.result.drafts, zone, parentTaskId)
            Applied(statusOf(added.taskIds.size, added.failed), added.taskIds, proposal.skipped, if (added.taskIds.isEmpty()) null else suspend { drafts.undo(added) })
        }
        is Proposal.Update -> applyUpdates(proposal, zone)
        is Proposal.Complete -> {
            val applied = mutableListOf<Long>()
            var failed = 0
            for (task in proposal.tasks) {
                if (tasks.setDone(task.id, proposal.done) is NetworkResult.Success) applied += task.id else failed++
            }
            Applied(
                statusOf(applied.size, failed),
                applied,
                proposal.skipped,
                if (applied.isEmpty()) null else suspend { for (id in applied) tasks.setDone(id, !proposal.done) },
            )
        }
    }

    private suspend fun applyUpdates(proposal: Proposal.Update, zone: kotlinx.datetime.TimeZone): Applied {
        val applied = mutableListOf<Long>()
        val undo = mutableListOf<suspend () -> Unit>()
        var failed = 0
        val known = labels.getAll().first().toMutableList()
        for (change in proposal.changes) {
            val current = tasks.getById(change.before.id).first() ?: change.before
            var updated = current
            change.title?.let { updated = updated.copy(title = it) }
            change.appendNotes?.let { updated = updated.copy(description = AiText.appendNotes(updated.description, it)) }
            change.due?.let { (local, hasTime) -> updated = updated.copy(dueDate = DueDates.fromParsed(local, hasTime, zone).toString()) }
            if (change.clearDue) updated = updated.copy(dueDate = "")
            change.priority?.let { updated = updated.copy(priority = it) }
            var ok = true
            if (updated != current) ok = tasks.update(updated) is NetworkResult.Success
            if (ok && change.projectId != null) ok = tasks.moveToProject(current.id, change.projectId) is NetworkResult.Success
            val addedLabelIds = mutableListOf<Long>()
            if (ok) {
                for (name in change.labelsAdd) {
                    val id = known.firstOrNull { it.title.equals(name, ignoreCase = true) }?.id
                        ?: (labels.create(Label(0L, name)) as? NetworkResult.Success)?.data?.also { known += it }?.id
                        ?: continue
                    if (labels.addToTask(current.id, id) is NetworkResult.Success) addedLabelIds += id
                }
                for (label in change.labelsRemove) labels.removeFromTask(current.id, label.id)
            }
            if (ok) {
                applied += current.id
                val before = current
                val removed = change.labelsRemove
                val movedFrom = if (change.projectId != null) current.projectId else null
                undo += {
                    if (movedFrom != null) tasks.moveToProject(before.id, movedFrom)
                    val now = tasks.getById(before.id).first()
                    if (now != null) {
                        tasks.update(
                            now.copy(
                                title = before.title,
                                description = before.description,
                                dueDate = before.dueDate,
                                // A due date moves or removes a block on its day (contract 16.3).
                                startDate = before.startDate,
                                endDate = before.endDate,
                                reminders = before.reminders,
                                priority = before.priority,
                            ),
                        )
                    }
                    for (id in addedLabelIds) labels.removeFromTask(before.id, id)
                    for (label in removed) labels.addToTask(before.id, label.id)
                }
            } else {
                failed++
            }
        }
        return Applied(statusOf(applied.size, failed), applied, proposal.skipped, if (applied.isEmpty()) null else suspend { undo.forEach { it() } })
    }

    private fun statusOf(applied: Int, failed: Int): String = when {
        failed == 0 && applied > 0 -> "applied"
        applied == 0 -> "failed"
        else -> "partly_applied"
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
