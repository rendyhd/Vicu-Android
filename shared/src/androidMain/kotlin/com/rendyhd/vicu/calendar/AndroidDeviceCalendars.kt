package com.rendyhd.vicu.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import com.rendyhd.vicu.util.CalendarEvent
import kotlin.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * The phone's calendars through Android's calendar provider (contract 20.9): whatever the phone
 * syncs (Google, Exchange through Outlook, CalDAV through DAVx5, links through ICSx5). Read only:
 * READ_CALENDAR and never WRITE_CALENDAR. `Instances` expands recurrence and exceptions itself.
 */
class AndroidDeviceCalendars(private val context: Context) : DeviceCalendars {
    override val supported: Boolean = true

    override fun hasAccess(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun calendars(): List<DeviceCalendar> {
        if (!hasAccess()) return emptyList()
        return withContext(Dispatchers.IO) {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
            )
            val found = mutableListOf<DeviceCalendar>()
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    found += DeviceCalendar(
                        id = id.toString(),
                        name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Calendar",
                        account = cursor.getString(2).orEmpty(),
                        color = if (cursor.isNull(3)) null else hex(cursor.getInt(3)),
                    )
                }
            }
            found
        }
    }

    override suspend fun events(calendarIds: Set<String>, from: Instant, to: Instant, zone: TimeZone): List<CalendarEvent> {
        if (!hasAccess() || calendarIds.isEmpty()) return emptyList()
        val ids = calendarIds.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.CALENDAR_ACCESS_LEVEL,
            )
            // A day either side: all-day instances are kept in UTC, so a zone far from it needs the room.
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from.toEpochMilliseconds() - DAY_MS)
                ContentUris.appendId(it, to.toEpochMilliseconds() + DAY_MS)
            }.build()
            val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }})"
            val args = ids.map { it.toString() }.toTypedArray()
            val events = mutableListOf<CalendarEvent>()
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val status = if (cursor.isNull(8)) null else cursor.getInt(8)
                    val self = if (cursor.isNull(9)) null else cursor.getInt(9)
                    // Cancelled events are left out (20.9), and so is a meeting you declined. Not answered
                    // yet (invited) or tentative is tentative, as is a tentative availability.
                    if (status == CalendarContract.Events.STATUS_CANCELED) continue
                    if (self == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = cursor.getInt(6) != 0
                    val begin = cursor.getLong(2)
                    val end = cursor.getLong(3)
                    val (start, finish) = if (allDay) {
                        // Stored as UTC midnights: the same dates from local midnight to local midnight.
                        localMidnight(begin, zone) to localMidnight(end, zone)
                    } else {
                        Instant.fromEpochMilliseconds(begin) to Instant.fromEpochMilliseconds(end)
                    }
                    if (finish <= from || start >= to) continue
                    val calendarId = cursor.getLong(1).toString()
                    val availability = if (cursor.isNull(7)) null else cursor.getInt(7)
                    val busy = availability != CalendarContract.Events.AVAILABILITY_FREE
                    // A calendar shared as free/busy only gives no titles: "Busy" or "Free" (20.8).
                    val freeBusyOnly = !cursor.isNull(10) &&
                        cursor.getInt(10) == CalendarContract.Calendars.CAL_ACCESS_FREEBUSY
                    events += CalendarEvent(
                        id = "$calendarId:${cursor.getLong(0)}:$begin",
                        calendarId = calendarId,
                        title = when {
                            freeBusyOnly -> if (busy) "Busy" else "Free"
                            else -> cursor.getString(4)?.trim()?.takeIf { it.isNotEmpty() } ?: "(No title)"
                        },
                        location = cursor.getString(5)?.trim()?.takeIf { it.isNotEmpty() && !freeBusyOnly },
                        start = start,
                        end = finish,
                        allDay = allDay,
                        busy = busy,
                        tentative = status == CalendarContract.Events.STATUS_TENTATIVE ||
                            availability == CalendarContract.Events.AVAILABILITY_TENTATIVE ||
                            self == CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE ||
                            self == CalendarContract.Attendees.ATTENDEE_STATUS_INVITED,
                    )
                }
            }
            events
        }
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        }.isSuccess
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun localMidnight(utcMillis: Long, zone: TimeZone): Instant {
        val date: LocalDate = Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date
        return date.atStartOfDayIn(zone)
    }

    private fun hex(color: Int): String = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0')

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
