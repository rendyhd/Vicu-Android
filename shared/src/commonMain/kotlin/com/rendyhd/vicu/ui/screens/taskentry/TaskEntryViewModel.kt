package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.randomUuid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DefaultReminder
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.SyntaxPrefixes
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.extractBangToday
import com.rendyhd.vicu.util.parser.getPrefixes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone


data class TaskEntryUiState(
    val title: String = "",
    val description: String = "",
    val dueDate: String = "",
    /**
     * True once the user picked or cleared the date. A date that came from the screen that opened
     * the entry (Today's FAB) is a seed, not manual, and a date typed in the title beats it.
     */
    val dueDateIsManual: Boolean = false,
    val priority: Int = 0,
    /** True once the user picked a priority, None included: the pick wins over "!3" in the title. */
    val priorityIsManual: Boolean = false,
    val projectId: Long = 0,
    /** True once the user picked a project: the pick wins over "#Work" in the title. */
    val projectIsManual: Boolean = false,
    val selectedLabelIds: Set<Long> = emptySet(),
    val reminders: List<TaskReminder> = emptyList(),
    /** null lets NLP decide; a non-null value, including None, is an explicit picker choice. */
    val manualRecurrence: RecurrenceValue? = null,
    val isSaving: Boolean = false,
    val savedTaskId: Long? = null,
    val error: String? = null,
    val allProjects: List<Project> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    // NLP parser state
    val parseResult: ParseResult? = null,
    val parserConfig: ParserConfig = ParserConfig(),
    val suppressedTypes: Set<TokenType> = emptySet(),
    val pendingAttachmentUris: List<String> = emptyList(),
    val pendingAttachmentMimeType: String? = null,
    val inboxProjectId: Long = 0L,
    /** uuid → local URI for images pasted/staged before the task is saved. */
    val pendingImages: Map<String, String> = emptyMap(),
    /** When true, keep the entry sheet open after saving (mass-add). */
    val keepEntryOpen: Boolean = false,
)

class TaskEntryViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val authManager: AuthManager,
    private val nlpPrefsStore: NlpPrefsStore,
    private val notificationPrefsStore: NotificationPrefsStore,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val appMessages: AppMessages,
    private val dayClock: DayClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEntryUiState())
    val uiState: StateFlow<TaskEntryUiState> = _uiState.asStateFlow()

    // Track raw texts for stale suppression detection
    private var suppressedRawTexts: Map<TokenType, List<String>> = emptyMap()

    /**
     * Types the user set with a chip's own control. The parser stops reading them, so their tokens
     * lose the highlight and stay plain text in the title; unlike a dismissed chip this never lifts
     * when the title changes (the desktop `pinType`).
     */
    private var pinnedTypes: Set<TokenType> = emptySet()

    /** The due date the opening screen asked for; a fresh draft (mass-add) starts from it again. */
    private var seedDueDate: String? = null

    /**
     * True once a screen has initialised the draft ([initWithDefaults] or [initWithSharedContent]).
     * A rotation recreates the sheet around this same view model; finding it set, the sheet keeps
     * the draft instead of resetting it to the defaults or to the shared text again.
     */
    var isInitialized: Boolean = false
        private set



    init {
        viewModelScope.launch {
            projectRepository.getAll().collect { projects ->
                _uiState.update { state ->
                    val selectedProjectIsActive = projects.any { it.id == state.projectId }
                    val fallbackProjectId = state.inboxProjectId
                        .takeIf { inboxId -> projects.any { it.id == inboxId } }
                        ?: projects.firstOrNull()?.id
                        ?: 0L
                    state.copy(
                        allProjects = projects,
                        projectId = if (state.projectId != 0L && !selectedProjectIsActive) {
                            fallbackProjectId
                        } else {
                            state.projectId
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            labelRepository.getAll().collect { labels ->
                _uiState.update { it.copy(allLabels = labels) }
            }
        }
        viewModelScope.launch {
            authManager.inboxProjectId.collect { inboxId ->
                _uiState.update { it.copy(inboxProjectId = inboxId ?: 0L) }
            }
        }
        viewModelScope.launch {
            behaviorPrefsStore.getPrefs().collect { prefs ->
                _uiState.update { it.copy(keepEntryOpen = prefs.keepEntryOpen) }
            }
        }
        viewModelScope.launch {
            nlpPrefsStore.config.collect { config ->
                _uiState.update { state ->
                    val newConfig = config.copy(suppressTypes = state.suppressedTypes)
                    state.copy(
                        parserConfig = newConfig,
                        parseResult = if (state.title.isNotBlank()) {
                            TaskParser.parse(state.title, newConfig)
                        } else {
                            state.parseResult
                        },
                    )
                }
            }
        }
    }

    fun initWithDefaults(defaultProjectId: Long?, defaultDueDate: String? = null) {
        isInitialized = true
        viewModelScope.launch {
            val projectId = resolveActiveProjectId(defaultProjectId)
            seedDueDate = defaultDueDate?.takeIf { it.isNotBlank() }
            _uiState.update {
                it.copy(
                    projectId = projectId,
                    dueDate = seedDueDate ?: "",
                    dueDateIsManual = false,
                )
            }
        }
    }

    fun initWithSharedContent(defaultProjectId: Long?, sharedContent: SharedContent) {
        isInitialized = true
        viewModelScope.launch {
            val projectId = resolveActiveProjectId(defaultProjectId)
            _uiState.update {
                it.copy(
                    projectId = projectId,
                    title = sharedContent.suggestedTitle ?: "",
                    description = sharedContent.suggestedDescription ?: "",
                    pendingAttachmentUris = sharedContent.fileUris,
                    pendingAttachmentMimeType = sharedContent.mimeType,
                )
            }
        }
    }

    private suspend fun resolveActiveProjectId(requestedProjectId: Long?): Long {
        val activeProjects = projectRepository.getAll().first()
        val activeProjectIds = activeProjects.mapTo(mutableSetOf()) { it.id }
        val inboxProjectId = authManager.getInboxProjectId()
        return requestedProjectId?.takeIf { it in activeProjectIds }
            ?: inboxProjectId?.takeIf { it in activeProjectIds }
            ?: activeProjects.firstOrNull()?.id
            ?: 0L
    }

    fun removePendingAttachment(index: Int) {
        _uiState.update {
            it.copy(
                pendingAttachmentUris = it.pendingAttachmentUris.toMutableList().apply {
                    removeAt(index)
                },
            )
        }
    }

    /** Stage a pasted image: append `[[image-pending:uuid]]` to description and remember the URI. */
    fun stagePendingImage(uri: String) {
        val uuid = randomUuid().take(8)
        _uiState.update { state ->
            val (text, refs) = ImageTokens.parseValue(state.description)
            val newRefs = refs + ImageTokens.ImageRef.Pending(uuid)
            state.copy(
                description = ImageTokens.buildValue(text, newRefs),
                pendingImages = state.pendingImages + (uuid to uri),
            )
        }
    }

    fun removePendingImage(uuid: String) {
        _uiState.update { it.copy(pendingImages = it.pendingImages - uuid) }
    }

    fun setTitle(title: String) {
        _uiState.update { state ->
            // Auto-lift stale suppressions: if the raw text no longer appears in input
            val activeSuppressed = state.suppressedTypes.filter { type ->
                if (type in pinnedTypes) return@filter true
                val texts = suppressedRawTexts[type] ?: return@filter false
                texts.any { title.contains(it) }
            }.toSet()
            if (activeSuppressed != state.suppressedTypes) {
                suppressedRawTexts = suppressedRawTexts.filterKeys { it in activeSuppressed }
            }

            val config = state.parserConfig.copy(suppressTypes = activeSuppressed)
            val parseResult = if (title.isNotBlank()) {
                TaskParser.parse(title, config)
            } else {
                null
            }
            state.copy(
                title = title,
                parseResult = parseResult,
                suppressedTypes = activeSuppressed,
                parserConfig = config,
            )
        }
    }

    fun suppressType(type: TokenType) {
        _uiState.update { state ->
            val result = state.parseResult ?: return@update state
            // Store raw texts for the tokens being suppressed
            val rawTexts = result.tokens.filter { it.type == type }.map { it.raw }
            suppressedRawTexts = suppressedRawTexts + (type to rawTexts)
            val newSuppressed = state.suppressedTypes + type
            val config = state.parserConfig.copy(suppressTypes = newSuppressed)
            val newResult = if (state.title.isNotBlank()) {
                TaskParser.parse(state.title, config)
            } else {
                null
            }
            state.copy(
                parseResult = newResult,
                suppressedTypes = newSuppressed,
                parserConfig = config,
            )
        }
    }

    fun setDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    /** Stops the parser reading [type]: the user set it with its own control (see [pinnedTypes]). */
    private fun pin(type: TokenType) {
        pinnedTypes = pinnedTypes + type
        _uiState.update { state ->
            val suppressed = state.suppressedTypes + type
            val config = state.parserConfig.copy(suppressTypes = suppressed)
            state.copy(
                suppressedTypes = suppressed,
                parserConfig = config,
                parseResult = if (state.title.isNotBlank()) TaskParser.parse(state.title, config) else null,
            )
        }
    }

    fun setDueDate(dueDate: String) {
        _uiState.update { it.copy(dueDate = dueDate, dueDateIsManual = true) }
        pin(TokenType.DATE)
    }

    fun clearDueDate() {
        _uiState.update { it.copy(dueDate = Constants.NULL_DATE_STRING, dueDateIsManual = true) }
        pin(TokenType.DATE)
    }

    fun setPriority(priority: Int) {
        _uiState.update { it.copy(priority = priority, priorityIsManual = true) }
        pin(TokenType.PRIORITY)
    }

    fun setRecurrence(recurrence: RecurrenceValue) {
        _uiState.update { it.copy(manualRecurrence = recurrence) }
        pin(TokenType.RECURRENCE)
    }

    fun setProjectId(projectId: Long) {
        _uiState.update { it.copy(projectId = projectId, projectIsManual = true) }
        pin(TokenType.PROJECT)
    }

    fun toggleLabel(labelId: Long) {
        _uiState.update {
            val newSet = if (labelId in it.selectedLabelIds) {
                it.selectedLabelIds - labelId
            } else {
                it.selectedLabelIds + labelId
            }
            it.copy(selectedLabelIds = newSet)
        }
    }

    /** The Tags chip's clear button: no labels picked and none read from the text. */
    fun clearLabels() {
        _uiState.update { it.copy(selectedLabelIds = emptySet()) }
        pin(TokenType.LABEL)
    }

    /** Create a brand-new label inline and select it (mirrors the detail sheet). */
    fun createAndAddLabel(name: String, hexColor: String) {
        viewModelScope.launch {
            when (val res = labelRepository.create(Label(id = 0L, title = name, hexColor = hexColor))) {
                is NetworkResult.Success ->
                    _uiState.update { it.copy(selectedLabelIds = it.selectedLabelIds + res.data.id) }
                is NetworkResult.Error ->
                    _uiState.update { it.copy(error = res.message) }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /** The title that will actually be saved (NLP tokens stripped). Used to gate the Save button. */
    fun effectiveTitle(): String {
        val state = _uiState.value
        return if (state.parserConfig.enabled && state.parseResult != null) {
            state.parseResult.title
        } else {
            state.title.trim()
        }
    }

    fun addReminder(reminder: TaskReminder) {
        _uiState.update { it.copy(reminders = it.reminders + reminder) }
    }

    fun removeReminder(index: Int) {
        _uiState.update {
            it.copy(reminders = it.reminders.toMutableList().apply { removeAt(index) })
        }
    }

    fun editReminder(index: Int, reminder: TaskReminder) {
        _uiState.update {
            it.copy(reminders = it.reminders.toMutableList().apply { set(index, reminder) })
        }
    }

    fun save() {
        val state = _uiState.value
        val config = state.parserConfig
        val parseResult = state.parseResult

        // Determine title
        var title = if (config.enabled && parseResult != null) {
            parseResult.title
        } else {
            state.title.trim()
        }
        if (title.isBlank()) return

        // What the sheet shows is what is saved: a field the user set with its own control wins
        // over the text, otherwise the text, otherwise what the screen gave (TaskEntryFields.kt).
        val day = dayClock.day.value
        val fields = resolveEntryFields(state, day.zone)
        val manualDueDate = fields.dueSource == FieldSource.CHIP
        var dueDate = fields.dueDate
        val priority = fields.priority
        val projectId = fields.projectId

        // Manual label selections; parsed @label tokens are resolved inside the coroutine
        // below (so unknown labels can be auto-created via the repository).
        val manualLabelIds = state.selectedLabelIds.toMutableSet()

        val recurrence = fields.recurrence

        // Bang-today fallback (works even when parser disabled; skipped when the user
        // dismissed the Today chip — DATE is then in suppressTypes). A date picked by hand or typed
        // as a date phrase wins over it; a seeded date does not.
        val typedDate = fields.dueSource == FieldSource.TEXT
        if (config.bangToday && TokenType.DATE !in config.suppressTypes && !manualDueDate && !typedDate) {
            val bang = extractBangToday(title)
            if (bang.dueDate != null) {
                title = bang.title
                // The "!" shortcut is today, date-only, in every entry point.
                dueDate = DueDates.bang(day.date, day.zone).toString()
            }
        }

        if (title.isBlank()) return

        _uiState.update { it.copy(isSaving = true, error = null) }

        val pendingUris = state.pendingAttachmentUris
        val pendingImages = state.pendingImages
        val descriptionAtSave = state.description

        viewModelScope.launch {
            try {
                // Resolve parsed @label tokens: attach existing labels by case-insensitive
                // title match; auto-create any that don't exist. Best-effort — a failed
                // create is logged and dropped so the task itself still saves.
                val resolvedLabelIds = manualLabelIds
                if (config.enabled && parseResult != null) {
                    for (labelName in parseResult.labels) {
                        val matched = state.allLabels.find { it.title.equals(labelName, ignoreCase = true) }
                        if (matched != null) {
                            resolvedLabelIds.add(matched.id)
                        } else {
                            when (val res = labelRepository.create(Label(id = 0L, title = labelName, hexColor = ""))) {
                                is NetworkResult.Success -> resolvedLabelIds.add(res.data.id)
                                is NetworkResult.Error -> Logger.w("TaskEntryVM", "Auto-create label '$labelName' failed: ${res.message}")
                                is NetworkResult.Loading -> {}
                            }
                        }
                    }
                }

                val task = Task(
                    id = 0,
                    title = title,
                    description = descriptionAtSave,
                    dueDate = dueDate,
                    priority = priority,
                    projectId = projectId,
                    reminders = state.reminders,
                    repeatAfter = recurrence.repeatAfter,
                    repeatMode = recurrence.repeatMode,
                )

                // Synthesize a default reminder when the user set a due date but no
                // manual reminder (desktop parity).
                val prefs = notificationPrefsStore.getPrefs().first()
                val taskToCreate = if (task.reminders.isEmpty()) {
                    val synthesized = DefaultReminder.build(
                        dueDate = task.dueDate,
                        offsetSeconds = prefs.defaultReminderOffset,
                        relativeTo = prefs.defaultReminderRelativeTo,
                    )
                    if (synthesized != null) task.copy(reminders = listOf(synthesized)) else task
                } else {
                    task
                }

                when (val result = taskRepository.create(taskToCreate)) {
                    is NetworkResult.Success -> {
                        val createdTask = result.data
                        for (labelId in resolvedLabelIds) {
                            labelRepository.addToTask(createdTask.id, labelId)
                        }
                        if (pendingUris.isNotEmpty()) {
                            uploadPendingAttachments(createdTask.id, pendingUris)
                        }
                        // Upload pasted images, swap `[[image-pending:uuid]]` → `[[image:N]]`,
                        // then update the task with the new description.
                        if (pendingImages.isNotEmpty()) {
                            uploadPendingImagesAndUpdateDescription(createdTask, pendingImages)
                        }
                        // The repository stored the server's answer for the new task, and adding
                        // labels and uploading files refresh that one task, so nothing else
                        // needs to be downloaded here: the next screen or sync refresh is
                        // incremental anyway.
                        _uiState.update {
                            it.copy(isSaving = false, savedTaskId = createdTask.id)
                        }
                    }
                    is NetworkResult.Error -> {
                        _uiState.update {
                            it.copy(isSaving = false, error = result.message)
                        }
                    }
                    is NetworkResult.Loading -> {}
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Failed to save task")
                }
            }
        }
    }

    private suspend fun uploadPendingImagesAndUpdateDescription(
        task: Task,
        pendingImages: Map<String, String>,
    ) {
        val mapping = mutableMapOf<String, Long>()
        for ((uuid, uri) in pendingImages) {
            when (val uploadResult = attachmentRepository.uploadPicked(task.id, uri)) {
                is NetworkResult.Success -> mapping[uuid] = uploadResult.data.id
                else -> {} // skip failed uploads; pending token will remain and be ignored client-side
            }
        }
        if (mapping.isEmpty()) return
        val newDescription = ImageTokens.replacePendingTokens(task.description, mapping)
        if (newDescription != task.description) {
            taskRepository.update(task.copy(description = newDescription))
        }
    }

    private fun uploadPendingAttachments(taskId: Long, uris: List<String>) {
        viewModelScope.launch {
            for (uri in uris) {
                // Too large or unreadable files are reported, so one bad file does not hide the rest.
                val result = attachmentRepository.uploadPicked(taskId, uri)
                if (result is NetworkResult.Error) appMessages.post(result.message)
            }
            attachmentRepository.refreshForTask(taskId)
        }
    }

    fun reset() {
        suppressedRawTexts = emptyMap()
        pinnedTypes = emptySet()
        _uiState.update {
            it.copy(
                title = "",
                description = "",
                dueDate = seedDueDate ?: "",
                dueDateIsManual = false,
                priority = 0,
                priorityIsManual = false,
                projectIsManual = false,
                selectedLabelIds = emptySet(),
                reminders = emptyList(),
                manualRecurrence = null,
                isSaving = false,
                savedTaskId = null,
                error = null,
                parseResult = null,
                suppressedTypes = emptySet(),
                pendingAttachmentUris = emptyList(),
                pendingAttachmentMimeType = null,
                pendingImages = emptyMap(),
            )
        }
    }
}
