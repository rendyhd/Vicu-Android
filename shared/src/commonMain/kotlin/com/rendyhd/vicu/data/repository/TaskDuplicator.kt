package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RelationKind
import kotlinx.coroutines.flow.first

/**
 * A copy made by [TaskDuplicator], and whether every label, person and subtask came along. [made]
 * is every task it created, the copy first and then its copied subtasks, for Undo.
 */
data class Duplicated(val copy: Task, val complete: Boolean, val made: List<Long> = listOf(copy.id))

/**
 * Duplicate (docs/cross-app-semantics-v1.md section 15): a new open task in the same project with
 * the original's fields, labels, assignees (on a shared project) and subtasks. Everything goes
 * through the ordinary create, label and assign paths, so it works offline like any new task.
 */
class TaskDuplicator(
    private val tasks: TaskRepository,
    private val labels: LabelRepository,
    private val collaboration: CollaborationRepository,
) {
    suspend fun duplicate(task: Task): NetworkResult<Duplicated> {
        val shared = collaboration.state.first().access(task.projectId).shared
        val copy = when (val created = tasks.create(copyOf(task))) {
            is NetworkResult.Success -> created.data
            is NetworkResult.Error -> return created
            is NetworkResult.Loading -> return NetworkResult.Error("Could not duplicate the task")
        }
        val made = mutableListOf(copy.id)
        val complete = followUp(task, copy, shared, visited = mutableSetOf(task.id), made = made)
        return NetworkResult.Success(Duplicated(copy, complete, made))
    }

    /**
     * Undo of Duplicate: deletes every task the copies made, subtasks before their parents, so the
     * originals' subtasks are never touched. Works offline like any delete (a delete of a queued
     * create drops the create). Returns the first refusal, or success.
     */
    suspend fun remove(copies: List<Duplicated>): NetworkResult<Unit> {
        var refused: NetworkResult.Error? = null
        for (id in copies.flatMap { it.made }.asReversed()) {
            val result = tasks.delete(id, deleteSubtasks = false)
            if (result is NetworkResult.Error && refused == null) refused = result
        }
        return refused ?: NetworkResult.Success(Unit)
    }

    /** Labels, people and subtasks of [original] onto [copy]; false when any of them failed. */
    private suspend fun followUp(
        original: Task,
        copy: Task,
        shared: Boolean,
        visited: MutableSet<Long>,
        made: MutableList<Long>,
    ): Boolean {
        var complete = true
        for (label in original.labels) {
            complete = labels.addToTask(copy.id, label.id).ok("label ${label.id}") && complete
        }
        if (shared) {
            for (person in original.assignees) {
                complete = collaboration.setAssigned(copy, person, assigned = true).ok("assignee ${person.id}") && complete
            }
        }
        for (child in subtasksOf(original)) {
            // A relation cycle would never end.
            if (!visited.add(child.id)) continue
            when (val created = tasks.createSubtask(copy.id, copyOf(child))) {
                is NetworkResult.Success -> {
                    made += created.data.id
                    complete = followUp(child, created.data, shared, visited, made) && complete
                }
                else -> complete = created.ok("subtask ${child.id}") && complete
            }
        }
        return complete
    }

    /** The full cached subtasks, in the parent's order; a subtask that is not cached as it is listed. */
    private suspend fun subtasksOf(task: Task): List<Task> {
        val listed = task.relatedTasks[RelationKind.SUBTASK].orEmpty()
        if (listed.isEmpty()) return emptyList()
        val cached = tasks.getByIds(listed.mapTo(mutableSetOf()) { it.id }).associateBy { it.id }
        return listed.map { cached[it.id] ?: it }
    }

    private fun NetworkResult<*>.ok(what: String): Boolean {
        if (this !is NetworkResult.Error) return true
        Logger.w(TAG, "Could not copy $what: $message")
        return false
    }

    companion object {
        private const val TAG = "TaskDuplicator"

        /** What a copy takes from [task] (section 15): always open, no comments, attachments or position. */
        fun copyOf(task: Task): Task = Task(
            id = 0,
            title = task.title,
            description = task.description,
            dueDate = task.dueDate,
            startDate = task.startDate,
            endDate = task.endDate,
            priority = task.priority,
            projectId = task.projectId,
            repeatAfter = task.repeatAfter,
            repeatMode = task.repeatMode,
            reminders = task.reminders,
        )
    }
}
