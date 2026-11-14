package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.ask.AskSnapshot
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.location.LocationRepository
import com.rendyhd.vicu.speech.HintProject
import com.rendyhd.vicu.speech.VocabularyHint
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime

/**
 * Smart dictation (`docs/ai-assistant-v1.md`, section 4): the context of the list the user is in,
 * one request with the transcript, the checks, and editing the drafts by voice.
 */
class DictationService(
    private val providers: AiProviders,
    private val settings: AiSettingsStore,
    private val projects: ProjectRepository,
    private val labels: LabelRepository,
    private val collaboration: CollaborationRepository,
    private val auth: AuthManager,
    private val dayClock: DayClock,
    private val locations: LocationRepository?,
    private val time: TimeSource = SystemTimeSource,
) {
    /** Whether dictation can send to a model now (set up, switched on, tested, agreed). */
    suspend fun isReady(): Boolean = settings.current().dictationReady

    /** The context for a dictation that ends now in the list [defaults] describes. */
    suspend fun context(defaults: TaskDefaults, reference: LocalDateTime? = null): DictationContext {
        dayClock.refresh()
        val day = dayClock.day.value
        val all = projects.getAllIncludingArchived().first()
        val inboxId = auth.getInboxProjectId()?.takeIf { it > 0 }
        val snapshot = AskSnapshot(
            tasks = emptyList(),
            projects = all,
            labels = emptyList(),
            customLists = emptyList(),
            collab = collaboration.state.first(),
            signals = emptyMap(),
            order = TaskOrderSettings(),
            inboxId = inboxId,
            today = day.date,
            zone = day.zone,
            reviewCadenceDays = 14,
            reviewEnabled = false,
            reviewExcludeInbox = true,
        )
        val writable = snapshot.writableProjects()
        val defaultProject = defaults.projectId?.takeIf { id -> writable.any { it.id == id } }
            ?: inboxId?.takeIf { id -> writable.any { it.id == id } }
            ?: writable.firstOrNull()?.id
            ?: 0L
        val listName = defaults.listName
            ?: defaults.projectId?.let { id -> all.firstOrNull { it.id == id }?.title }
            ?: "Inbox"
        val labelList = labels.getAll().first()
            .map { ContextLabel(it.id, it.title) }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.title }, { it.id }))
        val places = locations?.allPlaces().orEmpty()
            .map { ContextPlace(it.id, it.name) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.name }, { it.id }))
        val now = reference ?: time.now().toLocalDateTime(day.zone).let { LocalDateTime(it.date, kotlinx.datetime.LocalTime(it.hour, it.minute, it.second)) }
        return DictationContext(
            now = now,
            zone = day.zone,
            list = listName,
            defaultProjectId = defaultProject,
            defaultDue = DueDates.localDateOf(defaults.dueDate, day.zone),
            projects = writable,
            labels = labelList,
            places = places,
            weekStart = day.weekStart,
        )
    }

    /** Whisper's vocabulary hint for the same state (section 3.4). */
    suspend fun vocabularyHint(): String? {
        val all = projects.getAllIncludingArchived().first()
        val inboxId = auth.getInboxProjectId()
        val collab = collaboration.state.first()
        val hintProjects = all.map { p ->
            val permission = collab.projects[p.id]?.permission ?: p.maxPermission
            HintProject(p.title, inbox = p.id == inboxId, writable = permission != 0, archived = p.isArchived)
        }
        return VocabularyHint.build(
            hintProjects,
            labels.getAll().first().map { it.title },
            locations?.allPlaces().orEmpty().map { it.name },
        )
    }

    /**
     * Turns [transcript] into checked drafts; with [current] drafts it is an instruction about them
     * (section 4.4). Throws [LlmException] when the request fails.
     */
    suspend fun run(transcript: String, context: DictationContext, current: List<CheckedDraft>? = null): CheckResult {
        val s = settings.current()
        val connection = providers.connection(s) ?: throw LlmException(LlmException.Kind.BAD_REQUEST, "Set up the assistant first.")
        val answer = providers.structured.request(
            connection,
            ChatRequest(
                model = s.dictationModel,
                system = DictationRequests.system(editing = current != null),
                messages = listOf(ChatMessage.User(DictationRequests.userMessage(context, transcript, current))),
                schema = DictationRequests.answerSchema,
                structuredMode = s.structuredMode(s.dictationModel),
                deterministic = true,
                idleTimeoutSeconds = AiContract.limits.dictationIdleTimeoutSeconds,
                maxTokens = DICTATION_MAX_TOKENS,
            ),
        )
        return DictationChecks.check(answer, context)
    }

    /**
     * Organizes a brain dump into new projects and checked drafts (section 4.8); with the [plan] so
     * far, [transcript] is what the person said or typed next about it. Throws [LlmException].
     */
    suspend fun runBrainDump(transcript: String, context: DictationContext, plan: CheckResult? = null): CheckResult {
        val s = settings.current()
        val connection = providers.connection(s) ?: throw LlmException(LlmException.Kind.BAD_REQUEST, "Set up the assistant first.")
        val answer = providers.structured.request(
            connection,
            ChatRequest(
                model = s.dictationModel,
                system = DictationRequests.brainDumpSystem(editing = plan != null),
                messages = listOf(ChatMessage.User(DictationRequests.brainDumpMessage(context, transcript, plan))),
                schema = DictationRequests.brainDumpSchema,
                structuredMode = s.structuredMode(s.dictationModel),
                deterministic = true,
                idleTimeoutSeconds = AiContract.limits.dictationIdleTimeoutSeconds,
                maxTokens = AiContract.limits.brainDumpMaxTokens,
            ),
        )
        return BrainDumpChecks.check(answer, context)
    }

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

    companion object {
        const val DICTATION_MAX_TOKENS = 8_192
    }
}
