package com.rendyhd.vicu.ui.screens.taskentry

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.NlpPrefsStore
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
import com.rendyhd.vicu.util.FileUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.SyntaxPrefixes
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.extractBangToday
import com.rendyhd.vicu.util.parser.getPrefixes
import com.rendyhd.vicu.util.parser.recurrenceToVikunja
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class TaskEntryUiState(
    val title: String = "",
    val description: String = "",
    val dueDate: String = "",
    val priority: Int = 0,
    val projectId: Long = 0,
    val selectedLabelIds: Set<Long> = emptySet(),
    val reminders: List<TaskReminder> = emptyList(),
    val isSaving: Boolean = false,
    val savedTaskId: Long? = null,
    val error: String? = null,
    val allProjects: List<Project> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    // NLP parser state
    val parseResult: ParseResult? = null,
    val parserConfig: ParserConfig = ParserConfig(),
    val suppressedTypes: Set<TokenType> = emptySet(),
    val pendingAttachmentUris: List<Uri> = emptyList(),
    val pendingAttachmentMimeType: String? = null,
)

@HiltViewModel
class TaskEntryViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val authManager: AuthManager,
    private val nlpPrefsStore: NlpPrefsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEntryUiState())
    val uiState: StateFlow<TaskEntryUiState> = _uiState.asStateFlow()

    // Track raw texts for stale suppression detection
    private var suppressedRawTexts: Map<TokenType, List<String>> = emptyMap()

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    init {
        viewModelScope.launch {
            projectRepository.getAll().collect { projects ->
                _uiState.update { it.copy(allProjects = projects) }
            }
        }
        viewModelScope.launch {
            labelRepository.getAll().collect { labels ->
                _uiState.update { it.copy(allLabels = labels) }
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

    fun initWithDefaults(defaultProjectId: Long?) {
        viewModelScope.launch {
            val projectId = defaultProjectId ?: authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(projectId = projectId) }
        }
    }

    fun initWithSharedContent(defaultProjectId: Long?, sharedContent: SharedContent) {
        viewModelScope.launch {
            val projectId = defaultProjectId ?: authManager.getInboxProjectId() ?: 0L
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

    fun removePendingAttachment(index: Int) {
        _uiState.update {
            it.copy(
                pendingAttachmentUris = it.pendingAttachmentUris.toMutableList().apply {
                    removeAt(index)
                },
            )
        }
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
            dueDate = isoFormatter.format(
                parseResult.dueDate.atZone(ZoneId.systemDefault()).toInstant(),
            )
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

        // Determine labels: manual + parsed (merged)
        val labelIds = state.selectedLabelIds.toMutableSet()
        if (config.enabled && parseResult != null) {
            for (labelName in parseResult.labels) {
                val matchedLabel = state.allLabels.find {
                    it.title.equals(labelName, ignoreCase = true)
                }
                if (matchedLabel != null) {
                    labelIds.add(matchedLabel.id)
                }
            }
        }

        // Determine recurrence: parsed
        var repeatAfter = 0L
        var repeatMode = 0
        if (config.enabled && parseResult?.recurrence != null) {
            val vik = recurrenceToVikunja(parseResult.recurrence)
            repeatAfter = vik.repeatAfter
            repeatMode = vik.repeatMode
        }

        // Bang-today fallback (works even when parser disabled)
        if (config.bangToday && (dueDate.isBlank() || DateUtils.isNullDate(dueDate))) {
            val bang = extractBangToday(title)
            if (bang.dueDate != null) {
                title = bang.title
                dueDate = DateUtils.todayEndIso()
            }
        }

        if (title.isBlank()) return

        _uiState.update { it.copy(isSaving = true, error = null) }

        val pendingUris = state.pendingAttachmentUris

        viewModelScope.launch {
            try {
                val task = Task(
                    id = 0,
                    title = title,
                    description = state.description,
                    dueDate = dueDate,
                    priority = priority,
                    projectId = projectId,
                    reminders = state.reminders,
                    repeatAfter = repeatAfter,
                    repeatMode = repeatMode,
                )

                when (val result = taskRepository.create(task)) {
                    is NetworkResult.Success -> {
                        val createdTask = result.data
                        for (labelId in labelIds) {
                            labelRepository.addToTask(createdTask.id, labelId)
                        }
                        // Upload pending attachments in background (fire-and-forget)
                        if (pendingUris.isNotEmpty()) {
                            uploadPendingAttachments(createdTask.id, pendingUris)
                        }
                        taskRepository.refreshAll()
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

    private fun uploadPendingAttachments(taskId: Long, uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                val filePart = FileUtils.uriToMultipartPart(context, uri) ?: continue
                attachmentRepository.upload(taskId, filePart)
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
                isSaving = false,
                savedTaskId = null,
                error = null,
                parseResult = null,
                suppressedTypes = emptySet(),
                pendingAttachmentUris = emptyList(),
                pendingAttachmentMimeType = null,
            )
        }
    }
}
