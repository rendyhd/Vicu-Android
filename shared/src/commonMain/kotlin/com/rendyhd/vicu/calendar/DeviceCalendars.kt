package com.rendyhd.vicu.calendar

import com.rendyhd.vicu.util.CalendarEvent
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.TimeZone

/** A calendar the device keeps (Android's calendar provider; EventKit on iOS later). */
data class DeviceCalendar(
    /** Its id on this device: never the same on another one, so it never syncs (contract 20.9). */
    val id: String,
    val name: String,
    /** The account it belongs to, for the list's groups. */
    val account: String,
    /** The calendar's own colour, `#rrggbb`, or null. */
    val color: String?,
)

/**
 * The device's own calendars, read only (contract 20.9): Vicu never adds, changes or deletes an
 * event and never asks for write access. Android reads `CalendarContract.Instances`, which expands
 * recurrence; iOS has no reader yet and answers [supported] false.
 */
interface DeviceCalendars {
    /** Whether this platform reads its calendars at all. */
    val supported: Boolean

    /** Whether the app may read them now (Android: READ_CALENDAR). */
    fun hasAccess(): Boolean

    /** Every calendar on the device; empty without access. */
    suspend fun calendars(): List<DeviceCalendar>

    /**
     * The occurrences of [calendarIds] that touch [from] to [to], recurrence expanded, cancelled ones
     * left out. All-day events run from local midnight in [zone] to the midnight after their last day.
     * Titles are as the calendar has them: Busy only is applied by [PlanCalendars], before anything
     * else sees them.
     */
    suspend fun events(calendarIds: Set<String>, from: Instant, to: Instant, zone: TimeZone): List<CalendarEvent>

    /** Says when the device's calendars changed. Collected only while the Plan screen is shown. */
    fun changes(): Flow<Unit>
}

/** A platform without a calendar reader (iOS for now, and tests). */
object NoDeviceCalendars : DeviceCalendars {
    override val supported: Boolean = false
    override fun hasAccess(): Boolean = false
    override suspend fun calendars(): List<DeviceCalendar> = emptyList()
    override suspend fun events(calendarIds: Set<String>, from: Instant, to: Instant, zone: TimeZone): List<CalendarEvent> = emptyList()
    override fun changes(): Flow<Unit> = emptyFlow()
}
