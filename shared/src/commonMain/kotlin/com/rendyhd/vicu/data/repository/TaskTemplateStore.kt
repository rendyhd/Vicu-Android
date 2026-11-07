package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DefaultReminder
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.Permission
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.SubtaskOrder
import com.rendyhd.vicu.util.TaskTemplate
import com.rendyhd.vicu.util.TaskTemplates
import com.rendyhd.vicu.util.TemplateSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A task made by [TaskTemplateStore.make], and whether every label and subtask came along. */
data class MadeFromTemplate(val task: Task, val complete: Boolean)

/**
 * Task templates (docs/cross-app-semantics-v1.md section 18): each one a synced preference of the
 * account, and tasks made from them through the ordinary create, label and subtask paths, so it
 * works offline like any new task.
 */
class TaskTemplateStore(
    private val synced: SyncedEntries,
    private val tasks: TaskRepository,
    private val labels: LabelRepository,
    private val projects: ProjectRepository,
    private val notificationPrefs: NotificationPrefsStore,
    private val dayClock: DayClock,
    private val time: TimeSource,
) {
    /** The account's templates, oldest first. */
    val templates: Flow<List<TaskTemplate>> = synced.entries
        .map(TaskTemplates::fromEntries)
        .distinctUntilChanged()

    /** Saves [task] as a new template; null when it is too large to keep. */
    suspend fun saveFrom(task: Task): TaskTemplate? {
        val day = dayClock.day.value
        val template = TaskTemplates.capture(sourceOf(task, mutableSetOf(task.id)), day.date, day.zone)
            .copy(id = randomUuid(), createdMs = time.now().toEpochMilliseconds())
        if (TaskTemplates.isTooLarge(template)) return null
        put(template)
        return template
    }

    /** Writes [template]; false when it has grown too large to keep. */
    suspend fun put(template: TaskTemplate): Boolean {
        if (TaskTemplates.isTooLarge(template)) return false
        synced.set(SyncedPrefKeys.templateOf(template.id), TaskTemplates.encode(template))
        return true
    }

    suspend fun remove(template: TaskTemplate) {
        synced.set(SyncedPrefKeys.templateOf(template.id), null)
    }

    /** Makes a task from [template], in [fallbackProjectId] when its own project is out of reach. */
    suspend fun make(template: TaskTemplate, fallbackProjectId: Long): NetworkResult<MadeFromTemplate> {
        val day = dayClock.day.value
        val writable = projects.getAll().first()
            .filter { it.maxPermission < 0 || it.maxPermission >= Permission.WRITE }
            .mapTo(mutableSetOf()) { it.id }
        val projectId = TaskTemplates.projectFor(template, writable, fallbackProjectId)
        var task = TaskTemplates.taskOf(template, projectId, day.date, day.zone)
        if (task.reminders.isEmpty()) {
            val prefs = notificationPrefs.getPrefs().first()
            DefaultReminder.build(task.dueDate, prefs.defaultReminderOffset, prefs.defaultReminderRelativeTo)
                ?.let { task = task.copy(reminders = listOf(it)) }
        }
        val made = when (val created = tasks.create(task)) {
            is NetworkResult.Success -> created.data
            is NetworkResult.Error -> return created
            is NetworkResult.Loading -> return NetworkResult.Error("Could not make the task")
        }
        val known = labels.getAll().first().mapTo(mutableSetOf()) { it.id }
        val complete = followUp(template, made, known)
        return NetworkResult.Success(MadeFromTemplate(made, complete))
    }

    private suspend fun followUp(template: TaskTemplate, task: Task, known: Set<Long>): Boolean {
        val day = dayClock.day.value
        var complete = true
        for (label in template.labels) {
            if (label !in known) continue
            if (labels.addToTask(task.id, label) is NetworkResult.Error) complete = false
        }
        for (subtask in template.subtasks) {
            val child = TaskTemplates.taskOf(subtask, task.projectId, day.date, day.zone)
            when (val made = tasks.createSubtask(task.id, child)) {
                is NetworkResult.Success -> complete = followUp(subtask, made.data, known) && complete
                else -> complete = false
            }
        }
        return complete
    }

    /** [task] with its subtasks as cached, in the parent's order; a relation cycle stops there. */
    private suspend fun sourceOf(task: Task, visited: MutableSet<Long>): TemplateSource {
        val listed = task.relatedTasks[RelationKind.SUBTASK].orEmpty()
        if (listed.isEmpty()) return TemplateSource(task)
        val cached = tasks.getByIds(listed.mapTo(mutableSetOf()) { it.id }).associateBy { it.id }
        val order = SubtaskOrder.read(synced.entries.first()[SyncedPrefKeys.subtaskOrderOf(task.id)])
        val children = SubtaskOrder.sorted(listed, order) { it.id }
            .filter { visited.add(it.id) }
            .map { sourceOf(cached[it.id] ?: it, visited) }
        return TemplateSource(task, children)
    }
}
