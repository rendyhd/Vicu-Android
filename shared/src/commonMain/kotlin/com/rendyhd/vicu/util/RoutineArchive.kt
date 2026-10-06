package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutineSchedule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The rolling window, pruning and archive parts of a routine's history
 * (docs/cross-app-semantics-v1.md, sections 6.1, 6.2 and 6.4). Everything here is pure: the
 * callers pass today, the device zone and the JSON configuration, and do the server I/O
 * themselves, in the order the contract fixes: write the archive first, then the main carrier.
 */
object RoutineArchive {
    /** Title of every archive part task. */
    const val TITLE = "Vicu routine archive"

    /** Search text that finds archive parts on the server (the marker name inside the comment). */
    const val SEARCH = "vicu-routine:archive"

    /** A main carrier or an archive part is written at or below this many bytes of JSON (384 KiB). */
    const val BUDGET_BYTES = 384 * 1024

    /** Anything above this is rejected (512 KiB), so older clients can still read what we write. */
    const val HARD_LIMIT_BYTES = 512 * 1024

    /** The main carrier keeps occurrences from the last 400 days. */
    const val WINDOW_DAYS = 400

    /** The cutoff moves forward by this many days while the payload is over budget. */
    const val PRUNE_STEP_DAYS = 30

    // --- Sizes ----------------------------------------------------------------------------------

    /** Decoded JSON size of a main carrier payload, the figure the 384 KiB budget is about. */
    fun payloadBytes(payload: RoutinePayload, json: Json): Int =
        json.encodeToString(RoutinePayload.serializer(), payload).encodeToByteArray().size

    /** Decoded JSON size of an archive part. */
    fun partBytes(part: RoutineArchivePart, json: Json): Int =
        json.encodeToString(RoutineArchivePart.serializer(), part).encodeToByteArray().size

    // --- Pruning (section 6.2) ----------------------------------------------------------------

    data class PruneOutcome(
        /** The payload without the pruned occurrences and with the advanced `prunedBefore`. */
        val payload: RoutinePayload,
        /** The occurrences that left the payload and belong in the archive. */
        val archived: List<RoutineOccurrenceRecord>,
    )

    /**
     * Moves history older than the rolling window out of a payload: cutoff = today - 400 days,
     * never earlier than the existing `prunedBefore`; while the JSON is over [budgetBytes] the
     * cutoff moves forward in 30-day steps. It never passes today, and for an after-completion
     * routine never passes the latest completion (see [pruneCeiling]). `prunedBefore` only
     * advances when something moved.
     *
     * The caller writes `archived` to the archive first and the returned payload afterwards.
     */
    fun prune(
        payload: RoutinePayload,
        today: LocalDate,
        json: Json,
        deviceZone: TimeZone,
        budgetBytes: Int = BUDGET_BYTES,
    ): PruneOutcome {
        val existing = payload.prunedBefore.takeIf { isLocalDate(it) } ?: ""
        val todayText = today.toString()
        // Today's occurrences always stay, however far the shrinking has to go.
        val scheduleCeiling = pruneCeiling(payload, deviceZone)
        val ceiling = if (scheduleCeiling != null && scheduleCeiling < todayText) scheduleCeiling else todayText

        fun clamp(cutoff: String): String = maxOf(if (cutoff > ceiling) ceiling else cutoff, existing)

        var effective = clamp(today.minus(WINDOW_DAYS, DateTimeUnit.DAY).toString())
        while (true) {
            val kept = LinkedHashMap<String, RoutineOccurrenceRecord>()
            val archived = ArrayList<RoutineOccurrenceRecord>()
            for ((key, record) in payload.occurrences) {
                if (record.scheduledDate < effective) archived += record else kept[key] = record
            }
            val candidate = payload.copy(
                occurrences = kept,
                prunedBefore = if (archived.isNotEmpty()) effective else payload.prunedBefore,
            )
            if (kept.isEmpty() || payloadBytes(candidate, json) <= budgetBytes) {
                return PruneOutcome(candidate, archived)
            }
            val next = clamp(LocalDate.parse(effective).plus(PRUNE_STEP_DAYS, DateTimeUnit.DAY).toString())
            if (next == effective) return PruneOutcome(candidate, archived)
            effective = next
        }
    }

    /**
     * The newest `prunedBefore` an after-completion routine may reach: the record it counts from
     * (the latest completion, or `firstDueDate` before any completion) has to stay in the main
     * carrier, otherwise the next due date would fall back to an older completion or to
     * `firstDueDate`. A calendar routine has no such limit.
     */
    private fun pruneCeiling(payload: RoutinePayload, deviceZone: TimeZone): String? {
        val schedule = payload.definition.schedule as? RoutineSchedule.AfterCompletion ?: return null
        val latest = RoutineScheduleEngine.latestCompletion(payload.occurrences.values, deviceZone)
        return if (latest != null && isLocalDate(latest.record.scheduledDate)) {
            latest.record.scheduledDate
        } else {
            schedule.firstDueDate
        }
    }

    private fun isLocalDate(value: String): Boolean =
        try {
            LocalDate.parse(value).toString() == value
        } catch (_: Exception) {
            false
        }

    // --- Archive parts (section 6.4) -----------------------------------------------------------

    /** An archive part together with the id of the task that holds it. */
    data class PartRef(val taskId: Long, val part: RoutineArchivePart)

