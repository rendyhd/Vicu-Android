package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.PlatformFiles
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.repository.Duplicated
import com.rendyhd.vicu.data.repository.TaskDuplicator
import com.rendyhd.vicu.data.repository.SubtaskOrderPreferences
import com.rendyhd.vicu.data.repository.TaskTemplateStore
import com.rendyhd.vicu.data.repository.ProCapture
import com.rendyhd.vicu.data.repository.ProCaptureSource
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.WaitingUpload
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.repository.AttachResult
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.util.WaitingUploadText
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.screens.shared.collectSearchRefresh
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DescriptionHtml
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.SubtaskOrder
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.unfinishedDescendants
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.WhenQuickChoice
import com.rendyhd.vicu.util.postIfRefused
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.extractBangToday
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TaskDetailUiState(
    val requestedTaskId: Long = 0L,
    /** The temporary id the editor was opened with, after it moved to the server's id ([requestedTaskId]). */
    val followedFromTaskId: Long = 0L,
    val task: Task? = null,
    val originalTask: Task? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val allProjects: List<Project> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    val subtasks: List<Task> = emptyList(),
    val relations: Map<String, List<Task>> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    /** Files attached while the server could not be reached: waiting to upload, or refused. */
    val waitingUploads: List<WaitingUpload> = emptyList(),
    val showDeleteConfirmation: Boolean = false,
    val pendingSubtaskCompletion: Task? = null,
    /** Asking whether to complete the open task together with its unfinished subtasks. */
    val showCompleteConfirmation: Boolean = false,
    val isDeleted: Boolean = false,
    val inboxProjectId: Long = 0L,
    val isUploadingImage: Boolean = false,
    val isUploadingAttachment: Boolean = false,
    /** Attachments being deleted: hidden until the server answers, back again if it refuses. */
    val deletingAttachmentIds: Set<Long> = emptySet(),
    /** Attachments being downloaded to open or share. */
    val downloadingAttachmentIds: Set<Long> = emptySet(),
    val parseResult: ParseResult? = null,
    val parserConfig: ParserConfig = ParserConfig(),
    val suppressedTypes: Set<TokenType> = emptySet(),
    val manuallyEditedTypes: Set<TokenType> = emptySet(),
    /** Pro's phrases and people (contract 5.5); off unless Pro is on here. */
    val proCapture: ProCapture = ProCapture.OFF,
)

