package com.rendyhd.vicu.data.local.entity

import androidx.room.Entity

/**
 * Routine history that versions before 1.9 moved out of the routine and kept only on this phone.
 * Routine history now lives in archive parts on the server (docs/cross-app-semantics-v1.md,
 * section 6.4), so this table is only a queue: its rows are uploaded once, deleted after the
 * server has them, and nothing is written here any more. A later release can drop the table.
 */
@Entity(
    tableName = "routine_occurrence_archive",
    primaryKeys = ["routineId", "occurrenceKey"],
)
data class RoutineOccurrenceArchiveEntity(
    val routineId: String,
    val occurrenceKey: String,
    val slotId: String,
    val scheduledDate: String,
    val scheduledMinutes: Int,
    val timeZoneId: String,
    val status: String,
    val loggedAt: String,
    val modifiedAt: String,
    val modifiedBy: String,
    val note: String,
)
