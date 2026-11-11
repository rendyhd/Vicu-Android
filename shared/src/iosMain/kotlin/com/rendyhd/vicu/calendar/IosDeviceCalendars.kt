package com.rendyhd.vicu.calendar

import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.ioDispatcher
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import platform.CoreGraphics.CGColorGetComponents
import platform.CoreGraphics.CGColorGetNumberOfComponents
import platform.CoreGraphics.CGColorRef
import platform.EventKit.EKAuthorizationStatus
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKAuthorizationStatusWriteOnly
import platform.EventKit.EKCalendar
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventAvailabilityFree
import platform.EventKit.EKEventAvailabilityTentative
import platform.EventKit.EKEventStatusCanceled
import platform.EventKit.EKEventStatusTentative
import platform.EventKit.EKEventStore
import platform.EventKit.EKEventStoreChangedNotification
import platform.EventKit.EKParticipant
import platform.EventKit.EKParticipantStatus
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The app's one EventKit store, made on first use and never while Koin builds a definition
 * (gotcha 19: the Koin graph test runs without an app bundle). Full access, for reading only; Vicu
 * never asks for write access (contract 20.9).
 */
internal object IosEventStore {
    val store: EKEventStore by lazy { EKEventStore() }

    fun status(): EKAuthorizationStatus = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)

    fun hasFullAccess(): Boolean = status() == EKAuthorizationStatusFullAccess

    /** Refused, limited by the device's owner, or write only: the system will not ask again. */
    fun isRefused(): Boolean = status().let {
        it == EKAuthorizationStatusDenied || it == EKAuthorizationStatusRestricted || it == EKAuthorizationStatusWriteOnly
    }

    /** Asks for full access (iOS 17); [onResult] runs on the main thread. */
    fun requestFullAccess(onResult: (Boolean) -> Unit) {
        store.requestFullAccessToEventsWithCompletion { granted, _ ->
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }
}

/**
 * The iPhone's calendars through EventKit (contract 20.9): whatever the phone has (iCloud, Google,
 * Exchange, CalDAV, subscriptions). Read only. `eventsMatchingPredicate` expands recurrence and
 * exceptions itself; cancelled events and meetings you declined are left out, as on Android.
 */
class IosDeviceCalendars : DeviceCalendars {
    override val supported: Boolean = true

    override fun hasAccess(): Boolean = IosEventStore.hasFullAccess()

    private fun eventCalendars(): List<EKCalendar> =
        IosEventStore.store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent).filterIsInstance<EKCalendar>()

    override suspend fun calendars(): List<DeviceCalendar> {
        if (!hasAccess()) return emptyList()
        return withContext(ioDispatcher) {
            eventCalendars().map { calendar ->
                DeviceCalendar(
                    id = calendar.calendarIdentifier,
                    name = calendar.title.takeIf { it.isNotBlank() } ?: "Calendar",
                    account = calendar.source?.title.orEmpty(),
                    color = hexOf(calendar.CGColor),
                )
            }
        }
    }

    override suspend fun events(calendarIds: Set<String>, from: Instant, to: Instant, zone: TimeZone): List<CalendarEvent> {
        if (!hasAccess() || calendarIds.isEmpty()) return emptyList()
        return withContext(ioDispatcher) {
            val calendars = eventCalendars().filter { it.calendarIdentifier in calendarIds }
            if (calendars.isEmpty()) return@withContext emptyList()
            val store = IosEventStore.store
            val predicate = store.predicateForEventsWithStartDate(nsDate(from), nsDate(to), calendars)
            store.eventsMatchingPredicate(predicate).filterIsInstance<EKEvent>().mapNotNull { event -> eventOf(event, zone, from, to) }
        }
    }

    private fun eventOf(event: EKEvent, zone: TimeZone, from: Instant, to: Instant): CalendarEvent? {
        val status = event.status
        if (status == EKEventStatusCanceled) return null
        val self = event.attendees?.filterIsInstance<EKParticipant>()?.firstOrNull { it.currentUser }?.participantStatus
        if (self == EKParticipantStatus.EKParticipantStatusDeclined) return null
        val begin = event.startDate?.let(::instantOf) ?: return null
        val finish = event.endDate?.let(::instantOf) ?: begin
        val allDay = event.allDay
        val (start, end) = if (allDay) {
            // All-day events are floating: from the first day's local midnight to the one after the last.
            val first = begin.toLocalDateTime(zone).date
            val endLocal = finish.toLocalDateTime(zone)
            val last = if (endLocal.time == LocalTime(0, 0) && endLocal.date > first) endLocal.date.minus(1, DateTimeUnit.DAY) else endLocal.date
            first.atStartOfDayIn(zone) to maxOf(first, last).plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        } else {
            begin to maxOf(begin, finish)
        }
        if (end <= from || start >= to) return null
        val calendarId = event.calendar?.calendarIdentifier ?: return null
        return CalendarEvent(
            id = "$calendarId:${event.eventIdentifier.orEmpty()}:${begin.toEpochMilliseconds()}",
            calendarId = calendarId,
            title = event.title?.trim()?.takeIf { it.isNotEmpty() } ?: "(No title)",
            location = event.location?.trim()?.takeIf { it.isNotEmpty() },
            start = start,
            end = end,
            allDay = allDay,
            busy = event.availability != EKEventAvailabilityFree,
            // The event or its availability tentative, or your own answer tentative or not given yet (20.9).
            tentative = status == EKEventStatusTentative ||
                event.availability == EKEventAvailabilityTentative ||
                self == EKParticipantStatus.EKParticipantStatusTentative ||
                self == EKParticipantStatus.EKParticipantStatusPending,
        )
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = EKEventStoreChangedNotification,
            `object` = IosEventStore.store,
            queue = null,
        ) { _ -> trySend(Unit) }
        awaitClose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }.conflate()

    override fun refreshSources() {
        if (hasAccess()) IosEventStore.store.refreshSourcesIfNecessary()
    }

    private fun nsDate(at: Instant): NSDate = NSDate.dateWithTimeIntervalSince1970(at.toEpochMilliseconds() / 1000.0)

    private fun instantOf(date: NSDate): Instant = Instant.fromEpochMilliseconds(round(date.timeIntervalSince1970 * 1000.0).toLong())

    @OptIn(ExperimentalForeignApi::class)
    private fun hexOf(color: CGColorRef?): String? {
        if (color == null) return null
        val count = CGColorGetNumberOfComponents(color).toInt()
        val parts = CGColorGetComponents(color) ?: return null
        val rgb = when {
            count >= 3 -> listOf(parts[0], parts[1], parts[2])
            count >= 1 -> listOf(parts[0], parts[0], parts[0])
            else -> return null
        }
        return "#" + rgb.joinToString("") { (it.coerceIn(0.0, 1.0) * 255).roundToInt().toString(16).padStart(2, '0') }
    }
}
