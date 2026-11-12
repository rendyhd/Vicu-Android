package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProListView
import com.rendyhd.vicu.data.local.VerdictSnooze
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.postIfRefused
import com.rendyhd.vicu.util.pro.ProScreens
import com.rendyhd.vicu.util.pro.ProjectMode
import com.rendyhd.vicu.util.pro.Promises
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the Pro screens change (docs/cross-app-semantics-v1.md section 21), the desktop's
 * `usePromiseActions`, `useProPrefs` setters and the card chips' writes. Each runs on the app's
 * scope, so an Undo tapped after its screen has gone still works; a refused write says so in the
 * app-wide snackbar. Only Pro screens call these, and they only show while Pro is on.
 */
class ProActions(
    private val taskRepository: TaskRepository,
    private val labelRepository: LabelRepository,
    private val proLedger: ProLedgerRepository,
    private val proPreferences: ProPreferences,
    private val behaviorPrefs: BehaviorPrefsStore,
    private val appMessages: AppMessages,
    private val dayClock: DayClock,
    private val time: TimeSource,
    private val scope: CoroutineScope,
) {
    private suspend fun current(task: Task): Task = taskRepository.getByIds(setOf(task.id)).firstOrNull() ?: task

    /**
     * A verdict on a promise (decision 10): kept in the ledger; a final one completes the task
     * (`done` only), a new date moves the due date. Undo marks the verdict undone and puts the
     * task back.
     */
    fun verdict(task: Task, kind: String, newDue: LocalDate? = null, blocker: String? = null) {
        scope.launch {
            val before = proLedger.current().value("promises", task.id) ?: return@launch
            val zone = dayClock.day.value.zone
            val change = Promises.buildVerdict(task, kind, randomUuid(), time.now().toString(), newDue, blocker, zone)
            val verdictId = (change.verdict["id"] as JsonPrimitive).content
            proLedger.setRecord("promises", task.id.toString(), Promises.withVerdict(before, change.verdict))
            val completes = change.taskPatch?.containsKey("done") == true
            val newDueValue = (change.taskPatch?.get("due_date") as? JsonPrimitive)?.content
            val oldDue = task.dueDate
            when {
                completes -> appMessages.postIfRefused(taskRepository.setDone(task.id, true), "complete the task")
                newDueValue != null -> appMessages.postIfRefused(
                    taskRepository.update(current(task).copy(dueDate = newDueValue)),
                    "move the due date",
                )
            }
            appMessages.post("${ProScreens.verdictLabel(kind)}: ${task.title}", "Undo") {
                scope.launch {
                    proLedger.current().value("promises", task.id)?.let { now ->
                        proLedger.setRecord("promises", task.id.toString(), Promises.withVerdictUndone(now, verdictId))
                    }
                    when {
                        completes -> appMessages.postIfRefused(taskRepository.setDone(task.id, false), "reopen the task")
                        newDueValue != null -> appMessages.postIfRefused(
                            taskRepository.update(current(task).copy(dueDate = oldDue.ifBlank { Constants.NULL_DATE_STRING })),
                            "put the due date back",
                        )
                    }
                }
            }
        }
    }

    /** "Ask me tomorrow", once per due day (decision 10); per device. */
    fun snooze(task: Task) {
        scope.launch {
            val promise = proLedger.current().value("promises", task.id)
            val day = dayClock.day.value
            val entry = Promises.snoozeFor(task, promise, day.date, day.zone) ?: return@launch
            behaviorPrefs.setVerdictSnooze(task.id.toString(), VerdictSnooze(entry.due.toString(), entry.until.toString()))
        }
    }

    /** Makes [task] someone's promise (or yours to them); null removes the promise. [retitle] renames the task too. */
    fun setPromise(task: Task, value: JsonObject?, retitle: String? = null) {
        scope.launch {
            proLedger.setRecord("promises", task.id.toString(), value)
            val person = (value?.get("person") as? JsonPrimitive)?.content?.toLongOrNull()
            // The task carries the person's label, so devices without Pro still see who it is about.
            if (person != null && task.labels.none { it.id == person }) {
                appMessages.postIfRefused(labelRepository.addToTask(task.id, person), "add the person's label")
            }
            if (retitle != null && retitle.isNotBlank() && retitle != task.title) {
                appMessages.postIfRefused(taskRepository.update(current(task).copy(title = retitle)), "rename the task")
            }
        }
    }

    /** A task's estimate in minutes; null removes it. Kept in the ledger. */
    fun setEstimate(taskId: Long, minutes: Int?) {
        scope.launch { proLedger.setEstimate(taskId, minutes?.coerceIn(1, 1440)) }
    }

    /** "After": [task] waits on [otherId] (a blocked relation, queued offline), or no longer does. */
    fun setAfter(task: Task, otherId: Long, add: Boolean) {
        scope.launch {
            val result = if (add) {
                taskRepository.createRelation(task.id, otherId, RelationKind.BLOCKED)
            } else {
                taskRepository.deleteRelation(task.id, RelationKind.BLOCKED, otherId)
            }
            appMessages.postIfRefused(result, if (add) "add the task to wait on" else "remove the task to wait on")
        }
    }

    /** Copying a recap marks its promises as sent (decision 7). */
    suspend fun markSent(taskIds: List<Long>) {
        if (taskIds.isEmpty()) return
        val at = time.now().toString()
        proLedger.change { ledger, deviceId, nowMs ->
            var next = ledger
            for (id in taskIds) {
                val promise = next.value("promises", id) ?: continue
                next = com.rendyhd.vicu.util.pro.LedgerRules.setRecord(next, "promises", id.toString(), Promises.withSent(promise, at), deviceId, nowMs)
            }
            next
        }
    }

    fun setProjectMode(projectId: Long, mode: ProjectMode) {
        scope.launch { proPreferences.setProjectMode(projectId, mode) }
    }

    fun setProjectOnHold(projectId: Long, held: Boolean) {
        scope.launch { proPreferences.setProjectOnHold(projectId, held) }
    }

    fun setLabelOnHold(labelId: Long, held: Boolean) {
        scope.launch { proPreferences.setLabelOnHold(labelId, held) }
    }

    fun setPerson(labelId: Long, person: Boolean) {
        scope.launch { proPreferences.setPerson(labelId, person) }
    }

    fun setArea(categoryIds: List<String>) {
        scope.launch { proPreferences.setArea(categoryIds) }
    }

    /** What a list shows (Available, Remaining or All), per device and list. */
    fun setListView(listKey: String, view: ProListView) {
        scope.launch { behaviorPrefs.setProListView(listKey, view) }
    }
}