    sealed interface Op {
        /** Rewrite the part held by [taskId]. */
        data class Update(val taskId: Long, val part: RoutineArchivePart) : Op

        /** Create a new part task. */
        data class Create(val part: RoutineArchivePart) : Op
    }

    /** Sums entry sizes of an occurrence map so a part's JSON size is known without re-encoding. */
    private class PartSizer(header: RoutineArchivePart, json: Json) {
        private val base = partBytes(header.copy(occurrences = emptyMap()), json)
        private var entries = 0
        private var bytes = 0

        fun add(entry: Int) {
            bytes += entry
            entries += 1
        }

        fun replace(oldEntry: Int, newEntry: Int) {
            bytes += newEntry - oldEntry
        }

        /** Size of the JSON with one more entry of [extra] bytes (and the comma before it). */
        fun sizeWith(extra: Int): Int {
            val count = entries + 1
            return base + bytes + extra + (if (count > 1) count - 1 else 0)
        }

        val size: Int get() = base + bytes + (if (entries > 1) entries - 1 else 0)
    }

    /** Bytes one `"key":{record}` entry adds to a part's JSON, not counting the comma between entries. */
    private fun entryBytes(key: String, record: RoutineOccurrenceRecord, json: Json): Int =
        json.encodeToString(String.serializer(), key).encodeToByteArray().size + 1 +
            json.encodeToString(RoutineOccurrenceRecord.serializer(), record).encodeToByteArray().size

    private class Working(
        val taskId: Long?,
        val header: RoutineArchivePart,
        val occurrences: MutableMap<String, RoutineOccurrenceRecord>,
        val sizer: PartSizer,
        var changed: Boolean = false,
    ) {
        val number: Int get() = header.part
    }

    private fun working(taskId: Long?, part: RoutineArchivePart, json: Json): Working {
        val sizer = PartSizer(part, json)
        for ((key, record) in part.occurrences) sizer.add(entryBytes(key, record, json))
        return Working(taskId, part, LinkedHashMap(part.occurrences), sizer)
    }

    /**
     * Plans where [moved] occurrences go. A record whose key is already in a part is merged there
     * (last-write-wins); new keys are appended to the part with the highest number while it stays
     * within [budgetBytes], and the rest go into new parts numbered after it. Returns only the
     * parts that change; uploading the same history twice therefore writes nothing.
     */
    fun planWrites(
        routineId: String,
        existing: List<PartRef>,
        moved: Collection<RoutineOccurrenceRecord>,
        json: Json,
        budgetBytes: Int = BUDGET_BYTES,
    ): List<Op> {
        val mine = existing
            .filter { it.part.routineId == routineId }
            .sortedWith(compareBy({ it.part.part }, { it.taskId }))
            .map { working(it.taskId, it.part, json) }

        val ordered = moved.sortedWith(compareBy({ it.scheduledDate }, { it.key }))
        val appended = ArrayList<RoutineOccurrenceRecord>()

        for (record in ordered) {
            val holder = mine.firstOrNull { record.key in it.occurrences }
            if (holder == null) {
                appended += record
                continue
            }
            val current = holder.occurrences.getValue(record.key)
            val winner = RoutineScheduleEngine.newerOccurrence(current, record)
            if (winner === current) continue
            // Replacing a record can change its size by a few bytes; a part that would pass the
            // budget keeps its older copy and the newer record goes to another part (reads merge
            // by key).
            val oldEntry = entryBytes(record.key, current, json)
            val newEntry = entryBytes(record.key, record, json)
            if (holder.sizer.size + (newEntry - oldEntry) > budgetBytes) {
                appended += record
                continue
            }
            holder.occurrences[record.key] = record
            holder.sizer.replace(oldEntry, newEntry)
            holder.changed = true
        }

        val created = ArrayList<Working>()
        // The part with the highest number; among duplicates of that number the lowest task id.
        val highest = mine.maxOfOrNull { it.number } ?: 0
        var target: Working? = mine.firstOrNull { it.number == highest }
        var nextNumber = highest + 1

        for (record in appended) {
            val entry = entryBytes(record.key, record, json)
            val open = target
            val into = if (open != null && open.sizer.sizeWith(entry) <= budgetBytes) {
                open
            } else {
                working(null, RoutineArchivePart(routineId = routineId, part = nextNumber), json).also {
                    nextNumber += 1
                    created += it
                    target = it
                }
            }
            into.occurrences[record.key] = record
            into.sizer.add(entry)
            into.changed = true
        }

        return buildList {
            mine.filter { it.changed }.forEach { entry ->
                add(Op.Update(checkNotNull(entry.taskId), entry.header.copy(occurrences = entry.occurrences)))
            }
            created.forEach { entry -> add(Op.Create(entry.header.copy(occurrences = entry.occurrences))) }
        }
    }

    /**
     * The history of one routine: the main carrier's occurrences plus every archive part for it
     * (any part number, duplicates allowed), merged per key with last-write-wins. Parts of other
     * routines are ignored. The main carrier wins over the archive only when it is newer.
     */
    fun readHistory(
        routineId: String,
        main: Map<String, RoutineOccurrenceRecord>,
        parts: Collection<RoutineArchivePart>,
    ): Map<String, RoutineOccurrenceRecord> {
        val ordered = parts
            .filter { it.routineId == routineId }
            .sortedBy { it.part }
        return RoutineScheduleEngine.mergeOccurrenceMaps(main, *ordered.map { it.occurrences }.toTypedArray())
    }
}
