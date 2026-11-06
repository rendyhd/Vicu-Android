package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.TimeBlocks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The planner's two synced settings (docs/cross-app-semantics-v1.md, sections 11.3 and 16): what a
 * new block takes and the work hours. Choosing the default writes null, so the reset reaches the
 * other devices; a value outside the registry is never written.
 */
class PlannerPreferences(private val synced: SyncedEntries) {

    val defaultLength: Flow<Int> = synced.entries
        .map { TimeBlocks.readDefaultLength(it[SyncedPrefKeys.PLANNER_DEFAULT_LENGTH]) }
        .distinctUntilChanged()

    val hours: Flow<PlannerHours> = synced.entries
        .map { TimeBlocks.readHours(it[SyncedPrefKeys.PLANNER_HOURS]) }
        .distinctUntilChanged()

    /** Sets the default length; false when [minutes] is not one the registry allows. */
    suspend fun setDefaultLength(minutes: Int): Boolean {
        if (!TimeBlocks.isWritableLength(minutes)) return false
        val value = minutes.takeUnless { it == TimeBlocks.DEFAULT_LENGTH_MINUTES }?.let { JsonPrimitive(it) }
        synced.set(SyncedPrefKeys.PLANNER_DEFAULT_LENGTH, value)
        return true
    }

    /** Sets the work hours; false when the start is not before the end. */
    suspend fun setHours(hours: PlannerHours): Boolean {
        if (!TimeBlocks.isWritableHours(hours)) return false
        val value = hours.takeUnless { it == TimeBlocks.DEFAULT_HOURS }
            ?.let { JsonArray(listOf(JsonPrimitive(it.start), JsonPrimitive(it.end))) }
        synced.set(SyncedPrefKeys.PLANNER_HOURS, value)
        return true
    }
}
