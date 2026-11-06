package com.rendyhd.vicu.ui.screens.guided

import com.rendyhd.vicu.ai.ask.Proposal
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.ask.TaskChange
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.first

/**
 * The writes of a guided session (docs/cross-app-semantics-v1.md, section 14.3), through the
 * usual paths: a card's changes as one update (a merge patch, then the project move, queued when
 * offline), completing, deleting, and Undo all from the session's [GuidedLog].
 */
class GuidedWriter(
    private val tasks: TaskRepository,
    private val proposals: Proposals,
    private val dayClock: DayClock,
) {
    /** Writes [change]; false when it did not go through. */
    suspend fun write(change: TaskChange): Boolean {
        val applied = proposals.apply(Proposal.Update(CALL_ID, listOf(change), emptyList()), dayClock.day.value.zone)
        return applied.appliedIds.isNotEmpty()
    }

    suspend fun complete(task: Task): Boolean = tasks.setDone(task.id, true) is NetworkResult.Success

    suspend fun delete(task: Task): Boolean = tasks.delete(task.id) is NetworkResult.Success

    /** A new task named [title] in [projectId] (a review's next step); null when it was refused. */
    suspend fun create(title: String, projectId: Long): Task? =
        (tasks.createQueued(Task(id = 0, title = title, projectId = projectId)) as? NetworkResult.Success)?.data

    /** Undo all of a task [create] made. */
    suspend fun deleteCreated(taskId: Long) {
        tasks.delete(taskId)
    }

    /** Puts back every change of [log] except deletes, the latest first. */
    suspend fun undo(log: GuidedLog) {
        for (entry in log.all.reversed()) {
            if (entry.deleted) continue
            val before = entry.before
            if (entry.completed) tasks.setDone(before.id, false)
            val now = tasks.getById(before.id).first() ?: continue
            if (now.projectId != before.projectId) tasks.moveToProject(now.id, before.projectId)
            val sameDue = DateUtils.parseIsoDate(now.dueDate) == DateUtils.parseIsoDate(before.dueDate)
            if (now.title != before.title || !sameDue) {
                // A due date moves or removes a block on its day (contract 16.3): the old block goes back with it.
                tasks.update(
                    now.copy(
                        title = before.title,
                        dueDate = before.dueDate,
                        startDate = before.startDate,
                        endDate = before.endDate,
                        reminders = before.reminders,
                    ),
                )
            }
        }
    }

    private companion object {
        const val CALL_ID = "guided"
    }
}
