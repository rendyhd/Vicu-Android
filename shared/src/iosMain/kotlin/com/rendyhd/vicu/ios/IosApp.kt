package com.rendyhd.vicu.ios

import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.data.repository.WeekStartPreferences
import com.rendyhd.vicu.di.iosModule
import com.rendyhd.vicu.di.sharedModules
import com.rendyhd.vicu.location.AppleLocationBridge
import com.rendyhd.vicu.location.IosLocationBridgeHolder
import com.rendyhd.vicu.location.IosLocationPermissionPlatform
import com.rendyhd.vicu.location.LocationReminderCoordinator
import com.rendyhd.vicu.util.DayClock
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.seconds
import com.rendyhd.vicu.ui.AppInputs
import com.rendyhd.vicu.notification.IosNotificationDelegate
import com.rendyhd.vicu.ui.UiStateStore
import com.rendyhd.vicu.ui.navigation.AppLink
import com.rendyhd.vicu.ui.components.shared.deviceUses24Hour
import com.rendyhd.vicu.util.BuildInfo
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.TextTaskCreator
import kotlinx.coroutines.CancellationException
import com.rendyhd.vicu.widget.AppleWidgetBridge
import com.rendyhd.vicu.widget.IosWidgetSnapshots
import com.rendyhd.vicu.voice.AppleSpeechBridge
import com.rendyhd.vicu.voice.IosSpeechBridgeHolder
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle
import kotlin.experimental.ExperimentalNativeApi

/**
 * The entry points the Swift app calls (`IosApp.shared` in Swift): [start] from the app delegate
 * before anything else, [onActive] and [onBackground] from the scene phase, [openUrl] for links.
 */
object IosApp {
    private var started = false

    // The notification center holds its delegate weakly. Built here, not by Koin: Koin cannot
    // declare a subclass of an Objective-C class.
    private var notificationDelegate: IosNotificationDelegate? = null

    /**
     * [widgetBridge] reloads the widgets through WidgetKit, which only Swift can call;
     * [locationBridge] watches the location reminders' regions with CLMonitor; [speechBridge]
     * listens for holding the + button (SFSpeechRecognizer).
     */
    @OptIn(ExperimentalNativeApi::class)
    fun start(widgetBridge: AppleWidgetBridge?, locationBridge: AppleLocationBridge?, speechBridge: AppleSpeechBridge?) {
        if (started) return
        started = true
        // Before anything logs or builds the HTTP client: release builds log no requests.
        BuildInfo.configure(
            isDebug = Platform.isDebugBinary,
            versionName = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "",
        )
        AuthDebugLog.init(null)
        startKoin { modules(sharedModules + iosModule) }
        SingletonImageLoader.setSafe { KoinPlatform.getKoin().get<ImageLoader>() }
        // Both before the app finishes launching: iOS hands a notification action or a background
        // task to the app right after, and needs the delegate and the handlers in place.
        val koin = KoinPlatform.getKoin()
        notificationDelegate = IosNotificationDelegate(
            actions = koin.get(),
            inputs = koin.get(),
            syncEngine = koin.get(),
            appScope = koin.get(),
        ).also { it.install() }
        // Every ClockDay carries the account's week start from here on (contract section 2.1).
        koin.get<DayClock>().follow(koin.get<WeekStartPreferences>().weekStart)
        koin.get<IosBackgroundTasks>().register()
        koin.get<IosWidgetSnapshots>().bridge = widgetBridge
        koin.get<IosLocationBridgeHolder>().bridge = locationBridge
        koin.get<IosSpeechBridgeHolder>().bridge = speechBridge
        lifecycle().start()
    }

    fun onActive() = lifecycle().onActive()

    fun onBackground() = lifecycle().onBackground()

    /** The UI's state for the scene to keep while the app is away (scene storage in Swift). */
    fun saveUiState(): String = UiStateStore.save()

    /** Opens a `vicu://` link; false when it names nothing the app can open. */
    fun openUrl(url: String): Boolean {
        val link = AppLink.parse(url) ?: return false
        KoinPlatform.getKoin().get<AppInputs>().open(link)
        return true
    }

    /** Opens the task entry (the Control Center control, the quick add shortcut, the quick action). */
    fun openQuickAdd() {
        KoinPlatform.getKoin().get<AppInputs>().open(AppLink.AddTask())
    }

    /** Starts dictation (the home-screen quick action): smart dictation when it is set up, else the voice card. */
    fun startDictation() {
        KoinPlatform.getKoin().get<AppInputs>().open(AppLink.Dictate)
    }

    /**
     * Adds a task from one sentence, read like quick add, and returns what Siri says back (the Add
     * a task intent: "Hey Siri, add a task in Vicu", then the task). Runs without the app's screen,
     * also on a locked phone; the task is stored on the device and the sync sends it. Never throws:
     * a failure is the reply.
     */
    suspend fun addTaskFromText(text: String): String = try {
        val creator = KoinPlatform.getKoin().get<TextTaskCreator>()
        when (val result = creator.create(text, DateDisplayFormat.system(deviceUses24Hour()))) {
            is TextTaskCreator.Result.Created -> result.confirmation
            is TextTaskCreator.Result.Failed -> result.message
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w("IosApp", "Voice add failed: ${e.message}")
        "Vicu could not add the task. Try again in the app."
    }

    /**
     * CLMonitor saw the device enter ([entered]) or leave a watched region. iOS starts the app in
     * the background for it when it is not running; the reminders are shown while it lets the app run.
     */
    fun onRegionEvent(regionId: String, entered: Boolean) {
        val koin = KoinPlatform.getKoin()
        koin.get<CoroutineScope>().launchInBackgroundTask("Vicu location reminder", 25.seconds) {
            koin.get<LocationReminderCoordinator>().onRegionEvent(regionId, entered)
        }
    }

    /** The location permission changed (answered a prompt, or changed in Settings). */
    fun onLocationAuthorizationChanged() {
        val koin = KoinPlatform.getKoin()
        koin.get<IosLocationPermissionPlatform>().refresh()
        koin.get<LocationReminderCoordinator>().requestReplan()
    }

    private fun lifecycle(): IosAppLifecycle = KoinPlatform.getKoin().get()
}
