package com.rendyhd.vicu.di

import com.rendyhd.vicu.calendar.DeviceCalendars
import com.rendyhd.vicu.calendar.IosDeviceCalendars
import com.rendyhd.vicu.voice.IosSpeechBridgeHolder
import com.rendyhd.vicu.voice.IosSpeechInput
import com.rendyhd.vicu.voice.SpeechInput
import com.rendyhd.vicu.ai.AiKeyStore
import com.rendyhd.vicu.ai.IosAiKeyStore
import com.rendyhd.vicu.speech.AudioRecorder
import com.rendyhd.vicu.speech.IosAudioRecorder
import com.rendyhd.vicu.speech.IosSpeechEngineNative
import com.rendyhd.vicu.speech.IosSpeechOutput
import com.rendyhd.vicu.speech.IosSpeechStorage
import com.rendyhd.vicu.speech.SpeechEngineNative
import com.rendyhd.vicu.speech.SpeechOutput
import com.rendyhd.vicu.speech.SpeechStorage
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.IosAuthHooks
import com.rendyhd.vicu.auth.IosKeychainTokenStorage
import com.rendyhd.vicu.auth.PlatformAuthHooks
import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.PlatformContext
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.data.remote.VikunjaCoilInterceptor
import com.rendyhd.vicu.data.repository.IosRepositoryHooks
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.ios.BackgroundActivityLog
import com.rendyhd.vicu.ios.IosAppLifecycle
import com.rendyhd.vicu.ios.IosBackgroundTasks
import com.rendyhd.vicu.location.DeviceLocation
import com.rendyhd.vicu.location.IosDeviceLocation
import com.rendyhd.vicu.location.IosGeofencer
import com.rendyhd.vicu.location.IosLocationBridgeHolder
import com.rendyhd.vicu.location.IosLocationNotifier
import com.rendyhd.vicu.location.IosLocationPermissionPlatform
import com.rendyhd.vicu.location.IosPlaceSearch
import com.rendyhd.vicu.location.LocationNotifier
import com.rendyhd.vicu.location.LocationPermissionPlatform
import com.rendyhd.vicu.location.PlaceSearch
import com.rendyhd.vicu.location.PlatformGeofencer
import com.rendyhd.vicu.notification.IosNotificationScheduler
import com.rendyhd.vicu.permission.IosNotificationPermissionPlatform
import com.rendyhd.vicu.permission.NotificationPermissionPlatform
import com.rendyhd.vicu.share.IosShareInbox
import com.rendyhd.vicu.ui.AppInputs
import com.rendyhd.vicu.ui.screens.settings.IosSettingsHooks
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import com.rendyhd.vicu.util.IosCompletionSoundPlayer
import com.rendyhd.vicu.util.IosNetworkMonitor
import com.rendyhd.vicu.util.IosPaths
import com.rendyhd.vicu.util.IosPlatformFiles
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.TaskReminderNotifications
import com.rendyhd.vicu.widget.IosWidgetSnapshots
import com.rendyhd.vicu.worker.SyncEngine
import com.rendyhd.vicu.worker.SyncRequester
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import okio.Path.Companion.toPath
import org.koin.dsl.module
import platform.Foundation.NSHTTPCookieAcceptPolicy

/**
 * The Darwin engine with NSURLSession's own cookie handling off: the refresh token arrives as a
 * cookie and AuthManager sends it itself, so the session must not store and resend it as well.
 */
internal fun darwinEngine(): HttpClientEngine = Darwin.create {
    configureSession {
        HTTPShouldSetCookies = false
        HTTPCookieStorage = null
        HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
    }
}

