package com.rendyhd.vicu.calendar

import com.rendyhd.vicu.data.repository.SyncedEntries
import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.CalendarEvents
import com.rendyhd.vicu.util.ListedEvent
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.PlanEvent
import com.rendyhd.vicu.util.PlanEvents
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.randomUuid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonElement

/** A phone calendar with this device's choices applied. */
data class PlanCalendar(
    val id: String,
    val name: String,
    val account: String,
    /** The colour it is drawn in: the one chosen, else its own, else the palette's. */
    val color: String,
    /** The calendar's own colour, for the list's "Calendar colour" choice. */
    val ownColor: String?,
    val choice: CalendarChoice,
)

/** What the Plan screen draws and counts for the days asked for (contract 20). */
data class PlanCalendarDays(
    /** Your own Events' occurrences and the shown phone calendars' events, Busy only already applied. */
    val events: List<CalendarEvent> = emptyList(),
    /** Your own Events as stored, by title. */
    val ownEvents: List<ListedEvent> = emptyList(),
    /** The phone calendars that are shown, by id. */
    val calendars: Map<String, PlanCalendar> = emptyMap(),
    /** When the phone's calendars were last read, and why the last read failed. */
    val readAt: Instant? = null,
    val readError: String? = null,
) {
    /** Calendars that count as busy (20.2): shown ones with Counts as busy; your own Events always. */
    val counting: Set<String> get() = calendars.values.filter { it.choice.shown && it.choice.countsAsBusy }.mapTo(HashSet()) { it.id }

    fun colorOf(calendarId: String): String =
        if (calendarId == PlanEvents.MY_EVENTS_CALENDAR_ID) PlanEvents.MY_EVENTS_COLOR else calendars[calendarId]?.color ?: PlanEvents.MY_EVENTS_COLOR

    fun nameOf(calendarId: String): String =
        if (calendarId == PlanEvents.MY_EVENTS_CALENDAR_ID) "Your events" else calendars[calendarId]?.name ?: "A calendar"

    fun ownEvent(id: String?): ListedEvent? = id?.let { ownEvents.firstOrNull { e -> e.id == it } }
}

