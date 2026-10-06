package com.rendyhd.vicu

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.util.BuildInfo
import com.rendyhd.vicu.util.DayChangeReceiver
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.RoutineMaintenanceScheduler
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
        WidgetUpdateScheduler.schedulePeriodicRefresh(this)
        RoutineMaintenanceScheduler.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
