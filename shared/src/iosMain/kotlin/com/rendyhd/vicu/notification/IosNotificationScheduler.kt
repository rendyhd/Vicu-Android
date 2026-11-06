package com.rendyhd.vicu.notification

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.DailySummaryReader
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.local.SnoozeEntry
import com.rendyhd.vicu.data.local.SnoozeStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.model.isFinished
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.location.IosLocationNotifier
import com.rendyhd.vicu.util.LocalNotification
import com.rendyhd.vicu.util.LocalNotificationPlanner
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.PlannerNudge
import com.rendyhd.vicu.util.RoutineAlarmPlanner
import com.rendyhd.vicu.util.SnoozeRules
import com.rendyhd.vicu.util.TaskReminderNotifications
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSDateComponents
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneWithName
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app's local notifications on iOS: task reminders, snoozes, routine reminders and the daily
 * summaries, set ahead with UNUserNotificationCenter.
 *
 * iOS shows a scheduled notification without running the app, so what Android decides when an
 * alarm fires is decided here, every time something changes: [replan] builds the whole set
 * ([LocalNotificationPlanner], within iOS's 64 pending notifications), replaces what changed and
 * removes what is no longer wanted. It also takes away delivered reminders of tasks that are done
 * by now. A reminder of a task completed on another device can still ring until the app has synced
 * (docs/ios.md).
 *
 * The notification center is reached only when used: it exists only in a running app.
 */
class IosNotificationScheduler(
    private val taskDao: TaskDao,
    private val taskMapper: TaskMapper,
    private val snoozeStore: SnoozeStore,
    private val routineRepository: RoutineRepository,
    private val routinePrefs: RoutinePrefsStore,
    private val notificationPrefs: NotificationPrefsStore,
    private val behaviorPrefs: BehaviorPrefsStore,
    private val summaryReader: DailySummaryReader,
    private val authManager: AuthManager,
    private val time: TimeSource,
    private val appScope: CoroutineScope,
) : TaskReminderNotifications {

    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    /** Plans again after changes ([requestReplan], debounced) and when the notification settings change. */
    fun start() {
        if (started) return
        started = true
        appScope.launch {
            for (request in requests) {
                // Edits come in bursts (a sync, typing in the editor): plan once they settle.
                delay(DEBOUNCE)
                replan()
            }
        }
        appScope.launch {
            combine(
                notificationPrefs.getPrefs(),
                routinePrefs.remindersEnabled,
                routinePrefs.enabled,
                behaviorPrefs.getPrefs().map { PlannerNudge.timeOf(it.plannerEnabled, it.plannerNudge) },
            ) { prefs, routineReminders, routines, nudge ->
                listOf(prefs, routineReminders, routines, nudge)
            }.distinctUntilChanged().drop(1).collect { requestReplan() }
        }
    }

    fun requestReplan() {
        requests.trySend(Unit)
    }

    /** Brings the pending notifications in line with the data now. */
    suspend fun replan() = mutex.withLock {
        try {
            val wanted = if (signedIn()) plan() else emptyList()
            apply(wanted)
            removeStaleDelivered()
            Logger.d(TAG, "Planned ${wanted.size} notifications: ${wanted.joinToString { "${it.id} at ${it.trigger.at}" }}")
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Planning notifications failed: ${e.message}")
        }
    }

    override suspend fun cancelForTask(taskId: Long) {
        snoozeStore.remove(taskId)
        val prefixes = listOf("reminder:$taskId:", LocalNotificationPlanner.snoozeId(taskId))
        val delivered = deliveredNotifications().map { it.request.identifier }.filter { id ->
            prefixes.any { id.startsWith(it) } || id == IosLocationNotifier.idFor(taskId)
        }
        if (delivered.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(delivered)
        replan()
    }

    override suspend fun snooze(taskId: Long, taskTitle: String) {
        snoozeStore.put(SnoozeEntry(taskId, taskTitle, SnoozeRules.triggerAfterSnooze(time.now().toEpochMilliseconds())))
        replan()
    }

    /** Sign-out: removes every pending and delivered notification and forgets the snoozes. */
    suspend fun cancelAll() = mutex.withLock {
        snoozeStore.all().forEach { snoozeStore.remove(it.taskId) }
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
    }

    /** Settings' test notification: shown at once. Throws when notifications are not allowed. */
    fun sendTest() {
        val content = UNMutableNotificationContent().apply {
            setTitle("Test Notification")
            setBody("Task reminders are working!")
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(TEST_ID, content, trigger), withCompletionHandler = null)
    }

    private suspend fun signedIn(): Boolean = !authManager.getVikunjaUrl().isNullOrBlank()

    private suspend fun plan(): List<LocalNotification> {
        val prefs = notificationPrefs.getPrefs().first()
        val now = time.now()
        val zone = time.zone()
        // A snooze rings once: one whose time has passed has been shown.
        val snoozes = snoozeStore.all()
        snoozes.filter { it.triggerAtMillis <= now.toEpochMilliseconds() }.forEach { snoozeStore.remove(it.taskId) }
        val routinesOn = routinePrefs.remindersEnabled.first() && routinePrefs.enabled.first()
        val behavior = behaviorPrefs.getPrefs().first()
        val routineAlarms = if (routinesOn) {
            RoutineAlarmPlanner.plan(routineRepository.observeReminderRoutines().first(), now, zone, LocalNotificationPlanner.ROUTINE_HORIZON_DAYS)
        } else {
            emptyList()
        }
        return LocalNotificationPlanner.plan(
            LocalNotificationPlanner.Input(
                now = now,
                zone = zone,
                prefs = prefs,
                tasksWithReminders = taskDao.getAllWithReminders().map { with(taskMapper) { it.toDomain() } },
                snoozes = snoozes,
                routineAlarms = routineAlarms,
                summaryFor = { day ->
                    summaryReader.read(
                        day,
                        includeOverdue = prefs.notifyOverdueEnabled,
                        includeDueToday = prefs.notifyDueTodayEnabled,
                        includeTomorrow = prefs.notifyUpcomingEnabled,
                    )
                },
                plannerNudge = PlannerNudge.timeOf(behavior.plannerEnabled, behavior.plannerNudge),
            ),
        )
    }

    private suspend fun apply(wanted: List<LocalNotification>) {
        val pending = pendingRequests().filter { isOurs(it.identifier) }
        val pendingVersions = pending.associate { it.identifier to it.content.userInfo[KEY_VERSION] as? String }
        val wantedIds = wanted.mapTo(HashSet()) { it.id }
        val gone = pending.map { it.identifier }.filter { it !in wantedIds }
        if (gone.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(gone)
        // A request with the same identifier replaces the pending one, so only changes are added.
        for (notification in wanted) {
            val version = version(notification)
            if (pendingVersions[notification.id] == version) continue
            add(request(notification, version))
        }
    }

    /** Delivered reminders of tasks that are done or gone, and routine reminders already logged. */
    private suspend fun removeStaleDelivered() {
        val stale = deliveredNotifications().filter { delivered ->
            val info = delivered.request.content.userInfo
            when (info[LocalNotification.KEY_KIND] as? String) {
                LocalNotification.Kind.REMINDER.name, LocalNotification.Kind.SNOOZE.name, IosLocationNotifier.KIND -> {
                    val taskId = (info[LocalNotification.KEY_TASK_ID] as? String)?.toLongOrNull() ?: return@filter false
                    taskDao.getByIdSync(taskId)?.done ?: true
                }
                LocalNotification.Kind.ROUTINE.name -> {
                    val date = info[LocalNotification.KEY_DATE] as? String ?: return@filter false
                    val key = info[LocalNotification.KEY_OCCURRENCE_KEY] as? String ?: return@filter false
                    routineRepository.observeDay(date).first().occurrences.firstOrNull { it.key == key }?.status?.isFinished == true
                }
                else -> false
            }
        }
        if (stale.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(stale.map { it.request.identifier })
    }

    private fun request(notification: LocalNotification, version: String): UNNotificationRequest {
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            if (notification.subtitle.isNotEmpty()) setSubtitle(notification.subtitle)
            setBody(notification.body)
            setSound(if (notification.sound) UNNotificationSound.defaultSound else null)
            when (notification.kind) {
                LocalNotification.Kind.REMINDER, LocalNotification.Kind.SNOOZE -> {
                    setCategoryIdentifier(IosNotificationIds.CATEGORY_TASK)
                    setThreadIdentifier("reminders")
                }
                LocalNotification.Kind.ROUTINE -> {
                    setCategoryIdentifier(IosNotificationIds.CATEGORY_ROUTINE)
                    setThreadIdentifier("routines")
                }
                LocalNotification.Kind.SUMMARY -> setThreadIdentifier("summary")
                LocalNotification.Kind.PLANNER_NUDGE -> setThreadIdentifier("planner")
            }
            setUserInfo(notification.data + (KEY_VERSION to version))
        }
        val components = when (val trigger = notification.trigger) {
            // An instant: the components are read in UTC, so a change of zone does not move it.
            is LocalNotification.Trigger.Absolute -> components(trigger.at.toLocalDateTime(TimeZone.UTC), NSTimeZone.timeZoneWithName("UTC"))
            // A local time: no zone, so it rings at that wall-clock time wherever the device is.
            is LocalNotification.Trigger.Local -> components(trigger.dateTime, null)
        }
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        return UNNotificationRequest.requestWithIdentifier(notification.id, content, trigger)
    }

    private fun components(dateTime: LocalDateTime, zone: NSTimeZone?) = NSDateComponents().apply {
        setYear(dateTime.year.toLong())
        setMonth(dateTime.month.ordinal.toLong() + 1)
        setDay(dateTime.day.toLong())
        setHour(dateTime.hour.toLong())
        setMinute(dateTime.minute.toLong())
        setSecond(dateTime.second.toLong())
        if (zone != null) setTimeZone(zone)
    }

    /** Changes when anything the user would see or hear changes, so only those are replaced. */
    private fun version(notification: LocalNotification): String = listOf(
        notification.title,
        notification.subtitle,
        notification.body,
        notification.sound.toString(),
        notification.trigger.toString(),
        notification.data.entries.sortedBy { it.key }.joinToString(),
    ).joinToString("\u001f").hashCode().toString()

    private fun isOurs(identifier: String) = OUR_PREFIXES.any { identifier.startsWith(it) }

    private suspend fun pendingRequests(): List<UNNotificationRequest> = suspendCancellableCoroutine { continuation ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            continuation.resume(requests.orEmpty().filterIsInstance<UNNotificationRequest>())
        }
    }

    private suspend fun deliveredNotifications(): List<UNNotification> = suspendCancellableCoroutine { continuation ->
        center.getDeliveredNotificationsWithCompletionHandler { notifications ->
            continuation.resume(notifications.orEmpty().filterIsInstance<UNNotification>())
        }
    }

    private suspend fun add(request: UNNotificationRequest) = suspendCancellableCoroutine { continuation ->
        center.addNotificationRequest(request) { error ->
            if (error != null) Logger.w(TAG, "Could not schedule ${request.identifier}: ${error.localizedDescription}")
            continuation.resume(Unit)
        }
    }

    private companion object {
        const val TAG = "IosNotifications"
        const val KEY_VERSION = "v"
        const val TEST_ID = "test"
        val DEBOUNCE = 750.milliseconds
        val OUR_PREFIXES = listOf("reminder:", "snooze:", "routine:", "summary:", "plan-nudge:")
    }
}
