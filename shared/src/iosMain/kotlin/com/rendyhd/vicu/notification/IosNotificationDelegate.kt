package com.rendyhd.vicu.notification

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.ios.launchInBackgroundTask
import com.rendyhd.vicu.location.IosLocationNotifier
import com.rendyhd.vicu.ui.AppInputs
import com.rendyhd.vicu.ui.navigation.AppLink
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.util.LocalNotification
import com.rendyhd.vicu.util.NotificationActions
import com.rendyhd.vicu.worker.SyncEngine
import kotlinx.coroutines.CoroutineScope
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionIcon
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import com.rendyhd.vicu.notification.IosNotificationIds.ACTION_COMPLETE
import com.rendyhd.vicu.notification.IosNotificationIds.ACTION_ROUTINE_COMPLETE
import com.rendyhd.vicu.notification.IosNotificationIds.ACTION_ROUTINE_SKIP
import com.rendyhd.vicu.notification.IosNotificationIds.ACTION_SNOOZE
import com.rendyhd.vicu.notification.IosNotificationIds.CATEGORY_ROUTINE
import com.rendyhd.vicu.notification.IosNotificationIds.CATEGORY_TASK
import kotlin.time.Duration.Companion.seconds

/**
 * Answers the notification center: shows notifications while the app is open, opens what a tap
 * points at, and runs the reminder and routine buttons ([NotificationActions], as Android's
 * receivers do). A button runs in the background; iOS starts the app for it when it is not running,
 * so the delegate is set in IosApp.start, before the app finishes launching.
 */
class IosNotificationDelegate(
    private val actions: NotificationActions,
    private val inputs: AppInputs,
    private val syncEngine: SyncEngine,
    private val appScope: CoroutineScope,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {

    /** Registers the buttons and becomes the center's delegate (which holds it weakly: keep it). */
    fun install() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.setNotificationCategories(
            setOf(
                UNNotificationCategory.categoryWithIdentifier(
                    CATEGORY_TASK,
                    actions = listOf(
                        action(ACTION_COMPLETE, "Mark Complete", "checkmark.circle"),
                        action(ACTION_SNOOZE, "Snooze 15 min", "clock"),
                    ),
                    intentIdentifiers = emptyList<Any>(),
                    options = 0u,
                ),
                UNNotificationCategory.categoryWithIdentifier(
                    CATEGORY_ROUTINE,
                    actions = listOf(
                        action(ACTION_ROUTINE_COMPLETE, "Complete", "checkmark.circle"),
                        action(ACTION_ROUTINE_SKIP, "Skip", "forward"),
                    ),
                    intentIdentifiers = emptyList<Any>(),
                    options = 0u,
                ),
            ),
        )
        center.delegate = this
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        // Android shows reminders while the app is open too.
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound,
        )
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val info = didReceiveNotificationResponse.notification.request.content.userInfo
        fun text(key: String) = info[key] as? String
        val taskId = text(LocalNotification.KEY_TASK_ID)?.toLongOrNull()

        when (didReceiveNotificationResponse.actionIdentifier) {
            UNNotificationDefaultActionIdentifier -> {
                when (text(LocalNotification.KEY_KIND)) {
                    LocalNotification.Kind.REMINDER.name, LocalNotification.Kind.SNOOZE.name, IosLocationNotifier.KIND ->
                        taskId?.let { inputs.open(AppLink.OpenTask(it)) }
                    LocalNotification.Kind.ROUTINE.name -> inputs.open(AppLink.OpenView(ViewTarget.Routines))
                    LocalNotification.Kind.PLANNER_NUDGE.name -> inputs.open(AppLink.OpenView(ViewTarget.Plan))
                }
                withCompletionHandler()
            }
            ACTION_COMPLETE -> runAction(withCompletionHandler) { taskId?.let { actions.completeTask(it) } }
            ACTION_SNOOZE -> runAction(withCompletionHandler, sync = false) {
                taskId?.let { actions.snoozeTask(it, text(LocalNotification.KEY_TITLE).orEmpty().ifBlank { "Task Reminder" }) }
            }
            ACTION_ROUTINE_COMPLETE, ACTION_ROUTINE_SKIP -> runAction(withCompletionHandler) {
                val routineId = text(LocalNotification.KEY_ROUTINE_ID)
                val date = text(LocalNotification.KEY_DATE)
                val slotId = text(LocalNotification.KEY_SLOT_ID)
                if (routineId != null && date != null && slotId != null) {
                    val status = if (didReceiveNotificationResponse.actionIdentifier == ACTION_ROUTINE_SKIP) {
                        OccurrenceStatus.SKIPPED
                    } else {
                        OccurrenceStatus.COMPLETED
                    }
                    actions.setRoutineStatus(routineId, date, slotId, status)
                }
            }
            else -> withCompletionHandler()
        }
    }

    /**
     * Stores the change, tells iOS the button is handled, then sends the queued change while iOS
     * lets the app run.
     */
    private fun runAction(completion: () -> Unit, sync: Boolean = true, action: suspend () -> Unit) {
        appScope.launchInBackgroundTask("Vicu notification action", ACTION_TIMEOUT) {
            try {
                action()
            } finally {
                dispatch_async(dispatch_get_main_queue()) { completion() }
            }
            if (sync) syncEngine.performSync()
        }
    }

    private fun action(identifier: String, title: String, symbol: String) = UNNotificationAction.actionWithIdentifier(
        identifier,
        title = title,
        options = 0u,
        icon = UNNotificationActionIcon.iconWithSystemImageName(symbol),
    )

}

/** The notification categories and buttons the app registers. */
object IosNotificationIds {
    const val CATEGORY_TASK = "task_reminder"
    const val CATEGORY_ROUTINE = "routine"
    const val ACTION_COMPLETE = "complete"
    const val ACTION_SNOOZE = "snooze"
    const val ACTION_ROUTINE_COMPLETE = "routine_complete"
    const val ACTION_ROUTINE_SKIP = "routine_skip"
}

private val ACTION_TIMEOUT = 25.seconds
