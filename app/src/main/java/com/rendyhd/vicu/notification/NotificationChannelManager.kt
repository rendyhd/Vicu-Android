package com.rendyhd.vicu.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_TASK_REMINDERS = "task_reminders"
        const val CHANNEL_DAILY_SUMMARY = "daily_summary"
    }

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val taskReminders = NotificationChannel(
            CHANNEL_TASK_REMINDERS,
            "Task Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications for task reminders"
        }

        val dailySummary = NotificationChannel(
            CHANNEL_DAILY_SUMMARY,
            "Daily Summary",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Daily task summary digest"
        }

        manager.createNotificationChannels(listOf(taskReminders, dailySummary))
    }
}
