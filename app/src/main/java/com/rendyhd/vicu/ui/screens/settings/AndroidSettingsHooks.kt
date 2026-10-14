package com.rendyhd.vicu.ui.screens.settings

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.quicksettings.QuickAddTileService
import com.rendyhd.vicu.widget.RoutineWidget
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AndroidSettingsHooks(
    private val context: Context,
    private val dailySummaryScheduler: DailySummaryScheduler,
) : PlatformSettingsHooks {

    override val supportsQuickAddTile: Boolean = true

    override fun updateWidgets() {
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
        CoroutineScope(Dispatchers.IO).launch { runCatching { RoutineWidget().updateAllWidgets(context) } }
    }

    override fun scheduleSync(enabled: Boolean) {
        if (enabled) {
            SyncScheduler.enqueueWhenOnline(context)
        } else {
            SyncScheduler.cancel(context)
        }
    }

    override fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int) {
        dailySummaryScheduler.scheduleIfEnabled(slot, enabled, hour, minute)
    }

    override fun sendTestNotification(): String? {
        val tapIntent = Intent(context, MainActivity::class.java)
        val tapPending = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannelManager.CHANNEL_TASK_REMINDERS,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test Notification")
            .setContentText("Task reminders are working!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(888_888, notification)
            "Test notification sent"
        } catch (e: SecurityException) {
            throw Exception("Notification permission not granted")
        }
    }

    override fun requestQuickAddTile(onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult("Open Quick Settings, tap Edit, then drag Add task into your active tiles")
            return
        }

        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
        statusBarManager.requestAddTileService(
            ComponentName(context, QuickAddTileService::class.java),
            context.getString(R.string.quick_add_tile_label),
            Icon.createWithResource(context, R.drawable.ic_quick_add_tile),
            context.mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                    "Quick Add tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    "Quick Add tile is already added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                    "Quick Add tile wasn't added"
                else -> "Couldn't add the Quick Add tile"
            }
            onResult(message)
        }
    }

    override fun triggerImmediateSync() {
        SyncScheduler.enqueueImmediate(context)
    }
}
