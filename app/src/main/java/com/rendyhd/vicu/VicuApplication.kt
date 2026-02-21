package com.rendyhd.vicu

import android.app.Application
import androidx.work.Configuration
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VicuApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory
    @Inject lateinit var notificationChannelManager: NotificationChannelManager

    override fun onCreate() {
        super.onCreate()
        notificationChannelManager.createChannels()
        WidgetUpdateScheduler.schedulePeriodicRefresh(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
