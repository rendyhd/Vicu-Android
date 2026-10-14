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
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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

/** The local calendar day and the zone it was read in; a zone change can move the day boundary. */
data class ClockDay(val date: LocalDate, val zone: TimeZone)

/**
 * The current local calendar day as a flow, so everything that depends on "today" can follow it
 * instead of freezing the date it saw when it was created.
 *
 * The day is re-read at the next local midnight and whenever the host calls [refresh]: when the
 * app comes back to the foreground, and when the system reports a date, time or time zone
 * change. The timer alone is not enough, because a coroutine delay does not advance while the
 * device is in deep sleep and can fire hours late.
 */
class DayClock(
    private val scope: CoroutineScope,
    private val time: TimeSource = SystemTimeSource,
    ticking: Boolean = true,
) {
    private val _day = MutableStateFlow(read())
    val day: StateFlow<ClockDay> = _day.asStateFlow()

    /** Emits the local date now and each time it changes (not for zone changes within a day). */
    val today: Flow<LocalDate> get() = day.map { it.date }.distinctUntilChanged()

    private var timer: Job? = null

    init {
        if (ticking) arm()
    }

    /** Re-reads the day now and restarts the midnight timer from the current time. */
    fun refresh() {
        _day.value = read()
        if (timer != null) arm()
    }

    private fun read(): ClockDay {
        val zone = time.zone()
        return ClockDay(time.now().toLocalDateTime(zone).date, zone)
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
