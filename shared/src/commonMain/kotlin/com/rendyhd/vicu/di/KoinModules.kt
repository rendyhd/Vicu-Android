package com.rendyhd.vicu.di

import org.koin.dsl.module
import org.koin.core.context.startKoin
import org.koin.core.KoinApplication
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import com.rendyhd.vicu.data.local.*
import com.rendyhd.vicu.data.mapper.*
import com.rendyhd.vicu.data.repository.*
import com.rendyhd.vicu.domain.repository.*
import com.rendyhd.vicu.data.remote.*
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.auth.*
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.BuildInfo
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.data.sync.LabelRefresher
import com.rendyhd.vicu.data.sync.ProjectRefresher
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.data.sync.TaskRefresher
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.worker.SyncEngine

val databaseModule = module {
    single {
        getDatabaseBuilder(get()).addMigrations(MIGRATION_1_2).build()
    }
    single { get<VikunjaDatabase>().taskDao() }
    single { get<VikunjaDatabase>().projectDao() }
    single { get<VikunjaDatabase>().labelDao() }
    single { get<VikunjaDatabase>().pendingActionDao() }
    single { get<VikunjaDatabase>().attachmentDao() }
    single { get<VikunjaDatabase>().routineArchiveDao() }
    single { get<VikunjaDatabase>().localDataDao() }

    single { BehaviorPrefsStore(createDataStore(get(), "behavior_prefs")) }
    single { BottomBarPrefsStore(createDataStore(get(), "bottom_bar_prefs")) }
    single { CustomListStore(createDataStore(get(), "custom_lists")) }
    single { LabelOrderPrefsStore(createDataStore(get(), "label_order_prefs")) }
    single { LogbookPrefsStore(createDataStore(get(), "logbook_prefs")) }
    single { NlpPrefsStore(createDataStore(get(), "nlp_prefs")) }
    single { NotificationPrefsStore(createDataStore(get(), "notification_prefs")) }
    single { ProjectSectionPrefsStore(createDataStore(get(), "project_section_prefs")) }
    single { ReminderAlarmRegistry(createDataStore(get(), "reminder_alarm_registry"), get()) }
    single { ReviewPrefsStore(createDataStore(get(), "review_prefs")) }
    single { RoutinePrefsStore(createDataStore(get(), "routine_prefs")) }
    single { SnoozeStore(createDataStore(get(), "snooze_prefs"), get()) }
    single { SyncCursorStore(createDataStore(get(), "sync_cursor")) }
    single { TempIdGenerator(createDataStore(get(), "temp_ids")) }
    single { ThemePrefsStore(createDataStore(get(), "theme_prefs")) }
    single { WidgetPrefsStore(createDataStore(get(), "widget_prefs")) }
}

val networkModule = module {
    single { createApiJson() }
    single { BaseUrlHolder(get()) }
    single {
        KtorClientFactory.create(
            engine = get(),
            json = get(),
            baseUrlHolder = get(),
            authManager = get(),
            // Set by the host app from its BuildConfig before Koin starts.
            enableLogging = BuildInfo.isDebug,
        )
    }
    single { VikunjaApiService(get(), get()) }
}

val repositoryModule = module {
    single { TaskMapper(get()) }
    single { LabelMapper() }
    single { ProjectMapper() }
    single { AttachmentMapper() }

    single { TaskRefresher(taskDao = get(), pendingActionDao = get(), api = get(), taskMapper = get(), platformHooks = get(), cursorStore = get(), time = get()) }
    single {
        LabelRefresher(
            labelDao = get(),
            taskDao = get(),
            pendingActionDao = get(),
            api = get(),
            labelMapper = get(),
            taskMapper = get(),
        )
    }
    single { ProjectRefresher(projectDao = get(), pendingActionDao = get(), api = get(), projectMapper = get()) }

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
        )
    }
    single<AttachmentRepository> {
        AttachmentRepositoryImpl(
            attachmentDao = get(),
            api = get(),
            attachmentMapper = get(),
            platformFiles = get(),
        )
    }
    single<CustomListRepository> {
        CustomListRepositoryImpl(
            store = get(),
            api = get(),
            authManager = get(),
            platformHooks = get(),
            json = get(),
        )
    }
    single { RoutineArchiveStore(api = get(), json = get()) }
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
        )
    }
}

val commonModule = module {
    single { SyncStaleness() }
    single {
        ScreenRefresher(
            taskRepository = get(),
            projectRepository = get(),
            labelRepository = get(),
            staleness = get(),
        )
    }
    single { AppMessages() }
    single<TimeSource> { SystemTimeSource }
    single { DayClock(scope = get(), time = get()) }
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
        )
    }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
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
        )
    }
}

val sharedModules = listOf(
    databaseModule,
    networkModule,
    repositoryModule,
    commonModule
)
