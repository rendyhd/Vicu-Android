package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.WeekStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The day the account's weeks start on (docs/cross-app-semantics-v1.md, sections 2.1 and 11.3), a
 * synced setting: chosen once, it is the same on every device. Monday is the default and is written
 * as null, so going back to it reaches the other devices as a reset.
 *
 * The app does not read it from here to compute a week: [com.rendyhd.vicu.util.DayClock] follows
 * [weekStart] from app start and every [com.rendyhd.vicu.util.ClockDay] carries it. Code that runs
 * before the clock can have caught up (a widget drawn by a cold process) asks [current].
 */
class WeekStartPreferences(private val synced: SyncedEntries) {

    val weekStart: Flow<WeekStart> = synced.entries
        .map { WeekStart.read(it[SyncedPrefKeys.WEEK_START]) }
        .distinctUntilChanged()

    /** The week start as the device's copy of the preferences has it now. */
    suspend fun current(): WeekStart = weekStart.first()

    suspend fun set(weekStart: WeekStart) {
        synced.set(SyncedPrefKeys.WEEK_START, WeekStart.write(weekStart))
    }
}
