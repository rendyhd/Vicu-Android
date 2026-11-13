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

    /**
     * Puts back every change of [log] except deletes, the latest first. Only what the session
     * wrote goes back (the title, the project, the due date); a field changed on another device
     * meanwhile, or one the session never touched, is left as it is now (review C-9).
     */
    suspend fun undo(log: GuidedLog) {
        for (entry in log.all.reversed()) {
            if (entry.deleted) continue
            val before = entry.before
            val after = entry.after
            if (entry.completed) tasks.setDone(before.id, false)
            val now = tasks.getById(before.id).first() ?: continue
            if (before.projectId != after.projectId && now.projectId != before.projectId) {
                tasks.moveToProject(now.id, before.projectId)
            }
            val restored = restoreWritten(now, before, after)
            if (restored != now) tasks.update(restored)
        }
    }

    private companion object {
        const val CALL_ID = "guided"

        /**
         * [now] with the fields the session changed from [before] to [after] put back: the title
         * and the due date. A due date moves or removes a block on its day (contract 16.3), so
         * the block of the old date and its reminders come back with it.
         */
        fun restoreWritten(now: Task, before: Task, after: Task): Task {
            var restored = now
            if (before.title != after.title) restored = restored.copy(title = before.title)
            val dueChanged = DateUtils.parseIsoDate(before.dueDate) != DateUtils.parseIsoDate(after.dueDate)
            if (dueChanged && DateUtils.parseIsoDate(now.dueDate) != DateUtils.parseIsoDate(before.dueDate)) {
                restored = restored.copy(
                    dueDate = before.dueDate,
                    startDate = before.startDate,
                    endDate = before.endDate,
                    reminders = before.reminders,
                )
            }
            return restored
        }
    }
}
