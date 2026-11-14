package com.rendyhd.vicu.di

import com.rendyhd.vicu.location.DeviceLocation
import com.rendyhd.vicu.location.LocationReminderCoordinator
import com.rendyhd.vicu.location.LocationReminderStore
import com.rendyhd.vicu.location.LocationRepository
import com.rendyhd.vicu.location.SavedPlacesStore
import org.koin.dsl.module
import org.koin.core.context.startKoin
import org.koin.core.KoinApplication
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import com.rendyhd.vicu.data.local.*
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.*
import com.rendyhd.vicu.data.repository.*
import com.rendyhd.vicu.domain.repository.*
import com.rendyhd.vicu.data.remote.*
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.auth.*
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.CompletionToastCenter
import com.rendyhd.vicu.ui.screens.shared.ProjectActions
import com.rendyhd.vicu.ui.screens.guided.GuidedProgress
import com.rendyhd.vicu.ui.screens.guided.GuidedWorldSource
import com.rendyhd.vicu.ui.screens.guided.GuidedWriter
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.postIfRefused
import com.rendyhd.vicu.util.BuildInfo
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.NotificationActions
import com.rendyhd.vicu.util.RoutineReminderCheck
import com.rendyhd.vicu.data.sync.AccountEpoch
import com.rendyhd.vicu.data.sync.CarrierFinder
import com.rendyhd.vicu.data.sync.LabelRefresher
import com.rendyhd.vicu.data.sync.ProjectRefresher
import com.rendyhd.vicu.data.local.SavedFilterStore
import com.rendyhd.vicu.data.sync.SavedFilterImporter
import com.rendyhd.vicu.data.sync.ProjectTaskCounts
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.data.sync.TaskRefresher
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TextTaskCreator
import com.rendyhd.vicu.ui.components.fab.FabHint
import com.rendyhd.vicu.voice.HoldToTalk
import com.rendyhd.vicu.voice.SelectingSpeechInput
import com.rendyhd.vicu.ai.aiModule
import com.rendyhd.vicu.ai.dictation.DictationMic
import com.rendyhd.vicu.ai.dictation.DictationService
import com.rendyhd.vicu.voice.VoiceAddResult
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.widget.TaskWidgetStates
import kotlinx.coroutines.flow.first
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.worker.SyncEngine
import com.rendyhd.vicu.worker.CollaborationSync
import com.rendyhd.vicu.worker.AttachmentUploadSync
import com.rendyhd.vicu.data.local.OfflineAttachmentFiles
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.calendar.CalendarPrefsStore
import com.rendyhd.vicu.calendar.PlanCalendars

