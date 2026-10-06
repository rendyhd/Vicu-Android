package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrence
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

object RoutineScheduleEngine {
    fun occurrenceKey(routineId: String, date: String, slotId: String): String =
        "$routineId:$date:$slotId"

    fun isScheduledOn(
        schedule: RoutineSchedule,
        date: LocalDate,
        latestCompletionDate: LocalDate? = null,
    ): Boolean = when (schedule) {
        is RoutineSchedule.Calendar -> {
            val anchor = LocalDate.parse(schedule.anchorDate)
            if (date < anchor) {
                false
            } else {
                val weekdayMatches = schedule.weekdays.isEmpty() || date.dayOfWeek.isoDayNumber in schedule.weekdays
                val interval = schedule.weekInterval.coerceAtLeast(1)
                val anchorWeekStart = anchor.minus(anchor.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
                val dateWeekStart = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
                val weeksSinceAnchor = anchorWeekStart.daysUntil(dateWeekStart) / 7
                weekdayMatches && weeksSinceAnchor % interval == 0
            }
        }
        is RoutineSchedule.AfterCompletion -> {
            val due = latestCompletionDate
                ?.plus(schedule.intervalDays.coerceAtLeast(1), DateTimeUnit.DAY)
                ?: LocalDate.parse(schedule.firstDueDate)
            date == due
        }
    }

    // --- After-completion schedules (docs/cross-app-semantics-v1.md, section 6.5) ------------

    /** A completed occurrence and the local date it was completed on. */
    data class Completion(val record: RoutineOccurrenceRecord, val date: LocalDate)

    /**
     * The completion date of a `COMPLETED` occurrence: the local date of its `loggedAt` in the
     * occurrence's own time zone ([deviceZone] when that is missing or unknown), or its
     * `scheduledDate` when there is no usable `loggedAt`. Null only when neither is a date.
     */
    fun completionDate(record: RoutineOccurrenceRecord, deviceZone: TimeZone): LocalDate? {
        val logged = RoutineTime.parse(record.loggedAt)
        if (logged != null) {
            val zone = zoneOrNull(record.timeZoneId) ?: deviceZone
            return logged.toLocalDateTime(zone).date
        }
        return runCatching { LocalDate.parse(record.scheduledDate) }.getOrNull()
    }

    /**
     * The `COMPLETED` occurrence with the largest completion date. Among occurrences completed on
     * the same date the one scheduled earliest is chosen, so the answer never depends on map
     * order.
     */
    fun latestCompletion(
        occurrences: Collection<RoutineOccurrenceRecord>,
        deviceZone: TimeZone,
    ): Completion? {
        var best: Completion? = null
        for (record in occurrences) {
            if (record.status != OccurrenceStatus.COMPLETED) continue
            val date = completionDate(record, deviceZone) ?: continue
            val current = best
            if (current == null ||
                date > current.date ||
                (date == current.date && record.scheduledDate < current.record.scheduledDate)
            ) {
                best = Completion(record, date)
            }
        }
        return best
    }

    /** The largest completion date over all `COMPLETED` occurrences, or null when none. */
    fun latestCompletionDate(
        occurrences: Collection<RoutineOccurrenceRecord>,
        deviceZone: TimeZone,
    ): LocalDate? = latestCompletion(occurrences, deviceZone)?.date

    /**
     * The next due date of an after-completion routine: the latest completion date plus
     * `intervalDays`, or `firstDueDate` before the first completion. Null for a calendar routine.
     */
    fun dueDate(payload: RoutinePayload, deviceZone: TimeZone): LocalDate? {
        val schedule = payload.definition.schedule as? RoutineSchedule.AfterCompletion ?: return null
        return latestCompletionDate(payload.occurrences.values, deviceZone)
            ?.plus(schedule.intervalDays.coerceAtLeast(1), DateTimeUnit.DAY)
            ?: LocalDate.parse(schedule.firstDueDate)
    }

    private fun zoneOrNull(id: String): TimeZone? {
        if (id.isBlank()) return null
        return try {
            TimeZone.of(id)
        } catch (_: Exception) {
            null
        }
    }

    fun occurrencesForDate(
        routine: Routine,
        date: LocalDate,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        today: LocalDate = date,
    ): List<RoutineOccurrence> {
        val definition = routine.definition
        if (definition.archived || date < LocalDate.parse(definition.activeFrom)) return emptyList()
        val effectiveDate = when (val schedule = definition.schedule) {
            is RoutineSchedule.Calendar -> {
                if (!isScheduledOn(schedule, date)) return emptyList()
                date
            }
            is RoutineSchedule.AfterCompletion -> {
                val due = dueDate(routine.payload, timeZone) ?: return emptyList()
                // Keep a completion-based chore visible once it is due, but only on the
                // current day; future schedule scans still get exactly one occurrence.
                if (date != due && !(date == today && due < today)) return emptyList()
                due
            }
        }

        return definition.slots.map { slot ->
            val key = occurrenceKey(definition.id, effectiveDate.toString(), slot.id)
            val record = routine.payload.occurrences[key]
            val status = record?.status ?: OccurrenceStatus.PENDING
            RoutineOccurrence(
                routine = routine,
                key = key,
                slot = slot,
                scheduledDate = effectiveDate.toString(),
                status = if (
                    status == OccurrenceStatus.PENDING &&
                    definition.kind == RoutineKind.HEALTH &&
                    effectiveDate < today
                ) {
                    OccurrenceStatus.NOT_LOGGED
                } else {
                    status
                },
                loggedAt = record?.loggedAt.orEmpty(),
                note = record?.note.orEmpty(),
                overdue = definition.kind == RoutineKind.CHORE &&
                    status == OccurrenceStatus.PENDING && effectiveDate < today,
            )
        }
    }

    fun recordFor(
        routine: Routine,
        date: LocalDate,
        slot: RoutineSlot,
        status: OccurrenceStatus,
        nowIso: String,
        deviceId: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        note: String = "",
    ): RoutineOccurrenceRecord {
        val key = occurrenceKey(routine.definition.id, date.toString(), slot.id)
        return RoutineOccurrenceRecord(
            key = key,
            routineId = routine.definition.id,
            slotId = slot.id,
            scheduledDate = date.toString(),
            scheduledMinutes = slot.reminderMinutes,
            timeZoneId = timeZone.id,
            status = status,
            loggedAt = if (status == OccurrenceStatus.COMPLETED) nowIso else "",
            modifiedAt = nowIso,
            modifiedBy = deviceId,
            note = note,
        )
    }

    // --- Merge (section 6.3) ------------------------------------------------------------------

    /**
     * The newer of two records for the same key: the later parsed `modifiedAt`, then the larger
     * `modifiedBy`. On an exact tie [left] stays.
     */
    fun newerOccurrence(left: RoutineOccurrenceRecord, right: RoutineOccurrenceRecord): RoutineOccurrenceRecord =
        if (RoutineTime.compareWrites(left.modifiedAt, left.modifiedBy, right.modifiedAt, right.modifiedBy) >= 0) {
            left
        } else {
            right
        }

    /** Union of keys, last-write-wins per key. Earlier maps win exact ties. */
    fun mergeOccurrenceMaps(
        vararg maps: Map<String, RoutineOccurrenceRecord>,
    ): Map<String, RoutineOccurrenceRecord> {
        val merged = LinkedHashMap<String, RoutineOccurrenceRecord>()
        for (map in maps) {
            for ((key, record) in map) {
                val existing = merged[key]
                merged[key] = if (existing == null) record else newerOccurrence(existing, record)
            }
        }
        return merged
    }

    /** Drops every occurrence scheduled before [prunedBefore] (they live in the archive). */
    fun dropPruned(
        occurrences: Map<String, RoutineOccurrenceRecord>,
        prunedBefore: String,
    ): Map<String, RoutineOccurrenceRecord> {
        if (prunedBefore.isEmpty()) return occurrences
        return occurrences.filterValues { it.scheduledDate >= prunedBefore }
    }

    /**
     * `prunedBefore` is the later of the two, the definition is last-write-wins by the parsed
     * `updatedAt` (ties by `updatedBy`), occurrences are the last-write-wins union of both sides
     * minus what is older than `prunedBefore`. [local] wins exact ties.
     */
    fun merge(local: RoutinePayloadMergeInput, remote: RoutinePayloadMergeInput): RoutinePayloadMergeInput {
        val definition = if (
            RoutineTime.compareWrites(
                local.definitionUpdatedAt,
                local.definitionUpdatedBy,
                remote.definitionUpdatedAt,
                remote.definitionUpdatedBy,
            ) >= 0
        ) local.definition else remote.definition
        val prunedBefore = maxOf(local.prunedBefore, remote.prunedBefore)
        val occurrences = dropPruned(mergeOccurrenceMaps(local.occurrences, remote.occurrences), prunedBefore)
        return RoutinePayloadMergeInput(definition, occurrences, prunedBefore)
    }
}

data class RoutinePayloadMergeInput(
    val definition: com.rendyhd.vicu.domain.model.RoutineDefinition,
    val occurrences: Map<String, RoutineOccurrenceRecord>,
    val prunedBefore: String = "",
) {
    val definitionUpdatedAt: String get() = definition.updatedAt
    val definitionUpdatedBy: String get() = definition.updatedBy
}

private val kotlinx.datetime.DayOfWeek.isoDayNumber: Int
    get() = ordinal + 1
