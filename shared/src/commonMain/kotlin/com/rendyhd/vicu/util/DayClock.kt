package com.rendyhd.vicu.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Where "now" and the time zone come from; tests replace it with a fake. */
interface TimeSource {
    fun now(): Instant
    fun zone(): TimeZone
}

object SystemTimeSource : TimeSource {
    override fun now(): Instant = Clock.System.now()
    override fun zone(): TimeZone = TimeZone.currentSystemDefault()
}

/**
 * The local calendar day and the zone it was read in; a zone change can move the day boundary.
 * [weekStart] is the day the account's weeks start on (contract section 2.1), so everything that
 * reads the day reads the same week with it.
 */
data class ClockDay(val date: LocalDate, val zone: TimeZone, val weekStart: WeekStart = WeekStart.DEFAULT)

/**
 * The current local calendar day as a flow, so everything that depends on "today" can follow it
 * instead of freezing the date it saw when it was created.
 *
 * The day is re-read at the next local midnight and whenever the host calls [refresh]: when the
 * app comes back to the foreground, and when the system reports a date, time or time zone
 * change. The timer alone is not enough, because a coroutine delay does not advance while the
 * device is in deep sleep and can fire hours late.
 *
 * The day also carries the account's week start once the app calls [follow] at start: a change of
 * the setting is a new [ClockDay], so lists and calendars that collect the day redo their weeks.
 */
class DayClock(
    private val scope: CoroutineScope,
    private val time: TimeSource = SystemTimeSource,
    ticking: Boolean = true,
) {
    private var weekStart: WeekStart = WeekStart.DEFAULT
    private val _day = MutableStateFlow(read())
    val day: StateFlow<ClockDay> = _day.asStateFlow()

    /** Emits the local date now and each time it changes (not for zone changes within a day). */
    val today: Flow<LocalDate> get() = day.map { it.date }.distinctUntilChanged()

    private var timer: Job? = null

    init {
        if (ticking) arm()
    }

    /** The local time of day now, in minutes after midnight (for what is due soon). */
    fun minutesNow(): Int = time.now().toLocalDateTime(time.zone()).let { it.hour * 60 + it.minute }

    /** Keeps the day's week start on what [weekStarts] gives (the synced setting), for as long as the clock lives. */
    fun follow(weekStarts: Flow<WeekStart>) {
        scope.launch { weekStarts.collect { setWeekStart(it) } }
    }

    /** The week start every later [ClockDay] carries; the day is emitted again when it differs. */
    fun setWeekStart(value: WeekStart) {
        weekStart = value
        _day.update { it.copy(weekStart = value) }
    }

    /** Re-reads the day now and restarts the midnight timer from the current time. */
    fun refresh() {
        _day.value = read()
        if (timer != null) arm()
    }

    private fun read(): ClockDay {
        val zone = time.zone()
        return ClockDay(time.now().toLocalDateTime(zone).date, zone, weekStart)
    }

    private fun arm() {
        timer?.cancel()
        timer = scope.launch {
            while (true) {
                delay(DateUtils.millisUntilNextMidnight(time.now(), time.zone()) + SETTLE_MILLIS)
                _day.value = read()
            }
        }
    }

    private companion object {
        /** Wait a little past midnight so the new day is certain to have started. */
        const val SETTLE_MILLIS = 1_000L
    }
}