val databaseModule = module {
    single {
        getDatabaseBuilder(get()).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
    single { get<VikunjaDatabase>().taskDao() }
    single { get<VikunjaDatabase>().projectDao() }
    single { get<VikunjaDatabase>().labelDao() }
    single { get<VikunjaDatabase>().pendingActionDao() }
    single { get<VikunjaDatabase>().attachmentDao() }
    single { get<VikunjaDatabase>().routineArchiveDao() }
    single { get<VikunjaDatabase>().localDataDao() }
    single { get<VikunjaDatabase>().collaborationDao() }

    single { BehaviorPrefsStore(createDataStore(get(), "behavior_prefs")) }
    single { BottomBarPrefsStore(createDataStore(get(), "bottom_bar_prefs")) }
    single { CarrierIdStore(createDataStore(get(), "carrier_ids")) }
    single { CustomListStore(createDataStore(get(), "custom_lists")) }
    single { SavedFilterStore(createDataStore(get(), "saved_filters")) }
    single { LabelOrderPrefsStore(createDataStore(get(), "label_order_prefs")) }
    single { LogbookPrefsStore(createDataStore(get(), "logbook_prefs")) }
    single { NlpPrefsStore(createDataStore(get(), "nlp_prefs")) }
    single { NotificationPermissionStore(createDataStore(get(), "notification_permission")) }
    single { NotificationPrefsStore(createDataStore(get(), "notification_prefs")) }
    single { ProjectSectionPrefsStore(createDataStore(get(), "project_section_prefs")) }
    single { ReminderAlarmRegistry(createDataStore(get(), "reminder_alarm_registry"), get()) }
    single { ReviewPrefsStore(createDataStore(get(), "review_prefs")) }
    single { RoutinePrefsStore(createDataStore(get(), "routine_prefs")) }
    single { SnoozeStore(createDataStore(get(), "snooze_prefs"), get()) }
    single { SyncCursorStore(createDataStore(get(), "sync_cursor")) }
    single { SyncedPrefsStore(createDataStore(get(), "synced_prefs")) }
    single { ProLedgerStore(createDataStore(get(), "pro_ledger")) }
    single { TempIdGenerator(createDataStore(get(), "temp_ids")) }
    single { ThemePrefsStore(createDataStore(get(), "theme_prefs")) }
    single { SavedPlacesStore(createDataStore(get(), "saved_places"), get()) }
    single { LocationReminderStore(createDataStore(get(), "location_reminders"), get()) }
    single { LocationRepository(synced = get<SyncedPreferences>(), positions = get(), local = get()) }
    single { WidgetPrefsStore(createDataStore(get(), "widget_prefs")) }
    single { FabHintStore(createDataStore(get(), "fab_hint")) }
    single { CalendarPrefsStore(createDataStore(get(), "calendar_prefs")) }
}

val networkModule = module {
    single { createApiJson() }
    single { BaseUrlHolder(get()) }
    single { ServerReachability() }
    single {
        KtorClientFactory.create(
            engine = get(),
            json = get(),
            baseUrlHolder = get(),
            authManager = get(),
            // Set by the host app from its BuildConfig before Koin starts.
            enableLogging = BuildInfo.isDebug,
            tokenCheck = ApiTokenCheck(time = get()),
            userAgent = VikunjaUserAgent.current(),
            reachability = get(),
        )
    }
    single { VikunjaApiService(get(), get()) }
}

val repositoryModule = module {
    single { TaskMapper(get()) }
    single { LabelMapper() }
    single { ProjectMapper() }
    single { AttachmentMapper() }

    single { TaskRefresher(taskDao = get(), pendingActionDao = get(), api = get(), taskMapper = get(), platformHooks = get(), cursorStore = get(), time = get(), currentUser = get<AuthManager>(), routineDeviceId = get<RoutinePrefsStore>()::getOrCreateDeviceId, collaborationDao = get(), accountEpoch = get()) }
    single {
        LabelRefresher(
            labelDao = get(),
            taskDao = get(),
            pendingActionDao = get(),
            api = get(),
            labelMapper = get(),
            taskMapper = get(),
            accountEpoch = get(),
        )
    }
    single { ProjectRefresher(projectDao = get(), pendingActionDao = get(), api = get(), projectMapper = get(), savedFilters = get(), accountEpoch = get()) }
    single { SavedFilterImporter(api = get(), store = get(), customLists = get(), messages = get()) }

    single {
        val taskDao = get<TaskDao>()
        ListPositioner(
            api = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            time = get(),
            storePosition = { taskId, position -> taskDao.updatePosition(taskId, position) },
        )
    }
    single { TaskWriteGate(pendingActionDao = get()) }
    single<TaskRepository> {
        TaskRepositoryImpl(
            taskDao = get(),
            api = get(),
            pendingActionDao = get(),
            taskMapper = get(),
            platformHooks = get(),
            json = get(),
            behaviorPrefsStore = get(),
            logbookPrefsStore = get(),
            dayClock = get(),
            tempIds = get(),
            refresher = get(),
            positioner = get(),
            writeGate = get(),
            messages = get(),
        )
    }
    single<ProjectRepository> {
        ProjectRepositoryImpl(
            projectDao = get(),
            api = get(),
            projectMapper = get(),
            projectRefresher = get(),
            pendingActionDao = get(),
            platformHooks = get(),
            projectIcons = get(),
            writeGate = get(),
        )
    }
    single<LabelRepository> {
        LabelRepositoryImpl(
            labelDao = get(),
            taskDao = get(),
            pendingActionDao = get(),
            api = get(),
            labelMapper = get(),
            taskMapper = get(),
            platformHooks = get(),
            json = get(),
            tempIds = get(),
            labelRefresher = get(),
            writeGate = get(),
        )
    }
    single<AttachmentRepository> {
        AttachmentRepositoryImpl(
            attachmentDao = get(),
            api = get(),
            attachmentMapper = get(),
            platformFiles = get(),
            pendingActionDao = get(),
            offlineFiles = get(),
            networkMonitor = get(),
            platformHooks = get(),
        )
    }
    single {
        OfflineAttachmentFiles(
            directory = { get<PlatformFiles>().offlineAttachmentsDirectory() },
            nowMillis = { get<TimeSource>().now().toEpochMilliseconds() },
        )
    }
    single { CarrierFinder(api = get(), store = get(), time = get()) }
    single<CustomListRepository> {
        CustomListRepositoryImpl(
            store = get(),
            api = get(),
            authManager = get(),
            platformHooks = get(),
            json = get(),
            carrierFinder = get(),
            time = get(),
        )
    }
    single {
        SyncedPreferences(
            store = get(),
            api = get(),
            carrierFinder = get(),
            authManager = get(),
            platformHooks = get(),
            json = get(),
            projectDao = get(),
            messages = get(),
            time = get(),
        )
    }
    // A change redraws the widgets that show a project in its order (SyncedPreferences.set does).
    single { TaskOrderPreferences(synced = get<SyncedPreferences>()) }
    single { RoutinePersonalPreferences(synced = get<SyncedPreferences>()) }
    single { ProjectCategoryPreferences(synced = get<SyncedPreferences>()) }
    single { ProjectIconPreferences(synced = get<SyncedPreferences>()) }
    single { SubtaskOrderPreferences(synced = get<SyncedPreferences>()) }
    single { WeeklyReviewPreferences(synced = get<SyncedPreferences>()) }
    // The app start makes DayClock follow it (VicuApplication, IosApp), so every ClockDay carries it.
    single { WeekStartPreferences(synced = get<SyncedPreferences>()) }
    single { PlannerPreferences(synced = get<SyncedPreferences>()) }
    // Pro (contract 21): its synced keys, and the ledger, which syncs only while Pro is on.
    single { ProPreferences(synced = get<SyncedPreferences>()) }
    single { ProCaptureSource(behaviorPrefs = get(), proPreferences = get(), labelRepository = get(), proLedger = get()) }
    single {
        ProLedgerRepository(
            store = get(),
            api = get(),
            carrierFinder = get(),
            authManager = get(),
            behaviorPrefs = get(),
            platformHooks = get(),
            time = get(),
        )
    }
    // What the Pro screens and rows read, and what they change; nothing is read while Pro is off.
    single {
        ProWorldSource(
            behaviorPrefs = get(),
            taskRepository = get(),
            projectRepository = get(),
            labelRepository = get(),
            synced = get<SyncedPreferences>(),
            proLedger = get(),
            dayClock = get(),
            time = get(),
            scope = get(),
            dispatchers = get(),
        )
    }
    single {
        ProActions(
            taskRepository = get(),
            labelRepository = get(),
            proLedger = get(),
            proPreferences = get(),
            behaviorPrefs = get(),
            appMessages = get(),
            dayClock = get(),
            time = get(),
            scope = get(),
        )
    }
    // Your own Events (synced) and the phone's calendars (read only, choices on the device): contract 20.
    single { PlanCalendars(synced = get<SyncedPreferences>(), device = get(), prefs = get(), time = get()) }
    single<CollaborationRepository> {
        CollaborationRepositoryImpl(
            collaborationDao = get(),
            taskDao = get(),
            projectDao = get(),
            pendingActionDao = get(),
            api = get(),
            taskMapper = get(),
            projectMapper = get(),
            tokenStorage = get(),
            tempIds = get(),
            platformHooks = get(),
            writeGate = get(),
        )
    }
    single { CollaborationSync(api = get(), collaborationDao = get(), pendingActionDao = get(), tokenStorage = get()) }
    single {
        AttachmentUploadSync(
            api = get(),
            pendingActionDao = get(),
            attachmentDao = get(),
            attachmentMapper = get(),
            taskDao = get(),
            files = get(),
        )
    }
    single { TaskDuplicator(tasks = get(), labels = get(), collaboration = get()) }
    single {
        TaskTemplateStore(
            synced = get<SyncedPreferences>(),
            tasks = get(),
            labels = get(),
            projects = get(),
            notificationPrefs = get(),
            dayClock = get(),
            time = get(),
        )
    }
    single {
        RoutineArchiveStore(
            api = get(),
            json = get(),
            currentUser = get<AuthManager>(),
            readRoutines = RoutineArchiveStore.cachedRoutines(taskDao = get(), json = get(), currentUser = get<AuthManager>()),
        )
    }
    single<RoutineRepository> {
        RoutineRepositoryImpl(
            taskDao = get(),
            archiveDao = get(),
            taskMapper = get(),
            taskRepository = get(),
            authManager = get(),
            prefsStore = get(),
            platformHooks = get(),
            json = get(),
            archiveStore = get(),
            personal = get(),
            projectDao = get(),
            refresher = get(),
        )
    }
}

val commonModule = module {
    single { DailySummaryReader(get()) }
    single {
        TextTaskCreator(
            taskRepository = get(),
            projectRepository = get(),
            labelRepository = get(),
            authManager = get(),
            nlpPrefsStore = get(),
            notificationPrefsStore = get(),
            dayClock = get(),
            time = get(),
            locations = get(),
            locationPermission = get(),
            proCapture = get(),
            proLedger = get(),
        )
    }
    single { FabHint(store = get(), scope = get()) }
    // Local speech when a speech model is chosen, else the platform recognizer (holding +, Talk).
    single {
        val dictationService = get<DictationService>()
        SelectingSpeechInput(
            platform = get(),
            recorder = get(),
            host = get(),
            models = get(),
            settingsStore = get(),
            scope = get(),
            hint = { dictationService.vocabularyHint() },
        )
    }
    // Holding the + to say a task: quick capture, the words through the same path as the other voice
    // adds. Sliding up to the lock hands over to smart dictation (docs/ai-assistant-v1.md, section 4).
    single {
        val creator = get<TextTaskCreator>()
        val tasks = get<TaskRepository>()
        val hint = get<FabHint>()
        val messages = get<AppMessages>()
        val smart = get<DictationMic>()
        HoldToTalk(
            speech = get<SelectingSpeechInput>(),
            messages = messages,
            scope = get(),
            addTask = { text, format, defaults ->
                when (val result = creator.create(text, format, defaults.projectId, defaults.dueDate)) {
                    is TextTaskCreator.Result.Created -> VoiceAddResult(result.notice, result.task.id) {
                        messages.postIfRefused(tasks.delete(result.task.id), "undo the task")
                    }
                    is TextTaskCreator.Result.Failed -> VoiceAddResult(result.message)
                }
            },
            onVoiceAdded = hint::onVoiceUsed,
            canLock = { smart.ready.value },
            onLock = smart::open,
            onLockedWords = smart::listen,
        )
    }
    single { NotificationActions(taskRepository = get(), routineRepository = get(), reminders = get(), hooks = get()) }
    single { RoutineReminderCheck(repository = get(), prefsStore = get()) }
    single { TokenRefreshRun(authManager = get(), tokenStorage = get(), baseUrlHolder = get()) }
    single {
        TaskWidgetStates(
            taskDao = get(),
            projectDao = get(),
            taskRepository = get(),
            tokenStorage = get(),
            customListStore = get(),
            widgetPrefsStore = get(),
            behaviorPrefsStore = get(),
            time = get(),
            collaborationContext = {
                val collaboration: CollaborationRepository = get()
                CustomListContext.of(collaboration.state.first(), collaboration.signals.first())
            },
            taskOrders = get(),
            weekStart = { get<WeekStartPreferences>().current() },
        )
    }
    single {
        ProjectTaskCounts(
            api = get(),
            serverKey = { get<AuthManager>().getVikunjaUrl() },
            time = get(),
            scope = get(),
        )
    }
    single<ProjectProgressSource> {
        ProjectProgressSourceImpl(
            taskDao = get(),
            counts = get(),
            carrierFinder = get(),
            archiveStore = get(),
            authManager = get(),
            json = get(),
        )
    }
    single { SyncStaleness() }
    single { AccountEpoch() }
    single {
        ScreenRefresher(
            taskRepository = get(),
            projectRepository = get(),
            labelRepository = get(),
            staleness = get(),
            collaboration = get(),
            accountEpoch = get(),
        )
    }
    single { AppMessages() }
    single { NavigationTicker() }
    single { AppDispatchers() }
    single<TimeSource> { SystemTimeSource }
    single { DayClock(scope = get(), time = get()) }
    single { NotificationPermissionCoordinator(platform = get(), store = get(), scope = get()) }
    single {
        LocalDataWiper(
            dao = get(),
            customLists = get(),
            bottomBarPrefs = get(),
            platformHooks = get(),
            syncStaleness = get(),
            projectSectionPrefs = get(),
            labelOrderPrefs = get(),
            routinePrefs = get(),
            widgetPrefs = get(),
            syncCursor = get(),
            carrierIds = get(),
            listPositions = get(),
            syncedPreferences = get(),
            locationReminders = get(),
            assistantConversations = get(),
            keptTranscript = get(),
            savedFilters = get(),
            offlineAttachments = get(),
            proLedger = get(),
            accountEpoch = get(),
        )
    }
    single {
        LocationReminderCoordinator(
            locations = get(),
            taskRepository = get(),
            tempIds = get(),
            geofencer = get(),
            notifier = get(),
            notificationPrefs = get(),
            scope = get(),
            near = { get<DeviceLocation>().current() },
        )
    }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    single<CompletionToast> { CompletionToastCenter(messages = get(), taskRepository = get(), time = get(), scope = get()) }
    // Guided organizing (docs/cross-app-semantics-v1.md, section 14).
    single {
        GuidedWorldSource(
            tasks = get(),
            projects = get(),
            auth = get(),
            reviewPrefs = get(),
            taskOrder = get(),
            collabState = get<CollaborationRepository>().state,
            dayClock = get(),
        )
    }
    single { GuidedWriter(tasks = get(), proposals = get(), dayClock = get()) }
    single { GuidedProgress() }
    single {
        ProjectActions(
            projectRepository = get(),
            authManager = get(),
            repositoryHooks = get(),
            dayClock = get(),
            collaboration = get(),
            syncedPreferences = get(),
            categoryPreferences = get(),
            appMessages = get(),
            appScope = get(),
            iconPreferences = get(),
        )
    }
    single {
        AuthManager(
            platformAuthHooks = get(),
            tokenStorage = get(),
            apiServiceProvider = { get() },
            appScope = get(),
            networkMonitor = get()
        )
    }
    single {
        AccountSession(
            tokenStorage = get(),
            apiService = get(),
            wiper = get(),
            authManager = get(),
        )
    }
    single { SessionCleanup(authManager = get(), wiper = get()) }
    single {
        PasswordLoginHandler(
            apiServiceProvider = { get() }
        )
    }
    single {
        OidcHandler(
            apiServiceProvider = { get() }
        )
    }
    single {
        SyncEngine(
            pendingActionDao = get(),
            taskDao = get(),
            labelDao = get(),
            projectDao = get(),
            api = get(),
            taskMapper = get(),
            labelMapper = get(),
            projectMapper = get(),
            taskRefresher = get(),
            labelRefresher = get(),
            projectRefresher = get(),
            platformHooks = get(),
            json = get(),
            baseUrlHolder = get(),
            authManager = get(),
            customListRepository = get(),
            routineRepository = get(),
            positioner = get(),
            tempIds = get(),
            writeGate = get(),
            projectCounts = get(),
            collabSync = get(),
            collaboration = get(),
            syncedPreferences = get(),
            locations = get(),
            savedFilters = get(),
            attachmentSync = get(),
            proLedger = get(),
            messages = get(),
        )
    }
}

val sharedModules = listOf(
    databaseModule,
    networkModule,
    repositoryModule,
    commonModule,
    aiModule,
    viewModelModule,
)