/** The iOS implementations of the platform interfaces, next to [sharedModules]. */
val iosModule = module {
    single { PlatformContext() }
    single<HttpClientEngine> { darwinEngine() }
    single<TokenStorage> { IosKeychainTokenStorage() }
    single<PlatformAuthHooks> { IosAuthHooks(backgroundTasks = { get() }, widgets = { get() }) }
    single<PlatformFiles> { IosPlatformFiles() }
    single<NetworkMonitor> { IosNetworkMonitor() }
    single { AppInputs() }
    single {
        SyncRequester(
            scope = get(),
            sync = { get<SyncEngine>().performSync() },
            isSignedIn = {
                get<BaseUrlHolder>().ensureInitialized()
                !get<AuthManager>().getVikunjaUrl().isNullOrBlank()
            },
        )
    }
    single<PlatformSettingsHooks> {
        IosSettingsHooks(
            syncRequester = { get() },
            notifications = { get() },
            permission = get(),
            backgroundLog = get(),
            widgets = { get() },
        )
    }
    single { IosCompletionSoundPlayer(get()) }
    single<PlatformRepositoryHooks> {
        IosRepositoryHooks(
            syncRequester = { get() },
            soundPlayer = get(),
            notifications = { get() },
            backgroundTasks = { get() },
            widgets = { get() },
            shareInbox = { get() },
            appScope = get(),
        )
    }
    single {
        IosWidgetSnapshots(
            states = get(),
            customListStore = get(),
            routineRepository = get(),
            routinePrefs = get(),
            taskRepository = get(),
            dayClock = get(),
            syncRequester = { get() },
            appScope = get(),
        )
    }
    single {
        IosShareInbox(
            taskRepository = get(),
            attachmentRepository = get(),
            tokenStorage = get(),
            networkMonitor = get(),
            appMessages = get(),
            syncRequester = { get() },
        )
    }
    single {
        IosNotificationScheduler(
            taskDao = get(),
            taskMapper = get(),
            snoozeStore = get(),
            routineRepository = get(),
            routinePrefs = get(),
            notificationPrefs = get(),
            behaviorPrefs = get(),
            summaryReader = get(),
            authManager = get(),
            time = get(),
            appScope = get(),
        )
    }
    single<TaskReminderNotifications> { get<IosNotificationScheduler>() }
    single { BackgroundActivityLog(get()) }
    single {
        IosBackgroundTasks(
            tokenRefreshRun = get(),
            syncEngine = get(),
            notifications = get(),
            widgets = get(),
            shareInbox = get(),
            authManager = get(),
            baseUrlHolder = get(),
            pendingActionDao = get(),
            activityLog = get(),
            time = get(),
            appScope = get(),
            locations = get(),
        )
    }
    single { IosNotificationPermissionPlatform() }
    single { IosLocationBridgeHolder() }
    single<DeviceCalendars> { IosDeviceCalendars }
    single { IosSpeechBridgeHolder() }
    single<SpeechInput> { IosSpeechInput(get()) }
    // Local speech (docs/ai-assistant-v1.md, section 3) and the AI provider keys (7.2). None of
    // them touches the audio session, the Keychain or the network until first used.
    single<SpeechEngineNative> { IosSpeechEngineNative() }
    single<AudioRecorder> { IosAudioRecorder() }
    single<SpeechOutput> { IosSpeechOutput() }
    single<SpeechStorage> { IosSpeechStorage() }
    single<AiKeyStore> { IosAiKeyStore() }
    single { IosLocationPermissionPlatform(get()) }
    single<LocationPermissionPlatform> { get<IosLocationPermissionPlatform>() }
    single<PlatformGeofencer> { IosGeofencer(get()) }
    single<DeviceLocation> { IosDeviceLocation(get()) }
    single<PlaceSearch> { IosPlaceSearch() }
    single<LocationNotifier> { IosLocationNotifier() }
    single<NotificationPermissionPlatform> { get<IosNotificationPermissionPlatform>() }
    single {
        IosAppLifecycle(
            authManager = get(),
            baseUrlHolder = get(),
            syncRequester = get(),
            syncEngine = get(),
            networkMonitor = get(),
            dayClock = get(),
            notificationPermission = get(),
            notifications = get(),
            backgroundTasks = get(),
            widgets = get(),
            shareInbox = get(),
            appScope = get(),
            locations = get(),
            locationPermission = get(),
        )
    }
    single {
        val baseUrlHolder: BaseUrlHolder = get()
        val authManager: AuthManager = get()
        ImageLoader.Builder(coil3.PlatformContext.INSTANCE)
            .components {
                add(
                    VikunjaCoilInterceptor(
                        fullBaseUrl = {
                            baseUrlHolder.ensureInitialized()
                            baseUrlHolder.getFullBaseUrl()
                        },
                        token = { authManager.getBestTokenSync() ?: authManager.ensureInitializedAndGetToken() },
                    ),
                )
                add(KtorNetworkFetcherFactory(httpClient = { HttpClient(darwinEngine()) }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(IosPaths.subdirectory(IosPaths.caches, "image_cache").toPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