/**
 * Everything the Plan screen shows that is not a task (contract 20): your own Events, synced as
 * `event.<id>` preferences, and the phone's calendars, read only, through [DeviceCalendars].
 *
 * Phone calendars are read when the screen starts collecting, again when the device says they
 * changed, on a manual refresh ([refreshNow]) and every five minutes while it is shown. A read that fails keeps the last good
 * copy and says why. Busy only (20.8) is applied here, before any screen, cache or log has the
 * title. Nothing about an event is written to a task, and nothing here is synced but your Events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanCalendars(
    private val synced: SyncedEntries,
    private val device: DeviceCalendars,
    private val prefs: CalendarPrefsStore,
    private val time: TimeSource = SystemTimeSource,
) {
    private val accessTick = MutableStateFlow(0)
    private val refreshTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Whether this platform can show phone calendars at all. */
    val supported: Boolean get() = device.supported

    /** Reads the permission again (after the system's question was answered). */
    fun accessChanged() {
        accessTick.value += 1
    }

    fun hasAccess(): Boolean = device.supported && device.hasAccess()

    /** A manual refresh (contract 20.9): the shown calendars are read again at once. */
    fun refreshNow() {
        refreshTick.tryEmit(Unit)
    }

    private val access: Flow<Boolean> = accessTick.map { hasAccess() }.distinctUntilChanged()

    /** Your own Events, by title then id. */
    val ownEvents: Flow<List<ListedEvent>> = synced.entries.map(PlanEvents::readAll).distinctUntilChanged()

    /** The `event.*` entries as stored, unknown fields and Events this version cannot read included. */
    suspend fun storedEvents(): Map<String, JsonElement?> =
        synced.entries.first().filterKeys { SyncedPrefKeys.eventId(it) != null }

    /** Writes Event entries in one change (one sync); null deletes one on every device. */
    suspend fun write(writes: Map<String, JsonElement?>) {
        synced.setAll(writes)
    }

    /** A new Event; its id. */
    suspend fun create(event: PlanEvent): String {
        val id = randomUuid()
        synced.set(SyncedPrefKeys.eventOf(id), PlanEvents.encode(event))
        return id
    }

    fun newId(): String = randomUuid()

    /** The phone's calendars with their choices, for Settings; empty without access. */
    val phoneCalendars: Flow<List<PlanCalendar>> = combine(access, prefs.choices) { ok, choices -> ok to choices }
        .flatMapLatest { (ok, choices) ->
            if (!ok) flowOf(emptyList()) else flow { emit(withChoices(readCalendars(), choices)) }
        }

    suspend fun setChoice(calendarId: String, change: (CalendarChoice) -> CalendarChoice) = prefs.update(calendarId, change)

    private suspend fun readCalendars(): List<DeviceCalendar> = try {
        device.calendars()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Could not list the calendars: ${e::class.simpleName}")
        emptyList()
    }

    private fun withChoices(calendars: List<DeviceCalendar>, choices: Map<String, CalendarChoice>): List<PlanCalendar> {
        // The palette goes round in the order calendars were added; ids grow as they are added.
        val ordered = calendars.sortedWith(compareBy<DeviceCalendar> { it.id.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.id })
        return ordered.mapIndexed { index, calendar ->
            val choice = choices[calendar.id] ?: CalendarChoice()
            PlanCalendar(
                id = calendar.id,
                name = calendar.name,
                account = calendar.account,
                color = choice.color ?: calendar.color ?: CalendarEvents.CALENDAR_COLORS[index % CalendarEvents.CALENDAR_COLORS.size],
                ownColor = calendar.color,
                choice = choice,
            )
        }
    }

    private data class PhoneRead(
        val calendars: List<PlanCalendar> = emptyList(),
        val events: List<CalendarEvent> = emptyList(),
        val readAt: Instant? = null,
        val error: String? = null,
    )

    /** The shown phone calendars' events from [from] to [to], kept fresh while collected. */
    private fun phone(from: LocalDate, to: LocalDate, zone: TimeZone): Flow<PhoneRead> =
        combine(access, prefs.choices) { ok, choices -> ok to choices }
            .flatMapLatest { (ok, choices) ->
                if (!ok) {
                    flowOf(PhoneRead())
                } else {
                    channelFlow {
                        val reading = Mutex()
                        var last = PhoneRead()
                        suspend fun read() = reading.withLock {
                            last = try {
                                val calendars = withChoices(device.calendars(), choices).filter { it.choice.shown }
                                val byId = calendars.associateBy { it.id }
                                val events = device.events(
                                    byId.keys,
                                    from.atStartOfDayIn(zone),
                                    to.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
                                    zone,
                                ).mapNotNull { event ->
                                    val calendar = byId[event.calendarId] ?: return@mapNotNull null
                                    // Busy only before anything else holds the title (20.8).
                                    if (calendar.choice.busyOnly) CalendarEvents.busyOnly(event) else event
                                }
                                PhoneRead(calendars, events, time.now(), null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.w(TAG, "Could not read the calendars: ${e::class.simpleName}")
                                // The last good copy stays, with why the read failed (20.9).
                                last.copy(error = "Could not read the phone's calendars")
                            }
                            send(last)
                        }
                        read()
                        launch { device.changes().collect { read() } }
                        launch {
                            refreshTick.collect {
                                runCatching { device.refreshSources() }
                                read()
                            }
                        }
                        while (true) {
                            delay(REREAD_EVERY)
                            read()
                        }
                    }
                }
            }

    /** Everything for the days from [from] to [to] (both included). */
    fun observe(from: LocalDate, to: LocalDate, zone: TimeZone): Flow<PlanCalendarDays> {
        val days = generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= to }.toList()
        return combine(ownEvents, phone(from, to, zone)) { own, read ->
            PlanCalendarDays(
                events = PlanEvents.occurrences(own, days, zone) + read.events,
                ownEvents = own,
                calendars = read.calendars.associateBy { it.id },
                readAt = read.readAt,
                readError = read.error,
            )
        }
    }

    private companion object {
        const val TAG = "PlanCalendars"
        val REREAD_EVERY = 5.minutes
    }
}
