package com.rendyhd.vicu

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.util.BuildInfo
import com.rendyhd.vicu.util.DayChangeReceiver
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.RoutineMaintenanceScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import com.rendyhd.vicu.worker.reconnections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.androidx.workmanager.koin.workManagerFactory
import com.rendyhd.vicu.di.androidAppModules
import com.rendyhd.vicu.di.sharedModules

class VicuApplication : Application(), SingletonImageLoader.Factory, KoinComponent {

    private val notificationChannelManager: NotificationChannelManager by inject()
    private val imageLoader: ImageLoader by inject()
    private val dayClock: DayClock by inject()
    private val networkMonitor: NetworkMonitor by inject()
    private val authManager: AuthManager by inject()
    private val appScope: CoroutineScope by inject()

    override fun onCreate() {
        super.onCreate()
        // Tell the shared module which build this is before anything logs or builds the HTTP
        // client: release builds get no request logging, no debug logs and no auth log file.
        BuildInfo.configure(isDebug = BuildConfig.DEBUG)
        AuthDebugLog.init(this)
        startKoin {
            androidContext(this@VicuApplication)
            workManagerFactory()
            modules(sharedModules + androidAppModules)
        }
        notificationChannelManager.createChannels()
        DayChangeReceiver.register(this, dayClock)
        appScope.launch {
            // Only for an account: sign-out cancels the account's background work, and scheduling
            // it again at every start would undo that. Signing in schedules it (MainActivity).
            if (!authManager.getVikunjaUrl().isNullOrBlank()) {
                WidgetUpdateScheduler.schedulePeriodicRefresh(this@VicuApplication)
                RoutineMaintenanceScheduler.schedule(this@VicuApplication)
            }
        }
        // A sync that failed while the device was offline waits out its backoff, minutes to hours,
        // even after the network is back. Start it as soon as the network returns instead.
        appScope.launch {
            networkMonitor.isOnline.reconnections().collect {
                if (!authManager.getVikunjaUrl().isNullOrBlank()) {
                    SyncScheduler.enqueueWhenOnline(this@VicuApplication)
                }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
