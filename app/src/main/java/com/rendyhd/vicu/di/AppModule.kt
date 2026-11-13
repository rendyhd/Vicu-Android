package com.rendyhd.vicu.di

import com.rendyhd.vicu.calendar.AndroidDeviceCalendars
import com.rendyhd.vicu.calendar.DeviceCalendars
import com.rendyhd.vicu.voice.AndroidSpeechInput
import com.rendyhd.vicu.voice.SpeechInput
import com.rendyhd.vicu.ai.AiKeyStore
import com.rendyhd.vicu.ai.AndroidAiKeyStore
import com.rendyhd.vicu.speech.AndroidAudioRecorder
import com.rendyhd.vicu.speech.AndroidSpeechEngineNative
import com.rendyhd.vicu.speech.AndroidSpeechOutput
import com.rendyhd.vicu.speech.AndroidSpeechStorage
import com.rendyhd.vicu.speech.AudioRecorder
import com.rendyhd.vicu.speech.SpeechEngineNative
import com.rendyhd.vicu.speech.SpeechOutput
import com.rendyhd.vicu.speech.SpeechStorage
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
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
import com.rendyhd.vicu.util.TaskReminderNotifications
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.notification.PlannerNudgeScheduler
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.notification.RoutineAlarmScheduler
import com.rendyhd.vicu.util.CompletionSoundPlayer
import com.rendyhd.vicu.util.AndroidNetworkMonitor
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.AndroidPlatformFiles
import com.rendyhd.vicu.BuildConfig
import com.rendyhd.vicu.location.AndroidDeviceLocation
import com.rendyhd.vicu.location.AndroidGeofencer
import com.rendyhd.vicu.location.AndroidLocationNotifier
import com.rendyhd.vicu.location.AndroidLocationPermissionPlatform
import com.rendyhd.vicu.location.DeviceLocation
import com.rendyhd.vicu.location.LocationNotifier
import com.rendyhd.vicu.location.LocationPermissionPlatform
import com.rendyhd.vicu.location.PhotonPlaceSearch
import com.rendyhd.vicu.location.PlaceSearch
import com.rendyhd.vicu.location.PlatformGeofencer
import com.rendyhd.vicu.permission.AndroidNotificationPermissionPlatform
import com.rendyhd.vicu.permission.NotificationPermissionPlatform
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import com.rendyhd.vicu.ui.screens.settings.AndroidSettingsHooks
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.androidx.workmanager.dsl.workerOf
import com.rendyhd.vicu.worker.SyncWorker
import com.rendyhd.vicu.worker.DailySummaryWorker
import com.rendyhd.vicu.worker.PlannerNudgeWorker
import com.rendyhd.vicu.worker.PeriodicSyncWorker
import com.rendyhd.vicu.worker.TokenRefreshWorker
import com.rendyhd.vicu.worker.RoutineMaintenanceWorker
import com.rendyhd.vicu.widget.RoutineWidgetActionWorker
import com.rendyhd.vicu.widget.TaskWidgetWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath


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
            plannerNudgeScheduler = get(),
            imageLoaderProvider = { get() },
        )
    }
    single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
    single<HttpClientEngine> { OkHttp.create() }
    single<PlatformFiles> { AndroidPlatformFiles(get()) }
    single<PlatformSettingsHooks> { AndroidSettingsHooks(androidContext(), get()) }
    single { AndroidNotificationPermissionPlatform(androidContext()) }
    single<NotificationPermissionPlatform> { get<AndroidNotificationPermissionPlatform>() }
    single { AndroidLocationPermissionPlatform(androidContext()) }
    single<LocationPermissionPlatform> { get<AndroidLocationPermissionPlatform>() }
    single<DeviceCalendars> { AndroidDeviceCalendars(androidContext()) }
    single { AndroidSpeechInput(androidContext()) }
    single<SpeechInput> { get<AndroidSpeechInput>() }
    // Local speech (docs/ai-assistant-v1.md, section 3) and the AI provider keys (7.2). The
    // recorder asks for the microphone through the same prompt as holding +.
    single<SpeechEngineNative> { AndroidSpeechEngineNative() }
    single<AudioRecorder> { AndroidAudioRecorder(get()) }
    single<SpeechOutput> { AndroidSpeechOutput(androidContext()) }
    single<SpeechStorage> { AndroidSpeechStorage(androidContext()) }
    single<AiKeyStore> { AndroidAiKeyStore(androidContext()) }
    single<PlatformGeofencer> { AndroidGeofencer(androidContext()) }
    single<DeviceLocation> { AndroidDeviceLocation(androidContext()) }
    single<LocationNotifier> { AndroidLocationNotifier(androidContext()) }
    single<PlaceSearch> {
        PhotonPlaceSearch(
            engine = get(),
            userAgent = "Vicu/${BuildConfig.VERSION_NAME} (Android; https://github.com/rendyhd/Vicu-Mobile)",
        )
    }

    single { AlarmScheduler(androidContext(), get(), get(), get(), get(), get()) }
    single<TaskReminderNotifications> { get<AlarmScheduler>() }
    single { RoutineAlarmScheduler(androidContext(), get(), get(), get()) }
    single { DailySummaryScheduler(androidContext(), get()) }
    single { PlannerNudgeScheduler(androidContext(), get()) }
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

val workerModule = module {
    workerOf(::SyncWorker)
    workerOf(::PeriodicSyncWorker)
    workerOf(::DailySummaryWorker)
    workerOf(::PlannerNudgeWorker)
    workerOf(::TokenRefreshWorker)
    workerOf(::TaskWidgetWorker)
    workerOf(::RoutineMaintenanceWorker)
    workerOf(::RoutineWidgetActionWorker)
}

val androidAppModules = listOf(
    appModule,
    workerModule
)
