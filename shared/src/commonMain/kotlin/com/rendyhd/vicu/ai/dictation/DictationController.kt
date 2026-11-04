package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TextTaskCreator
import com.rendyhd.vicu.util.postIfRefused
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.voice.HoldToTalk
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

enum class DictationPhase {
    /** The microphone is on and nothing went to the model yet (smart dictation's lock, section 4); in a brain dump, the dump grows. */
    LISTENING,

    /** The model is reading the transcript (a brain dump: organizing it). */
    THINKING,

    /** Draft cards are on screen (a brain dump: the plan). */
    REVIEW,

    /** The request failed: the transcript waits with Retry and Add as one task. */
    FAILED,

    /** The checks left nothing to add. */
    EMPTY,
}

/** What the draft sheet shows (`docs/ai-assistant-v1.md`, sections 4.4 to 4.6). */
data class DictationUi(
    val phase: DictationPhase,
    val transcript: String,
    val defaults: TaskDefaults,
    val parentTaskId: Long? = null,
    val drafts: List<CheckedDraft> = emptyList(),
    val dropped: List<DroppedField> = emptyList(),
    val unclear: List<String> = emptyList(),
    /** Per draft ref, the fields the last voice edit changed (highlighted on the card). */
    val changed: Map<String, Set<String>> = emptyMap(),
    val context: DictationContext? = null,
    val error: String? = null,
    /** A voice instruction is being applied to the drafts. */
    val editing: Boolean = false,
    val adding: Boolean = false,
    /** Brain dump (section 4.8): the transcript is the dump, sent only by Organize. */
    val brainDump: Boolean = false,
    /** A brain dump's new projects, parents first. */
    val projects: List<CheckedProject> = emptyList(),
    /** The refs of the new projects the last Update added or changed. */
    val changedProjects: Set<String> = emptySet(),
    /** What was said or typed after a brain dump's plan, waiting for Update. */
    val pending: String = "",
) {
    /** Something to add: drafts, or a brain dump's projects on their own. */
    val hasPlan: Boolean get() = drafts.isNotEmpty() || projects.isNotEmpty()
}

/**
 * Smart dictation's flow across the app: a transcript comes in (holding +, Make tasks from this,
 * Break into steps), the model's drafts are checked and shown, the user changes them by hand or by
 * voice, and Add creates them with one Undo. A failed transcript is kept until added or discarded.
 */
