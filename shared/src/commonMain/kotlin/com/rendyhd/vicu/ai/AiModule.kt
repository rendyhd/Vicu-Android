package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.ask.AskBackend
import com.rendyhd.vicu.ai.ask.AskList
import com.rendyhd.vicu.ai.ask.AskSnapshot
import com.rendyhd.vicu.ai.ask.ConversationStore
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationController
import com.rendyhd.vicu.ai.dictation.DictationMic
import com.rendyhd.vicu.ai.dictation.DictationService
import com.rendyhd.vicu.ai.dictation.DraftWriter
import com.rendyhd.vicu.ai.dictation.KeptTranscriptStore
import com.rendyhd.vicu.ai.dictation.PlanWriter
import com.rendyhd.vicu.ai.organize.OrganizeService
import com.rendyhd.vicu.ai.organize.OrganizeSuggester
import com.rendyhd.vicu.ai.organize.PlanDayService
import com.rendyhd.vicu.ai.organize.PlanDaySuggester
import com.rendyhd.vicu.ai.provider.LlmClient
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.local.createDataStore
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.speech.SpeechEngineHost
import com.rendyhd.vicu.speech.SpeechModelManager
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.ui.screens.ask.AskEntry
import com.rendyhd.vicu.voice.SelectingSpeechInput
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.coroutines.flow.first
import org.koin.dsl.module

/** What Ask reads, from the repositories (`docs/ai-assistant-v1.md`, section 5). */
class RepositoryAskBackend(
    private val tasks: TaskRepository,
    private val projects: ProjectRepository,
    private val labels: LabelRepository,
    private val customLists: CustomListRepository,
    private val collaboration: CollaborationRepository,
    private val taskOrder: TaskOrderPreferences,
    private val reviewPrefs: ReviewPrefsStore,
    private val auth: AuthManager,
    private val dayClock: DayClock,
    private val dictation: DictationService,
) : AskBackend {
    override suspend fun snapshot(): AskSnapshot {
        dayClock.refresh()
        val day = dayClock.day.value
        val review = reviewPrefs.getPrefs().first()
        return AskSnapshot(
            tasks = tasks.getAllTasksFlat().first(),
            projects = projects.getAllIncludingArchived().first(),
            labels = labels.getAll().first(),
            customLists = customLists.lists.first(),
            collab = collaboration.state.first(),
            signals = collaboration.signals.first(),
            order = taskOrder.settings.first(),
            inboxId = auth.getInboxProjectId()?.takeIf { it > 0 },
            today = day.date,
            zone = day.zone,
            reviewCadenceDays = review.defaultCadenceDays,
            reviewEnabled = review.enabled,
            reviewExcludeInbox = review.excludeInbox,
        )
    }

    override suspend fun logbook(): List<Task> = tasks.getLogbookTasks().first()

    override suspend fun dictationContext(list: AskList): DictationContext =
        dictation.context(TaskDefaults(projectId = list.projectId, listName = list.name))
}

/**
 * The assistant's shared definitions. The platform modules bind [AiKeyStore] and the speech seams
 * (`SpeechEngineNative`, `AudioRecorder`, `SpeechOutput`, `SpeechStorage`).
 */
val aiModule = module {
    single { AiSettingsStore(createDataStore(get(), "ai_settings")) }
    single { ConversationStore(createDataStore(get(), "ai_conversations")) }
    single { KeptTranscriptStore(createDataStore(get(), "ai_kept_transcript")) }
    single { LlmClient(engine = get()) }
    single { AiProviders(client = get(), keys = get(), settingsStore = get()) }
    single { SpeechEngineHost(native = get(), storage = get(), io = get<AppDispatchers>().io) }
    single { SpeechModelManager(engine = get(), storage = get(), scope = get(), io = get<AppDispatchers>().io) }
    single {
        DictationService(
            providers = get(),
            settings = get(),
            projects = get(),
            labels = get(),
            collaboration = get(),
            auth = get(),
            dayClock = get(),
            locations = get(),
            time = get(),
        )
    }
    single { DraftWriter(tasks = get(), labels = get(), notificationPrefs = get(), locations = get()) }
    single { PlanWriter(projects = get(), drafts = get()) }
    single { Proposals(tasks = get(), labels = get(), drafts = get()) }
    single<OrganizeSuggester> { OrganizeService(providers = get(), settings = get(), backend = get(), dictation = get()) }
    single<PlanDaySuggester> { PlanDayService(providers = get(), settings = get(), backend = get(), dictation = get()) }
    single {
        DictationController(
            service = get(),
            writer = get(),
            planWriter = get(),
            kept = get(),
            textTasks = get(),
            tasks = get(),
            messages = get(),
            dayClock = get(),
            scope = get(),
        )
    }
    single {
        DictationMic(
            controller = get(),
            speech = get<SelectingSpeechInput>(),
            settingsStore = get(),
            messages = get(),
            scope = get(),
        )
    }
    single { AskEntry() }
    single<AskBackend> {
        RepositoryAskBackend(
            tasks = get(),
            projects = get(),
            labels = get(),
            customLists = get(),
            collaboration = get(),
            taskOrder = get(),
            reviewPrefs = get(),
            auth = get(),
            dayClock = get(),
            dictation = get(),
        )
    }
}
