package com.rendyhd.vicu.di

import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import android.content.pm.ApplicationInfo
import java.util.concurrent.TimeUnit
import com.rendyhd.vicu.auth.AndroidAuthHooks
import com.rendyhd.vicu.auth.AndroidSecureTokenStorage
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.PlatformAuthHooks
import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.PlatformContext
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.data.remote.VikunjaImageInterceptor
import com.rendyhd.vicu.data.repository.AndroidRepositoryHooks
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.data.local.DailySummaryReader
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.notification.RoutineAlarmScheduler
import com.rendyhd.vicu.util.CompletionSoundPlayer
import com.rendyhd.vicu.util.AndroidNetworkMonitor
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.AndroidPlatformFiles
import com.rendyhd.vicu.permission.AndroidNotificationPermissionPlatform
import com.rendyhd.vicu.permission.NotificationPermissionPlatform
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import com.rendyhd.vicu.ui.screens.settings.AndroidSettingsHooks
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.androidx.workmanager.dsl.workerOf
import com.rendyhd.vicu.worker.SyncWorker
import com.rendyhd.vicu.worker.DailySummaryWorker
import com.rendyhd.vicu.worker.PeriodicSyncWorker
import com.rendyhd.vicu.worker.TokenRefreshWorker
import com.rendyhd.vicu.worker.RoutineMaintenanceWorker
import com.rendyhd.vicu.widget.RoutineWidgetActionWorker
import com.rendyhd.vicu.widget.TaskWidgetWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath

// Import all 17 ViewModels
import com.rendyhd.vicu.ui.SyncStateViewModel
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.navigation.DrawerViewModel
import com.rendyhd.vicu.ui.screens.anytime.AnytimeViewModel
import com.rendyhd.vicu.ui.screens.customlist.CustomListViewModel
import com.rendyhd.vicu.ui.screens.inbox.InboxViewModel
import com.rendyhd.vicu.ui.screens.logbook.LogbookViewModel
import com.rendyhd.vicu.ui.screens.project.ProjectViewModel
import com.rendyhd.vicu.ui.screens.review.ReviewViewModel
import com.rendyhd.vicu.ui.screens.routines.RoutinesViewModel
import com.rendyhd.vicu.ui.screens.search.SearchViewModel
import com.rendyhd.vicu.ui.screens.settings.SettingsViewModel
import com.rendyhd.vicu.ui.screens.setup.SetupViewModel
import com.rendyhd.vicu.ui.screens.tag.TagViewModel
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailViewModel
import com.rendyhd.vicu.ui.screens.taskentry.TaskEntryViewModel
import com.rendyhd.vicu.ui.screens.today.TodayViewModel
import com.rendyhd.vicu.ui.screens.upcoming.UpcomingViewModel

val appModule = module {
    single { PlatformContext(androidContext()) }
    single<TokenStorage> { AndroidSecureTokenStorage(androidContext()) }
    single<PlatformAuthHooks> { AndroidAuthHooks(androidContext()) }
    single<PlatformRepositoryHooks> {
        AndroidRepositoryHooks(
            context = androidContext(),
            alarmScheduler = get(),
            routineAlarmSchedulerProvider = { get() },
            completionSoundPlayer = get(),
            appScope = get(),
            dailySummaryScheduler = get(),
        )
    }
    single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
    single<HttpClientEngine> { OkHttp.create() }
    single<PlatformFiles> { AndroidPlatformFiles(get()) }
    single<PlatformSettingsHooks> { AndroidSettingsHooks(androidContext(), get()) }
    single { AndroidNotificationPermissionPlatform(androidContext()) }
    single<NotificationPermissionPlatform> { get<AndroidNotificationPermissionPlatform>() }

    single { AlarmScheduler(androidContext(), get(), get(), get(), get(), get()) }
    single { RoutineAlarmScheduler(androidContext(), get(), get(), get()) }
    single { DailySummaryScheduler(androidContext(), get()) }
    single { DailySummaryReader(get()) }
    single { CompletionSoundPlayer(androidContext(), get()) }
    single { NotificationChannelManager(androidContext()) }

    single {
        val baseUrlHolder = get<BaseUrlHolder>()
        val authManager = get<AuthManager>()
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                VikunjaImageInterceptor(
                    getFullBaseUrl = baseUrlHolder::getFullBaseUrl,
                    initializeBaseUrl = baseUrlHolder::ensureInitializedBlocking,
                    getCachedToken = authManager::getBestTokenSync,
                    initializeAuth = {
                        runBlocking {
                            authManager.ensureInitializedAndGetToken()
                        }
                    },
                )
            )

        val context = androidContext()
        val isDebug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebug) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                }
            )
        }
        builder.build()
    }

    single {
        ImageLoader.Builder(androidContext())
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { get<OkHttpClient>() }))
            }
            .diskCache(
                DiskCache.Builder()
                    .directory(androidContext().cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            )
            .build()
    }
}

val viewModelModule = module {
    viewModelOf(::SyncStateViewModel)
    viewModelOf(::SelectionViewModel)
    viewModelOf(::DrawerViewModel)
    viewModelOf(::AnytimeViewModel)
    viewModelOf(::CustomListViewModel)
    viewModelOf(::InboxViewModel)
    viewModelOf(::LogbookViewModel)
    viewModelOf(::ProjectViewModel)
    viewModelOf(::ReviewViewModel)
    viewModelOf(::RoutinesViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::SetupViewModel)
    viewModelOf(::TagViewModel)
    viewModelOf(::TaskDetailViewModel)
    viewModelOf(::TaskEntryViewModel)
    viewModelOf(::TodayViewModel)
    viewModelOf(::UpcomingViewModel)
}

val workerModule = module {
    workerOf(::SyncWorker)
    workerOf(::PeriodicSyncWorker)
    workerOf(::DailySummaryWorker)
    workerOf(::TokenRefreshWorker)
    workerOf(::TaskWidgetWorker)
    workerOf(::RoutineMaintenanceWorker)
    workerOf(::RoutineWidgetActionWorker)
}

val androidAppModules = listOf(
    appModule,
    viewModelModule,
    workerModule
)