class DictationController(
    private val service: DictationService,
    private val writer: DraftWriter,
    private val planWriter: PlanWriter,
    private val kept: KeptTranscriptStore,
    private val textTasks: TextTaskCreator,
    private val tasks: TaskRepository,
    private val messages: AppMessages,
    private val dayClock: DayClock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<DictationUi?>(null)
    val state: StateFlow<DictationUi?> = _state.asStateFlow()

    private var job: Job? = null

    val isOpen: Boolean get() = _state.value != null

    /** A request runs: the model reads the transcript or applies an edit. */
    val isBusy: Boolean get() = _state.value?.let { it.phase == DictationPhase.THINKING || it.editing } == true

    suspend fun isReady(): Boolean = service.isReady()

    /** At app start: a transcript kept from a failed dictation comes back with its two choices. */
    fun restoreKept() {
        scope.launch {
            val k = kept.current() ?: return@launch
            if (_state.value != null) return@launch
            _state.value = DictationUi(
                phase = DictationPhase.FAILED,
                transcript = k.transcript,
                defaults = TaskDefaults(k.projectId, k.dueDate, k.listName),
                parentTaskId = k.parentTaskId,
                error = if (k.brainDump) "This brain dump was not organized yet." else "This dictation was not turned into tasks yet.",
                brainDump = k.brainDump,
            )
        }
    }

    /** Opens the sheet with the microphone on and nothing said yet, for the list [defaults] describes. */
    fun listen(defaults: TaskDefaults, brainDump: Boolean = false) {
        if (_state.value == null) _state.value = DictationUi(DictationPhase.LISTENING, "", defaults, brainDump = brainDump)
    }

    /** The Brain dump switch, offered while nothing was sent (section 4.8). */
    fun setBrainDump(on: Boolean) {
        _state.update { s -> if (s?.phase == DictationPhase.LISTENING) s.copy(brainDump = on) else s }
    }

    /**
     * A turn of a brain dump: added to the end of the dump, or after the plan to the words that wait
     * for Update. False when the dump is as long as one can be (the words that fit are kept).
     */
    fun addWords(text: String): Boolean {
        if (text.isBlank()) return true
        var fits = true
        _state.update { s ->
            when {
                s == null || !s.brainDump -> s
                s.phase == DictationPhase.LISTENING || s.phase == DictationPhase.FAILED -> {
                    val dump = HoldToTalk.joinWords(s.transcript, text)
                    fits = dump.length <= maxDump
                    s.copy(transcript = dump.takeUtf16(maxDump))
                }
                else -> s.copy(pending = HoldToTalk.joinWords(s.pending, text).takeUtf16(maxDump))
            }
        }
        return fits
    }

    /** The dump as the person typed or corrected it. */
    fun setDump(text: String) {
        _state.update { s -> if (s?.brainDump == true && s.phase == DictationPhase.LISTENING) s.copy(transcript = text.takeUtf16(maxDump)) else s }
    }

    /** What waits for Update, as the person typed or corrected it. */
    fun setPending(text: String) {
        _state.update { s -> if (s?.brainDump == true) s.copy(pending = text.takeUtf16(maxDump)) else s }
    }

    /** Organize: the whole dump in one request (section 4.8.1). */
    fun organize(): Job? {
        val s = _state.value ?: return null
        if (!s.brainDump || s.transcript.isBlank() || s.phase == DictationPhase.THINKING || s.phase == DictationPhase.REVIEW) return null
        _state.value = s.copy(phase = DictationPhase.THINKING, error = null)
        return run()
    }

    /** Back to the dump to change or extend it: Edit after a failure, or Cancel while organizing. */
    fun backToDump() {
        job?.cancel()
        job = null
        _state.update { s ->
            if (s == null || !s.brainDump || s.phase == DictationPhase.REVIEW) {
                s
            } else {
                s.copy(phase = DictationPhase.LISTENING, transcript = HoldToTalk.joinWords(s.transcript, s.pending), pending = "", error = null)
            }
        }
    }

    /** Update: what was said or typed since the plan, as an edit of it (section 4.8.4). */
    fun update(): Job? {
        val current = _state.value ?: return null
        val context = current.context ?: return null
        val words = current.pending.trim()
        if (!current.brainDump || current.phase != DictationPhase.REVIEW || words.isEmpty() || current.editing) return null
        _state.value = current.copy(editing = true, pending = "")
        job?.cancel()
        return scope.launch {
            try {
                val plan = CheckResult(current.drafts, emptyList(), emptyList(), current.projects)
                val result = service.runBrainDump(words, context, plan)
                val before = current.drafts.associateBy { it.ref }
                val changed = result.drafts.associate { d -> d.ref to changedFields(before[d.ref], d) }.filterValues { it.isNotEmpty() }
                val projectsBefore = current.projects.associateBy { it.ref }
                _state.update {
                    it?.copy(
                        phase = if (result.drafts.isEmpty() && result.projects.isEmpty()) DictationPhase.EMPTY else DictationPhase.REVIEW,
                        drafts = result.drafts,
                        projects = result.projects,
                        dropped = result.dropped,
                        unclear = result.unclear,
                        changed = changed,
                        changedProjects = result.projects.filter { p -> projectsBefore[p.ref] != p }.mapTo(HashSet()) { p -> p.ref },
                        editing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The words wait again, before anything said since.
                _state.update { it?.copy(editing = false, pending = HoldToTalk.joinWords(words, it.pending)) }
                messages.post((e as? LlmException)?.message ?: "Could not change the plan.")
            }
        }.also { job = it }
    }

    fun renameProject(ref: String, title: String) {
        val name = DictationChecks.oneLine(title)
        _state.update { s -> s?.copy(projects = s.projects.map { if (it.ref == ref) it.copy(title = name) else it }) }
    }

    /** Removes a new project: its tasks and new subprojects go to its parent (section 4.8.5). */
    fun removeProject(ref: String) {
        _state.update { s ->
            if (s == null) return@update null
            val defaultProject = s.context?.defaultProjectId ?: s.drafts.firstOrNull()?.projectId ?: 0L
            val (projects, drafts) = BrainDumpChecks.removeProject(ref, s.projects, s.drafts, defaultProject)
            s.copy(projects = projects, drafts = drafts, phase = if (drafts.isEmpty() && projects.isEmpty()) DictationPhase.EMPTY else s.phase)
        }
    }

    fun start(transcript: String, defaults: TaskDefaults, parentTaskId: Long? = null): Job? {
        val text = transcript.trim()
        if (text.isEmpty()) return null
        _state.value = DictationUi(DictationPhase.THINKING, text, defaults, parentTaskId)
        return run()
    }

    fun retry() {
        val current = _state.value ?: return
        _state.value = current.copy(phase = DictationPhase.THINKING, error = null)
        run()
    }

    private fun run(): Job {
        job?.cancel()
        return scope.launch {
            val current = _state.value ?: return@launch
            try {
                val context = service.context(current.defaults)
                val result = if (current.brainDump) service.runBrainDump(current.transcript, context) else service.run(current.transcript, context)
                kept.clear()
                _state.update {
                    it?.copy(
                        phase = if (result.drafts.isEmpty() && result.projects.isEmpty()) DictationPhase.EMPTY else DictationPhase.REVIEW,
                        drafts = result.drafts,
                        projects = result.projects,
                        changedProjects = emptySet(),
                        dropped = result.dropped,
                        unclear = result.unclear,
                        context = context,
                        changed = emptyMap(),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Words said while a brain dump was being organized join the dump.
                val failed = _state.updateAndGet {
                    it?.copy(
                        phase = DictationPhase.FAILED,
                        error = (e as? LlmException)?.message ?: "The model did not answer.",
                        transcript = HoldToTalk.joinWords(it.transcript, it.pending),
                        pending = "",
                    )
                } ?: return@launch
                kept.keep(KeptTranscript(failed.transcript, failed.defaults.listName, failed.defaults.projectId, failed.defaults.dueDate, failed.parentTaskId, failed.brainDump))
            }
        }.also { job = it }
    }

    /** What the person said next about the drafts on screen: changes, more tasks, or both (section 4.4). */
    fun edit(instruction: String): Job? {
        val current = _state.value ?: return null
        val context = current.context ?: return null
        if (current.phase != DictationPhase.REVIEW || instruction.isBlank()) return null
        _state.value = current.copy(editing = true)
        job?.cancel()
        return scope.launch {
            try {
                val result = service.run(instruction.trim(), context, current.drafts)
                val before = current.drafts.associateBy { it.ref }
                val changed = result.drafts.associate { d -> d.ref to changedFields(before[d.ref], d) }.filterValues { it.isNotEmpty() }
                _state.update {
                    it?.copy(
                        phase = if (result.drafts.isEmpty()) DictationPhase.EMPTY else DictationPhase.REVIEW,
                        drafts = result.drafts,
                        dropped = result.dropped,
                        unclear = result.unclear,
                        changed = changed,
                        editing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it?.copy(editing = false) }
                messages.post((e as? LlmException)?.message ?: "Could not change the drafts.")
            }
        }.also { job = it }
    }

    /**
     * Talking again cancelled the request still running (section 3.7): its words go out with the
     * next turn, so the sheet goes back to listening, or to the drafts as they were.
     */
    fun cancelRequest() {
        job?.cancel()
        job = null
        _state.update { s ->
            when {
                s == null -> null
                s.phase == DictationPhase.THINKING -> s.copy(phase = DictationPhase.LISTENING, transcript = "")
                s.editing -> s.copy(editing = false)
                else -> s
            }
        }
    }

    fun updateDraft(ref: String, change: (CheckedDraft) -> CheckedDraft) {
        _state.update { s -> s?.copy(drafts = s.drafts.map { if (it.ref == ref) change(it) else it }) }
    }

    fun removeDraft(ref: String) {
        _state.update { s ->
            val drafts = s?.drafts?.filterNot { it.ref == ref } ?: return@update s
            s.copy(drafts = drafts, phase = if (drafts.isEmpty() && s.projects.isEmpty()) DictationPhase.EMPTY else s.phase)
        }
    }

    /** Add: creates the drafts in order, one message with Undo. */
    fun add() {
        val current = _state.value ?: return
        if (current.brainDump) return addPlan(current)
        if (current.drafts.isEmpty() || current.adding) return
        _state.value = current.copy(adding = true)
        scope.launch {
            val zone = dayClock.day.value.zone
            val added = writer.add(current.drafts, zone, current.parentTaskId)
            _state.value = null
            kept.clear()
            val n = added.taskIds.size
            when {
                n == 0 -> messages.post(added.firstError ?: "Could not add the tasks.")
                else -> {
                    val text = (if (n == 1) "Added 1 task" else "Added $n tasks") +
                        if (added.failed > 0) ", ${added.failed} failed" else ""
                    messages.post(text, "Undo", openTaskId = added.taskIds.singleOrNull()) { scope.launch { writer.undo(added) } }
                }
            }
        }
    }

    /** A brain dump's Add (section 4.8.5): the projects, then the tasks, with one Undo. */
    private fun addPlan(current: DictationUi) {
        if (!current.hasPlan || current.adding || current.editing) return
        if (current.projects.any { it.title.isBlank() }) {
            messages.post("Give every new project a name first.")
            return
        }
        _state.value = current.copy(adding = true)
        scope.launch {
            val added = planWriter.add(CheckResult(current.drafts, emptyList(), emptyList(), current.projects), dayClock.day.value.zone)
            if (added.error != null) {
                _state.update { it?.copy(adding = false) }
                messages.post(added.error)
                return@launch
            }
            _state.value = null
            kept.clear()
            val tasks = added.drafts?.taskIds?.size ?: 0
            val failed = added.drafts?.failed ?: 0
            messages.post(
                addedText(added.projectIds.size, tasks) + if (failed > 0) ", $failed failed" else "",
                "Undo",
                openTaskId = added.drafts?.taskIds?.singleOrNull(),
            ) {
                scope.launch { planWriter.undo(added) }
            }
        }
    }

    /** Add as one task: the transcript through the quick-add parser, as dictation did before. */
    fun addAsOneTask(format: DateDisplayFormat) {
        val current = _state.value ?: return
        _state.value = null
        scope.launch {
            kept.clear()
            when (val result = textTasks.create(current.transcript, format, current.defaults.projectId, current.defaults.dueDate)) {
                is TextTaskCreator.Result.Created -> messages.post(result.confirmation, "Undo", openTaskId = result.task.id) {
                    scope.launch { messages.postIfRefused(tasks.delete(result.task.id), "undo the task") }
                }
                is TextTaskCreator.Result.Failed -> messages.post(result.message)
            }
        }
    }

    fun discard() {
        job?.cancel()
        _state.value = null
        scope.launch { kept.clear() }
    }

    private fun changedFields(before: CheckedDraft?, after: CheckedDraft): Set<String> {
        if (before == null) return setOf("new")
        val fields = mutableSetOf<String>()
        if (before.title != after.title) fields += "title"
        if (before.notes != after.notes) fields += "notes"
        if (before.due != after.due || before.dueHasTime != after.dueHasTime) fields += "when"
        if (before.projectId != after.projectId || before.projectRef != after.projectRef) fields += "project"
        if (before.labels != after.labels) fields += "labels"
        if (before.priority != after.priority) fields += "priority"
        if (before.repeat != after.repeat) fields += "repeat"
        if (before.reminder != after.reminder) fields += "reminder"
        if (before.place != after.place) fields += "place"
        if (before.subtasks != after.subtasks) fields += "subtasks"
        return fields
    }

    companion object {
        private val maxDump get() = AiContract.limits.transcriptMaxChars

        /** "Added 2 projects and 14 tasks" (section 4.8.5). */
        fun addedText(projects: Int, tasks: Int): String {
            val p = if (projects == 1) "1 project" else "$projects projects"
            val t = if (tasks == 1) "1 task" else "$tasks tasks"
            return when {
                projects == 0 -> "Added $t"
                tasks == 0 -> "Added $p"
                else -> "Added $p and $t"
            }
        }
    }
}
