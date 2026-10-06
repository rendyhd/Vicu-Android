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
import com.rendyhd.vicu.util.DefaultReminder
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
import kotlinx.datetime.toInstant


data class TaskEntryUiState(
    val title: String = "",
    val description: String = "",
    val dueDate: String = "",
    val priority: Int = 0,
    val projectId: Long = 0,
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEntryUiState())
    val uiState: StateFlow<TaskEntryUiState> = _uiState.asStateFlow()

    // Track raw texts for stale suppression detection
    private var suppressedRawTexts: Map<TokenType, List<String>> = emptyMap()



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
            val inboxId = authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(inboxProjectId = inboxId) }
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
        viewModelScope.launch {
            val projectId = resolveActiveProjectId(defaultProjectId)
            _uiState.update {
                it.copy(
                    projectId = projectId,
                    dueDate = defaultDueDate ?: "",
                )
            }
        }
    }

    fun initWithSharedContent(defaultProjectId: Long?, sharedContent: SharedContent) {
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

    fun setDueDate(dueDate: String) {
        _uiState.update { it.copy(dueDate = dueDate) }
    }

    fun clearDueDate() {
        _uiState.update { it.copy(dueDate = Constants.NULL_DATE_STRING) }
    }

    fun setPriority(priority: Int) {
        _uiState.update { it.copy(priority = priority) }
    }

    fun setRecurrence(recurrence: RecurrenceValue) {
        _uiState.update { it.copy(manualRecurrence = recurrence) }
    }

    fun cyclePriority() {
        _uiState.update { it.copy(priority = (it.priority + 1) % 5) }
    }

    fun setProjectId(projectId: Long) {
        _uiState.update { it.copy(projectId = projectId) }
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

        // Determine due date: manual picker > parsed date > bang today
        var dueDate = state.dueDate
        if (config.enabled && parseResult?.dueDate != null &&
            (dueDate.isBlank() || DateUtils.isNullDate(dueDate))
        ) {
            dueDate = parseResult.dueDate.toInstant(TimeZone.currentSystemDefault()).toString()
        }

        // Determine priority: manual > parsed
        var priority = state.priority
        if (config.enabled && parseResult?.priority != null && priority == 0) {
            priority = parseResult.priority
        }

        // Determine project: parsed overrides if a match is found
        var projectId = state.projectId
        if (config.enabled && parseResult?.project != null) {
            val matchedProject = state.allProjects.find {
                it.title.equals(parseResult.project, ignoreCase = true)
            }
            if (matchedProject != null) {
                projectId = matchedProject.id
            }
        }

        // Manual label selections; parsed @label tokens are resolved inside the coroutine
        // below (so unknown labels can be auto-created via the repository).
        val manualLabelIds = state.selectedLabelIds.toMutableSet()

        // Determine recurrence: an explicit picker choice (including None) wins over NLP.
        val recurrence = resolveTaskEntryRecurrence(
            manualRecurrence = state.manualRecurrence,
            parserEnabled = config.enabled,
            parsedRecurrence = parseResult?.recurrence,
        )

        // Bang-today fallback (works even when parser disabled; skipped when the user
        // dismissed the Today chip — DATE is then in suppressTypes)
        if (config.bangToday && TokenType.DATE !in config.suppressTypes &&
            (dueDate.isBlank() || DateUtils.isNullDate(dueDate))
        ) {
            val bang = extractBangToday(title)
            if (bang.dueDate != null) {
                title = bang.title
                // Match the NLP parser and desktop: bang-today means start of today.
                dueDate = DateUtils.todayStartIso()
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
                        // Dismiss immediately; run the full refresh in the background so the
                        // sheet doesn't hang on the network round-trip (save-task stutter).
                        _uiState.update {
                            it.copy(isSaving = false, savedTaskId = createdTask.id)
                        }
                        viewModelScope.launch { taskRepository.refreshAll() }
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
        _uiState.update {
            it.copy(
                title = "",
                description = "",
                dueDate = "",
                priority = 0,
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