class TaskDetailViewModel(
    private val taskRepository: TaskRepository,
    private val labelRepository: LabelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val projectRepository: ProjectRepository,
    private val authManager: AuthManager,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val nlpPrefsStore: NlpPrefsStore,
    private val platformFiles: PlatformFiles,
    private val appMessages: AppMessages,
    private val dayClock: DayClock,
    private val tempIds: TempIdGenerator? = null,
    private val taskDuplicator: TaskDuplicator? = null,
    private val templateStore: TaskTemplateStore? = null,
    private val subtaskOrders: SubtaskOrderPreferences? = null,
    /** Pro's phrases while Pro is on; none in tests. */
    private val proCaptureSource: ProCaptureSource? = null,
) : ViewModel() {

    companion object {
        private const val TAG = "TaskDetailVM"

        /** How long the editor must be idle before its changes are saved on their own. */
        internal const val AUTOSAVE_DELAY_MS = 1_000L
    }

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    /** Collectors started by loadTask; cancelled when a different task is loaded so a
     *  previously opened task's Room emissions can't overwrite the current task's state. */
    private val loadJobs = mutableListOf<Job>()

    /** Preserved link HTML stripped from description for display, re-appended on save. */
    private var preservedLinkHtml = ""
    private var preservedRoutineHtml = ""

    /**
     * The notes the editor's draft started from, as the server stored them (description format,
     * "Notes changed in two places"). It follows the stored notes while the draft has no unsaved notes
     * of its own, stays put while it has, and after a save is what the server answered with. A save
     * hands it to the repository, which reads the task first and keeps both versions when the notes
     * were changed on another device meanwhile.
     */
    private var notesBase: String? = null

    /** Raw token text retained when a parse-preview chip is dismissed. */
    private var suppressedRawTexts: Map<TokenType, List<String>> = emptyMap()

    /**
     * Bumped by every loadTask. A save remembers the value it started under and only touches the
     * editor state if it is still current, so a save that finishes after another task was opened
     * cannot overwrite that task.
     */
    private var loadGeneration = 0
    private var autosaveJob: Job? = null
    private val saveMutex = Mutex()

    /** Saves started and not yet finished, per task id. */
    private val savesInFlight = mutableMapOf<Long, Int>()

    /** What the most recent save of a task is sending, while it is in flight. */
    private val inFlightPayload = mutableMapOf<Long, Task>()

    /** Set while the task is being deleted so no save runs against a task that is going away. */
    private var discardEdits = false

    /** A draft restored after process death, applied to the first stored value that arrives. */
    private var restoredDraft: TaskDetailDraft? = null
    private var draftToApply: TaskDetailDraft? = null

    private val _relationSearchQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val relationSearchResults: StateFlow<List<Task>> = _relationSearchQuery
        .debounce(250)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else taskRepository.searchTasks(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // The server is asked about a relation search after the text rests, one request at a
        // time, and the one in flight is cancelled by a new text. The list reads Room only.
        viewModelScope.launch {
            _relationSearchQuery.collectSearchRefresh { text -> taskRepository.refreshAll(mapOf("q" to text)) }
        }
        viewModelScope.launch {
            nlpPrefsStore.config.collect { config ->
                _uiState.update { state ->
                    val newConfig = config.copy(suppressTypes = state.suppressedTypes)
                    state.copy(
                        parserConfig = newConfig,
                        parseResult = state.parseResult?.let {
                            state.task?.title?.takeIf(String::isNotBlank)?.let { title ->
                                parseTitle(title, newConfig, state.proCapture)
                            }
                        },
                    )
                }
            }
        }
        proCaptureSource?.let { source ->
            viewModelScope.launch {
                source.capture.collect { pro ->
                    _uiState.update { state ->
                        state.copy(
                            proCapture = pro,
                            parseResult = state.parseResult?.let {
                                state.task?.title?.takeIf(String::isNotBlank)?.let { title -> parseTitle(title, state.parserConfig, pro) }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun parseTitle(title: String, config: ParserConfig, pro: ProCapture): ParseResult =
        TaskParser.parse(title, pro.config(config), people = pro.parserPeople)

    fun loadTask(taskId: Long) {
        // Edits of the task being replaced are saved first. The request is captured now, before
        // the state is reset below; it finishes in the background and reports through the global
        // message channel because this editor session is gone by then.
        autosaveJob?.cancel()
        captureSaveRequest(final = true)?.let(::launchSave)
        loadGeneration++
        discardEdits = false
        draftToApply = restoredDraft?.takeIf { it.taskId == taskId }
        restoredDraft = null

        // This ViewModel is owned above the conditional detail screen and survives its dismissal.
        // Always restart the session so reopening the same task cannot reuse stale editor state.
        loadJobs.forEach { it.cancel() }
        loadJobs.clear()

        // Reset state for the new task so stale data from the previous task doesn't persist
        preservedLinkHtml = ""
        preservedRoutineHtml = ""
        notesBase = null
        suppressedRawTexts = emptyMap()
        _uiState.update {
            it.copy(
                requestedTaskId = taskId,
                followedFromTaskId = 0L,
                task = null,
                originalTask = null,
                isLoading = true,
                isDeleted = false,
                error = null,
                showCompleteConfirmation = false,
                parseResult = null,
                suppressedTypes = emptySet(),
                manuallyEditedTypes = emptySet(),
                parserConfig = it.parserConfig.copy(suppressTypes = emptySet()),
            )
        }

        collectTask(taskId)

        loadJobs += viewModelScope.launch {
            projectRepository.getAll().collect { projects ->
                _uiState.update { it.copy(allProjects = projects) }
            }
        }

        loadJobs += viewModelScope.launch {
            authManager.inboxProjectId.collect { inboxId ->
                _uiState.update { it.copy(inboxProjectId = inboxId ?: 0L) }
            }
        }

        loadJobs += viewModelScope.launch {
            labelRepository.getAll().collect { labels ->
                _uiState.update { it.copy(allLabels = labels) }
            }
        }

        collectAttachments(taskId)
    }

    /** Follows the stored task [taskId]: its edits from elsewhere, and the server's id once an offline create goes through. */
    private fun collectTask(taskId: Long) {
        loadJobs += viewModelScope.launch {
            taskRepository.getById(taskId).collect { task ->
                if (task != null) {
                    // While one of our own saves of this task is running the stored row changes
                    // under us (optimistic write, server answer, or a rollback if it fails).
                    // Those emissions are echoes of the save, not news: the baseline stays put
                    // until the save finishes, so a failed save leaves the edits marked as unsaved.
                    val ownSaveRunning = (savesInFlight[taskId] ?: 0) > 0
                    val subtasks = SubtaskOrder.sorted(
                        task.relatedTasks["subtask"] ?: emptyList(),
                        subtaskOrders?.orderOf(task.id).orEmpty(),
                    ) { it.id }
                    val relations = task.relatedTasks
                        .filterKeys { it in com.rendyhd.vicu.util.RelationKind.DISPLAYABLE }
                        .filterValues { it.isNotEmpty() }
                    val split = DescriptionHtml.splitForEditor(task.description)
                    val displayDesc = ImageTokens.buildValue(split.htmlBody, split.imageRefs)
                    val incoming = task.copy(description = displayDesc)
                    _uiState.update { state ->
                        // Embedded note/page links are not editable here. Always adopt their newest
                        // server value, even when the visible description has a local draft.
                        preservedLinkHtml = split.linkHtml
                        preservedRoutineHtml = split.routineHtml
                        val draft = draftToApply?.takeIf { state.task == null }
                        draftToApply = null
                        // Notes changed on another device while the draft has unsaved notes are not
                        // asked about: the draft keeps the notes it started from as its base, and the
                        // save keeps both versions.
                        val unsavedNotes = state.task != null && state.originalTask != null &&
                            state.task.description != state.originalTask.description
                        when {
                            draft != null && draft.edited.description != draft.base.description ->
                                notesBase = storedDescription(draft.base.description)
                            state.task == null || state.originalTask == null -> notesBase = task.description
                            // The save in flight sets it from the server's answer.
                            ownSaveRunning -> Unit
                            !unsavedNotes -> notesBase = task.description
                        }
                        val merged = if (state.task == null || state.originalTask == null) {
                            draft?.let { applyDraft(it, incoming) } ?: incoming
                        } else {
                            reconcileTaskEditor(state.task, state.originalTask, incoming)
                        }
                        state.copy(
                            task = merged,
                            originalTask = if (ownSaveRunning) state.originalTask ?: incoming else incoming,
                            // A row being dragged stays where the finger put it.
                            subtasks = if (subtaskDragging) state.subtasks else subtasks,
                            relations = relations,
                            isLoading = false,
                            parseResult = if (draft != null) {
                                merged.title.takeIf(String::isNotBlank)?.let { parseTitle(it, state.parserConfig, state.proCapture) }
                            } else {
                                state.parseResult
                            },
                            manuallyEditedTypes = if (draft != null) {
                                TokenType.entries.filter { it.name in draft.manuallyEditedTypes }.toSet()
                            } else {
                                state.manuallyEditedTypes
                            },
                        )
                    }
                } else if (!followRealId(taskId)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun collectAttachments(taskId: Long) {
        loadJobs += viewModelScope.launch {
            attachmentRepository.getByTaskId(taskId).collect { attachments ->
                _uiState.update { it.copy(attachments = attachments) }
            }
        }
        loadJobs += viewModelScope.launch {
            attachmentRepository.waitingUploads(taskId).collect { waiting ->
                _uiState.update { it.copy(waitingUploads = waiting) }
            }
        }

        loadJobs += viewModelScope.launch {
            attachmentRepository.refreshForTask(taskId)
        }
    }

    /**
     * When the temporary id [tempId] this editor shows has become a server id (its offline create
     * went through: the row was replaced), the editor moves to that id instead of losing the task.
     */
    private suspend fun followRealId(tempId: Long): Boolean {
        if (tempId >= 0 || _uiState.value.requestedTaskId != tempId) return false
        val realId = tempIds?.realIdFor(tempId) ?: return false
        _uiState.update { state ->
            state.copy(
                requestedTaskId = realId,
                followedFromTaskId = tempId,
                task = state.task?.copy(id = realId),
                originalTask = state.originalTask?.copy(id = realId),
            )
        }
        collectTask(realId)
        collectAttachments(realId)
        return true
    }

    fun updateTitle(rawTitle: String) {
        // A title is one line, whatever the keyboard or a paste delivered.
        val title = rawTitle.withoutLineBreaks()
        _uiState.update { state ->
            val activeSuppressed = state.suppressedTypes.filter { type ->
                val texts = suppressedRawTexts[type] ?: return@filter false
                texts.any { title.contains(it) }
            }.toSet()
            if (activeSuppressed != state.suppressedTypes) {
                suppressedRawTexts = suppressedRawTexts.filterKeys { it in activeSuppressed }
            }

            val config = state.parserConfig.copy(suppressTypes = activeSuppressed)
            state.copy(
                task = state.task?.copy(title = title),
                parseResult = title.takeIf(String::isNotBlank)?.let { parseTitle(it, config, state.proCapture) },
                suppressedTypes = activeSuppressed,
                parserConfig = config,
            )
        }
        scheduleAutosave()
    }

    fun suppressType(type: TokenType) {
        _uiState.update { state ->
            val result = state.parseResult ?: return@update state
            suppressedRawTexts = suppressedRawTexts + (
                type to result.tokens.filter { it.type == type }.map { it.raw }
            )
            val newSuppressed = state.suppressedTypes + type
            val config = state.parserConfig.copy(suppressTypes = newSuppressed)
            state.copy(
                parseResult = state.task?.title?.takeIf(String::isNotBlank)?.let {
                    parseTitle(it, config, state.proCapture)
                },
                suppressedTypes = newSuppressed,
                parserConfig = config,
            )
        }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(task = it.task?.copy(description = description)) }
        scheduleAutosave()
    }

    fun setDueDate(dueDate: String) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(dueDate = dueDate),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.DATE,
            )
        }
        scheduleAutosave()
    }

    /**
     * A quick move of the editor: the due date goes to [choice]'s day with its time of day kept, and
     * the app snackbar offers Undo. Undo puts the previous due date back, in the stored task when the
     * editor has moved on to another task or closed.
     */
    fun moveDue(choice: WhenQuickChoice) {
        val task = _uiState.value.task ?: return
        setDueDate(movedDue(task.dueDate, choice.date, dayClock.day.value.zone))
        appMessages.post(quickMoveMessage(choice), "Undo") { restoreDue(task) }
    }

    /** Back to [before]: its due date and the block, which followed the due date (contract 16.3). */
    private fun restoreDue(before: Task) {
        fun Task.restored() = copy(
            dueDate = before.dueDate,
            startDate = before.startDate,
            endDate = before.endDate,
            reminders = before.reminders,
        )
        if (_uiState.value.task?.id == before.id) {
            _uiState.update {
                it.copy(task = it.task?.restored(), manuallyEditedTypes = it.manuallyEditedTypes + TokenType.DATE)
            }
            scheduleAutosave()
            return
        }
        viewModelScope.launch {
            val stored = taskRepository.getById(before.id).first() ?: return@launch
            appMessages.postIfRefused(taskRepository.update(stored.restored()), "undo the move")
        }
    }

    fun clearDueDate() {
        _uiState.update {
            it.copy(
                task = it.task?.copy(dueDate = Constants.NULL_DATE_STRING),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.DATE,
            )
        }
        scheduleAutosave()
    }

    fun setPriority(value: Int) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(priority = value.coerceIn(0, 4)),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.PRIORITY,
            )
        }
        scheduleAutosave()
    }

    /**
     * Pro's start date (contract 21.3): that day's local midnight, or none. Only offered while the
     * task has no time block, which holds the start itself.
     */
    fun setStartDate(day: kotlinx.datetime.LocalDate?) {
        val zone = dayClock.day.value.zone
        _uiState.update { state ->
            state.copy(
                task = state.task?.copy(
                    startDate = day?.let { com.rendyhd.vicu.util.pro.StartDates.dateOnlyStart(it, zone).toString() }
                        ?: Constants.NULL_DATE_STRING,
                ),
            )
        }
        scheduleAutosave()
    }

    /** Pro's flag (contract 21.4): `is_favorite`, written as its own change of the task. */
    fun setFlag(flagged: Boolean) {
        _uiState.update { it.copy(task = it.task?.copy(isFavorite = flagged)) }
        scheduleAutosave()
    }

    /** Persists a picker choice on dismiss and prevents title NLP from overriding it. */
    fun setRecurrence(recurrence: RecurrenceValue) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(
                    repeatAfter = recurrence.repeatAfter,
                    repeatMode = recurrence.repeatMode,
                ),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.RECURRENCE,
            )
        }
        scheduleAutosave()
    }

    fun setProject(projectId: Long) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(projectId = projectId),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.PROJECT,
            )
        }
        scheduleAutosave()
    }

    fun addLabel(labelId: Long) {
        val current = _uiState.value.task ?: return
        if (current.labels.any { it.id == labelId }) return
        // Optimistically reflect the label so the picker checkbox + chips update immediately,
        // without waiting for the server round-trip and Room re-emission (issue #6).
        val label = _uiState.value.allLabels.find { it.id == labelId }
        if (label != null) {
            _uiState.update { st ->
                val t = st.task ?: return@update st
                st.copy(task = t.copy(labels = t.labels + label))
            }
        }
        viewModelScope.launch {
            when (val result = labelRepository.addToTask(current.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { st ->
                    // Roll back the optimistic add and surface the failure.
                    val t = st.task ?: return@update st.copy(error = result.message)
                    st.copy(
                        task = t.copy(labels = t.labels.filterNot { it.id == labelId }),
                        error = result.message,
                    )
                }
                else -> {}
            }
        }
    }

    fun removeLabel(labelId: Long) {
        val current = _uiState.value.task ?: return
        val removed = current.labels.find { it.id == labelId } ?: return
        // Optimistic remove.
        _uiState.update { st ->
            val t = st.task ?: return@update st
            st.copy(task = t.copy(labels = t.labels.filterNot { it.id == labelId }))
        }
        viewModelScope.launch {
            when (val result = labelRepository.removeFromTask(current.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { st ->
                    // Roll back the optimistic remove and surface the failure.
                    val t = st.task ?: return@update st.copy(error = result.message)
                    val restored = if (t.labels.none { it.id == labelId }) t.labels + removed else t.labels
                    st.copy(task = t.copy(labels = restored), error = result.message)
                }
                else -> {}
            }
        }
    }

    fun createAndAddLabel(name: String, hexColor: String) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            val label = Label(id = 0, title = name, hexColor = hexColor)
            when (val result = labelRepository.create(label)) {
                is NetworkResult.Success -> {
                    labelRepository.addToTask(task.id, result.data.id)
                }
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addReminder(reminder: TaskReminder) {
        _uiState.update {
            it.copy(task = it.task?.copy(reminders = it.task.reminders + reminder))
        }
        scheduleAutosave()
    }

    fun removeReminder(index: Int) {
        _uiState.update {
            val updated = it.task?.reminders?.toMutableList()?.apply { removeAt(index) } ?: emptyList()
            it.copy(task = it.task?.copy(reminders = updated))
        }
        scheduleAutosave()
    }

    fun editReminder(index: Int, reminder: TaskReminder) {
        _uiState.update {
            val updated = it.task?.reminders?.toMutableList()?.apply { set(index, reminder) } ?: emptyList()
            it.copy(task = it.task?.copy(reminders = updated))
        }
        scheduleAutosave()
    }

    fun createSubtask(title: String) {
        val task = _uiState.value.task ?: return
        if (title.isBlank()) return

        viewModelScope.launch {
            val subtask = Task(
                id = 0,
                title = title,
                projectId = task.projectId,
            )
            when (val result = taskRepository.createSubtask(task.id, subtask)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addRelation(otherTaskId: Long, relationKind: String) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = taskRepository.createRelation(task.id, otherTaskId, relationKind)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun removeRelation(relationKind: String, otherTaskId: Long) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = taskRepository.deleteRelation(task.id, relationKind, otherTaskId)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun setRelationSearchQuery(q: String) {
        _relationSearchQuery.value = q
    }

    /**
     * The checkbox of the open task. Completing a task that still has unfinished subtasks asks
     * first, because completing it completes them too (the same question the lists ask).
     */
    fun requestToggleDone() {
        val task = _uiState.value.task ?: return
        if (!task.done && task.unfinishedDescendants().isNotEmpty()) {
            _uiState.update { it.copy(showCompleteConfirmation = true) }
        } else {
            toggleDone()
        }
    }

    fun confirmCompletion() {
        _uiState.update { it.copy(showCompleteConfirmation = false) }
        toggleDone()
    }

    fun dismissCompletion() {
        _uiState.update { it.copy(showCompleteConfirmation = false) }
    }

    /**
     * Completes or reopens the open task through the repository, not through the editor's save:
     * completion has its own path (subtasks, repeating tasks, queueing). Edits typed so far are
     * saved first and waited for, so a save cannot answer after the completion with the old state.
     * The editor and its baseline both move to the new state at once, so the change is not an
     * unsaved edit that a later save would send a second time.
     */
    private fun toggleDone() {
        val task = _uiState.value.task ?: return
        val target = !task.done
        val generation = loadGeneration
        viewModelScope.launch {
            saveIfChanged(final = false)
            _uiState.first { !it.isSaving }
            if (generation != loadGeneration) return@launch
            setEditorDone(target)
            val result = taskRepository.setDone(task.id, target)
            if (result is NetworkResult.Error) {
                if (generation == loadGeneration) setEditorDone(!target)
                _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    private fun setEditorDone(done: Boolean) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(done = done),
                originalTask = it.originalTask?.copy(done = done),
            )
        }
    }

    fun requestToggleSubtaskDone(subtask: Task) {
        if (!subtask.done && subtask.unfinishedDescendants().isNotEmpty()) {
            _uiState.update { it.copy(pendingSubtaskCompletion = subtask) }
        } else {
            toggleSubtaskDone(subtask)
        }
    }

    fun confirmSubtaskCompletion() {
        val subtask = _uiState.value.pendingSubtaskCompletion ?: return
        _uiState.update { it.copy(pendingSubtaskCompletion = null) }
        toggleSubtaskDone(subtask)
    }

    fun dismissSubtaskCompletion() {
        _uiState.update { it.copy(pendingSubtaskCompletion = null) }
    }

    private fun toggleSubtaskDone(subtask: Task) {
        val parentId = _uiState.value.task?.id ?: return
        val target = !subtask.done
        // Optimistically flip the checkbox so it responds instantly; the repository also flips
        // the parent's cached relatedTasks, so the Room re-emission reconciles to the same state.
        _uiState.update { st ->
            st.copy(
                subtasks = st.subtasks.map { if (it.id == subtask.id) it.copy(done = target) else it },
                relations = st.relations.mapValues { (_, list) ->
                    list.map { if (it.id == subtask.id) it.copy(done = target) else it }
                },
            )
        }
        viewModelScope.launch {
            when (val result = taskRepository.toggleSubtaskDone(parentId, subtask)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun uploadAttachment(uriString: String) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAttachment = true) }
            val result = attachmentRepository.attachPicked(task.id, uriString)
            _uiState.update { it.copy(isUploadingAttachment = false) }
            // Posted to the app-wide snackbar: the editor may have been closed by now.
            when (result) {
                is AttachResult.Refused -> appMessages.post(result.message)
                is AttachResult.Queued -> appMessages.post(WaitingUploadText.queued(result.fileName))
                is AttachResult.Uploaded -> Unit
            }
        }
    }

    /** Removes a file that waits to upload (or was refused), with Undo. */
    fun removeWaitingUpload(id: Long) {
        viewModelScope.launch {
            val removed = attachmentRepository.removeWaiting(id) ?: return@launch
            appMessages.post("Removed ${removed.fileName}", "Undo") {
                viewModelScope.launch {
                    if (!attachmentRepository.restoreWaiting(removed)) {
                        appMessages.post("Could not bring back ${removed.fileName}: its copy is gone")
                    }
                }
            }
        }
    }

    /** Sends a refused upload again. */
    fun retryWaitingUpload(id: Long) {
        viewModelScope.launch { attachmentRepository.retryWaiting(id) }
    }

    fun addImageAttachment(uriString: String) {
        val taskIdSnapshot = _uiState.value.task?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingImage = true) }
            when (val result = attachmentRepository.attachPicked(taskIdSnapshot, uriString, intoNotes = true)) {
                is AttachResult.Uploaded -> {
                    // Read the LATEST description inside .update so keystrokes typed
                    // during the upload aren't dropped by a pre-launch snapshot.
                    _uiState.update { current ->
                        val latestDesc = current.task?.description ?: ""
                        val newDesc = ImageTokens.appendImageToken(latestDesc, result.attachment.id)
                        current.copy(
                            task = current.task?.copy(description = newDesc),
                            isUploadingImage = false,
                        )
                    }
                    // Persist immediately so a token added mid-session survives a
                    // process kill before the sheet's onDispose save fires.
                    saveIfChanged(final = false)
                }
                is AttachResult.Refused -> {
                    _uiState.update { it.copy(isUploadingImage = false) }
                    appMessages.post(result.message)
                }
                is AttachResult.Queued -> {
                    _uiState.update { it.copy(isUploadingImage = false) }
                    appMessages.post(WaitingUploadText.queued(result.fileName))
                }
            }
        }
    }

    /**
     * Deletes an attachment. The row is hidden while the server is asked and comes back if it
     * refuses (the cached row is only removed after the server agrees), so nothing is lost on
     * a failure. The screen asks the user to confirm before calling this.
     */
    fun deleteAttachment(attachmentId: Long) {
        val task = _uiState.value.task ?: return
        if (attachmentId in _uiState.value.deletingAttachmentIds) return
        _uiState.update { it.copy(deletingAttachmentIds = it.deletingAttachmentIds + attachmentId) }
        viewModelScope.launch {
            val result = attachmentRepository.delete(task.id, attachmentId)
            _uiState.update { it.copy(deletingAttachmentIds = it.deletingAttachmentIds - attachmentId) }
            if (result is NetworkResult.Error) {
                appMessages.post("Could not delete the attachment: ${result.message}")
            }
        }
    }

    /** Downloads the attachment to the app cache and opens it in another app. */
    fun openAttachment(attachment: Attachment) {
        withCachedAttachment(attachment) { path -> platformFiles.openFile(path, attachment.mimeType) }
    }

    /** Downloads the attachment to the app cache and offers it to other apps. */
    fun shareAttachment(attachment: Attachment, report: ((String) -> Unit)? = null) {
        withCachedAttachment(attachment, report) { path -> platformFiles.shareFile(path, attachment.mimeType) }
    }

    /** Downloads the attachment and saves a copy where the user keeps files (Downloads; Files on iOS). */
    fun saveAttachment(attachment: Attachment, report: ((String) -> Unit)? = null) {
        withCachedAttachment(attachment, report) { path -> platformFiles.saveFile(path, attachment.fileName, attachment.mimeType) }
    }

    /** What there is to say goes to [report] when given (the image viewer covers the snackbar). */
    private fun withCachedAttachment(
        attachment: Attachment,
        report: ((String) -> Unit)? = null,
        present: suspend (String) -> String?,
    ) {
        if (attachment.id in _uiState.value.downloadingAttachmentIds) return
        _uiState.update { it.copy(downloadingAttachmentIds = it.downloadingAttachmentIds + attachment.id) }
        viewModelScope.launch {
            val result = attachmentRepository.downloadToCache(attachment)
            _uiState.update { it.copy(downloadingAttachmentIds = it.downloadingAttachmentIds - attachment.id) }
            val message = when (result) {
                is NetworkResult.Success -> present(result.data)
                is NetworkResult.Error -> "Could not download \"${attachment.fileName}\": ${result.message}"
                else -> null
            } ?: return@launch
            if (report != null) report(message) else appMessages.post(message)
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    /**
     * Entry point for the trash button. A task with descendants always gets the
     * structural choice dialog, even when ordinary delete confirmations are disabled.
     */
    fun requestDeleteTask() {
        viewModelScope.launch {
            val prefs = behaviorPrefsStore.getPrefs().first()
            if (_uiState.value.subtasks.isNotEmpty() || prefs.confirmBeforeDelete) {
                _uiState.update { it.copy(showDeleteConfirmation = true) }
            } else {
                deleteTask()
            }
        }
    }

    /**
     * Duplicate in the editor's menu (semantics 15): the edits are saved first, then the copy is
     * made from the saved task and opened here, since a copy is usually made to be changed.
     */
    fun duplicateTask() {
        val duplicator = taskDuplicator ?: return
        val id = _uiState.value.task?.id ?: return
        saveIfChanged()
        viewModelScope.launch {
            // Saves run one at a time under this lock; taking it waits for the one just started.
            saveMutex.withLock { }
            val source = taskRepository.getByIds(setOf(id)).firstOrNull() ?: _uiState.value.task ?: return@launch
            when (val result = duplicator.duplicate(source)) {
                is NetworkResult.Success -> {
                    val copy = result.data
                    loadTask(copy.copy.id)
                    appMessages.post(
                        if (copy.complete) "Duplicated. This is the copy." else "Duplicated, but some labels, people or subtasks could not be copied",
                        "Undo",
                    ) { undoDuplicate(copy, source.id) }
                }
                is NetworkResult.Error -> appMessages.post("Could not duplicate the task: ${result.message}")
                is NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Undo of [duplicateTask] (issue 97): the copy and its copied subtasks are deleted, and when
     * the editor still shows the copy it goes back to the original ([originalId]).
     */
    private fun undoDuplicate(copy: Duplicated, originalId: Long) {
        val duplicator = taskDuplicator ?: return
        if (_uiState.value.task?.id == copy.copy.id) loadTask(originalId)
        viewModelScope.launch { appMessages.postIfRefused(duplicator.remove(listOf(copy)), "undo the duplicate") }
    }

    /**
     * Save as template (section 18.1): the edits are saved first, then the saved task is taken as
     * it is, at once. [onEdit] is the Edit action of the answer.
     */
    fun saveAsTemplate(onEdit: (String) -> Unit) {
        val store = templateStore ?: return
        val id = _uiState.value.task?.id ?: return
        saveIfChanged()
        viewModelScope.launch {
            saveMutex.withLock { }
            val source = taskRepository.getByIds(setOf(id)).firstOrNull() ?: _uiState.value.task ?: return@launch
            val template = store.saveFrom(source)
            if (template == null) {
                appMessages.post("This task is too long to save as a template")
            } else {
                appMessages.post("Saved as a template", actionLabel = "Edit") { onEdit(template.id) }
            }
        }
    }

    private var subtaskDragging = false

    /** Section 19: only ids the server has can be written, so a task made offline waits. */
    fun canMoveSubtasks(): Boolean {
        val state = _uiState.value
        return subtaskOrders != null && (state.task?.id ?: 0L) > 0L && state.subtasks.size > 1 && state.subtasks.all { it.id > 0L }
    }

    fun onSubtaskDragStarted() {
        subtaskDragging = true
    }

    /** A dragged row passed [toId]; nothing is written before the finger comes up. */
    fun moveSubtask(fromId: Long, toId: Long) {
        _uiState.update { state ->
            val from = state.subtasks.indexOfFirst { it.id == fromId }
            val to = state.subtasks.indexOfFirst { it.id == toId }
            if (from < 0 || to < 0) return@update state
            state.copy(subtasks = state.subtasks.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    fun onSubtaskDragStopped() {
        subtaskDragging = false
        writeSubtaskOrder()
    }

    /** Move up or Move down, for those who cannot drag. */
    fun moveSubtaskBy(id: Long, delta: Int) {
        val subtasks = _uiState.value.subtasks
        val target = subtasks.getOrNull(subtasks.indexOfFirst { it.id == id } + delta) ?: return
        moveSubtask(id, target.id)
        writeSubtaskOrder()
    }

    private fun writeSubtaskOrder() {
        val store = subtaskOrders ?: return
        val state = _uiState.value
        val taskId = state.task?.id ?: return
        val ids = state.subtasks.map { it.id }
        viewModelScope.launch { store.set(taskId, ids) }
    }

    /** The task a template just made: its title is selected when it opens, once. */
    private var selectTitleOf: Long? = null

    fun selectTitleOnOpen(taskId: Long) {
        selectTitleOf = taskId
    }

    fun consumeSelectTitle(taskId: Long): Boolean {
        if (selectTitleOf != taskId) return false
        selectTitleOf = null
        return true
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteTask(deleteSubtasks: Boolean = true) {
        val task = _uiState.value.task ?: return
        // Edits of a task that is being deleted must not be saved back (that would resurrect it
        // or fail with "not found" once the screen closes).
        discardEdits = true
        autosaveJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
            when (val result = taskRepository.delete(task.id, deleteSubtasks)) {
                is NetworkResult.Success -> _uiState.update { it.copy(isDeleted = true) }
                is NetworkResult.Error -> {
                    discardEdits = false
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /**
     * Saves the editor's changes now. This is the final save of a session (the screen is
     * stopping or closing, or another task is being opened), so it also applies the title's
     * shortcuts; [final] = false is the quieter variant used while the user may still be typing.
     */
    fun saveIfChanged(final: Boolean = true) {
        autosaveJob?.cancel()
        captureSaveRequest(final)?.let(::launchSave)
    }

    /** Saves once the editor has been idle for [AUTOSAVE_DELAY_MS]; every new edit restarts the wait. */
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            captureSaveRequest(final = false)?.let(::launchSave)
        }
    }

    /** Everything a save needs, captured when it is requested so later edits cannot change it. */
    private class SaveRequest(
        val generation: Int,
        /** The editor's task when the save was requested (title as typed). */
        val snapshot: Task,
        /** The task the editor started from. */
        val base: Task,
        /** What is sent to the repository. */
        val toSave: Task,
        val parsedLabelNames: List<String>,
        val allLabels: List<Label>,
        val movedProject: Boolean,
        /** The notes the draft started from, as stored; see [notesBase]. */
        val notesBase: String?,
    )

    private class ShortcutOutcome(val result: TaskEditShortcutResult, val hasTokens: Boolean)

    private fun computeShortcuts(
        state: TaskDetailUiState,
        edited: Task,
        original: Task,
    ): ShortcutOutcome = when {
        state.parserConfig.enabled && state.parseResult != null -> ShortcutOutcome(
            result = applyTaskEditShortcuts(
                task = edited,
                parseResult = state.parseResult,
                projects = state.allProjects,
                zone = dayClock.day.value.zone,
                manuallyEditedTypes = state.manuallyEditedTypes,
                pro = state.proCapture,
            ),
            hasTokens = state.parseResult.tokens.isNotEmpty(),
        )
        !state.parserConfig.enabled &&
            state.parserConfig.bangToday &&
            edited.title != original.title &&
            TokenType.DATE !in state.manuallyEditedTypes -> {
            val bang = extractBangToday(edited.title)
            if (bang.dueDate != null) {
                val day = dayClock.day.value
                ShortcutOutcome(
                    result = TaskEditShortcutResult(
                        task = edited.copy(
                            title = bang.title,
                            dueDate = DueDates.bang(day.date, day.zone).toString(),
                        ),
                        labelNames = emptyList(),
                    ),
                    hasTokens = true,
                )
            } else {
                ShortcutOutcome(TaskEditShortcutResult(edited, emptyList()), hasTokens = false)
            }
        }
        else -> ShortcutOutcome(TaskEditShortcutResult(edited, emptyList()), hasTokens = false)
    }

    private fun captureSaveRequest(final: Boolean): SaveRequest? {
        if (discardEdits) return null
        val state = _uiState.value
        if (state.isDeleted) return null
        val edited = state.task ?: return null
        val original = state.originalTask ?: return null

        val shortcuts = computeShortcuts(state, edited, original)

        var task = shortcuts.result.task
        var labelNames = shortcuts.result.labelNames
        var keptTitleMessage: String? = null
        if (shortcuts.hasTokens && !final) {
            // The title is probably still being typed: a half-typed "#proj" or "@lab" must not
            // create anything. Everything else is saved now, the title and its shortcuts wait
            // for the final save.
            task = edited.copy(title = original.title)
            labelNames = emptyList()
        } else if (task.title.isBlank()) {
            // Empty, or nothing but shortcuts: the server rejects an empty title. Keep the
            // previous one and drop whatever the shortcut words would have set.
            task = edited.copy(title = original.title)
            labelNames = emptyList()
            if (final) {
                keptTitleMessage = if (edited.title.isBlank()) {
                    "A task needs a title, so the previous title was kept."
                } else {
                    "A title cannot be only a date, priority, project or label, so the previous title was kept."
                }
            }
        }

        if (keptTitleMessage != null) {
            appMessages.post(keptTitleMessage)
            _uiState.update { it.copy(task = it.task?.copy(title = original.title), parseResult = null) }
        }

        val parsedLabelNames = labelNames.filterNot { labelName ->
            task.labels.any { it.title.equals(labelName, ignoreCase = true) }
        }
        if (task == original && parsedLabelNames.isEmpty()) return null

        // Re-append preserved link HTML before saving. task.description already
        // contains the rich-text HTML body + [[image:N]] tokens; link metadata
        // is stored separately and stitched back here.
        val toSave = task.copy(description = storedDescription(task.description))
        // The same content is already on its way (a stop save right after an autosave, or
        // leaving the screen while one runs): do not send it twice.
        if ((savesInFlight[toSave.id] ?: 0) > 0 && inFlightPayload[toSave.id] == toSave && parsedLabelNames.isEmpty()) {
            return null
        }
        Logger.d(TAG, "saveIfChanged(final=$final): task has changes, saving...")
        return SaveRequest(
            generation = loadGeneration,
            snapshot = edited,
            base = original,
            toSave = toSave,
            parsedLabelNames = parsedLabelNames,
            allLabels = state.allLabels,
            movedProject = task.projectId != original.projectId,
            notesBase = notesBase,
        )
    }

    /** The editor's notes ([display]: the body and its image tokens) as stored, with the link and routine HTML put back. */
    private fun storedDescription(display: String): String {
        val (bodyHtml, imageRefs) = ImageTokens.parseValue(display)
        return DescriptionHtml.merge(bodyHtml, imageRefs, preservedLinkHtml, preservedRoutineHtml)
    }

    private fun launchSave(request: SaveRequest) {
        val taskId = request.toSave.id
        savesInFlight[taskId] = (savesInFlight[taskId] ?: 0) + 1
        inFlightPayload[taskId] = request.toSave
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                // One save at a time: two overlapping updates of the same task could otherwise
                // answer out of order and leave the older values in the cache.
                saveMutex.withLock { performSave(request) }
            } finally {
                val left = (savesInFlight[taskId] ?: 1) - 1
                if (left <= 0) {
                    savesInFlight.remove(taskId)
                    inFlightPayload.remove(taskId)
                } else {
                    savesInFlight[taskId] = left
                }
                _uiState.update { it.copy(isSaving = savesInFlight.isNotEmpty()) }
            }
        }
    }

    private suspend fun performSave(request: SaveRequest) {
        val taskToSave = request.toSave
        // Send the COMPLETE edited task: the repository diffs it against the cached row and sends
        // only what changed.
        when (val result = taskRepository.updateWithNotesBase(taskToSave, request.notesBase)) {
            is NetworkResult.Success -> {
                if (request.movedProject) {
                    // Vikunja does not move subtasks with their parent: move the whole subtree.
                    when (val moved = taskRepository.moveDescendantsToProject(taskToSave.id, taskToSave.projectId)) {
                        is NetworkResult.Error -> appMessages.post(moved.message)
                        else -> {}
                    }
                }
                val split = DescriptionHtml.splitForEditor(result.data.description)
                val displayDesc = ImageTokens.buildValue(split.htmlBody, split.imageRefs)
                val displayed = result.data.copy(description = displayDesc)
                // Only touch the editor if it still shows the task this save was for.
                if (request.generation == loadGeneration) {
                    // After a send the base is what the server answered with.
                    notesBase = result.data.description
                    preservedLinkHtml = split.linkHtml
                    preservedRoutineHtml = split.routineHtml
                    val editedSince = _uiState.value.task?.let {
                        it.draftFields() != request.snapshot.draftFields()
                    } ?: false
                    if (!editedSince) suppressedRawTexts = emptyMap()
                    _uiState.update { adoptSavedTask(it, request, displayed, editedSince) }
                }

                for (labelName in request.parsedLabelNames) {
                    val labelId = request.allLabels
                        .firstOrNull { it.title.equals(labelName, ignoreCase = true) }
                        ?.id
                        ?: when (
                            val createResult = labelRepository.create(
                                Label(id = 0L, title = labelName, hexColor = ""),
                            )
                        ) {
                            is NetworkResult.Success -> createResult.data.id
                            is NetworkResult.Error -> {
                                Logger.w(TAG, "Auto-create label '$labelName' failed: ${createResult.message}")
                                appMessages.post("Could not create label \"$labelName\": ${createResult.message}")
                                null
                            }
                            is NetworkResult.Loading -> null
                        }

                    if (labelId != null) {
                        when (val addResult = labelRepository.addToTask(displayed.id, labelId)) {
                            is NetworkResult.Error ->
                                appMessages.post("Could not add label \"$labelName\": ${addResult.message}")
                            else -> {}
                        }
                    }
                }
            }
            is NetworkResult.Error -> {
                // The editor may be closed by now, so this goes to the app-level message channel.
                // The editor still holds the edits (the baseline did not move while the save ran), so
                // the next edit, the stop save or the closing save tries again.
                appMessages.post("Could not save \"${request.base.title}\": ${result.message}")
            }
            else -> {}
        }
    }

    /**
     * The editor state after a save succeeded. Fields the user changed while the save was in
     * flight keep their edited value; the rest take what the server returned.
     */
    private fun adoptSavedTask(
        state: TaskDetailUiState,
        request: SaveRequest,
        displayed: Task,
        editedSince: Boolean,
    ): TaskDetailUiState {
        val current = state.task
        val merged = if (current == null || !editedSince) {
            displayed
        } else {
            reconcileTaskEditor(current, request.snapshot, displayed)
        }
        return if (editedSince) {
            state.copy(task = merged, originalTask = displayed)
        } else {
            state.copy(
                task = merged,
                originalTask = displayed,
                parseResult = null,
                suppressedTypes = emptySet(),
                manuallyEditedTypes = emptySet(),
                parserConfig = state.parserConfig.copy(suppressTypes = emptySet()),
            )
        }
    }

    /**
     * The unsaved edits of the open task as an opaque string for the saved instance state, or
     * null when there are none. Evaluated when the activity saves its state, so it is always
     * current without any work per keystroke.
     */
    fun currentDraftJson(): String? {
        val state = _uiState.value
        val task = state.task ?: return null
        val original = state.originalTask ?: return null
        if (state.isDeleted || discardEdits) return null
        if (task.draftFields() == original.draftFields()) return null
        return TaskDetailDraftCodec.encode(
            TaskDetailDraft(
                taskId = task.id,
                edited = task.draftFields(),
                base = original.draftFields(),
                manuallyEditedTypes = state.manuallyEditedTypes.map { it.name },
            ),
        )
    }

    /**
     * Hands back a draft from [currentDraftJson] after the process was recreated. Applied to the
     * next [loadTask] of the same task; ignored once this ViewModel has already loaded something.
     */
    fun restoreDraft(encoded: String?) {
        if (_uiState.value.requestedTaskId != 0L) return
        restoredDraft = TaskDetailDraftCodec.decode(encoded)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
