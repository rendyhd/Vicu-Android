package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutineSchedule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The routine merge, pruning, archive and after-completion vectors
 * (`test-fixtures/routine-archive-v1.json`, identical in the desktop repo; see section 6 of
 * `docs/cross-app-semantics-v1.md`). Occurrences are written compactly in the file; [expand]
 * turns one into the full record the file's `about` field describes.
 */
object RoutineArchiveFixture {

    @Serializable
    data class Compact(
        val date: String,
        val status: OccurrenceStatus,
        val modifiedAt: String,
        val modifiedBy: String,
        val loggedAt: String = "",
        val timeZoneId: String = "UTC",
    )

    @Serializable
    data class Side(val prunedBefore: String = "", val occurrences: List<Compact> = emptyList())

    @Serializable
    data class MergeExpect(val prunedBefore: String, val occurrences: Map<String, String>)

    @Serializable
    data class MergeVector(val name: String, val local: Side, val remote: Side, val expect: MergeExpect)

    @Serializable
    data class DefinitionSide(val name: String, val updatedAt: String, val updatedBy: String)

    @Serializable
    data class DefinitionMergeVector(
        val name: String,
        val local: DefinitionSide,
        val remote: DefinitionSide,
        val expectName: String,
    )

    @Serializable
    data class PruneExpect(val prunedBefore: String, val kept: List<String>, val archived: List<String>)

    @Serializable
    data class PruneVector(
        val name: String,
        val today: String,
        val prunedBefore: String = "",
        val schedule: RoutineSchedule? = null,
        val occurrences: List<Compact>,
        val expect: PruneExpect,
    )

    @Serializable
    data class PartVector(val part: Int, val occurrences: List<Compact>)

    @Serializable
    data class ArchiveReadVector(
        val name: String,
        val main: List<Compact>,
        val parts: List<PartVector>,
        val expect: Map<String, String>,
    )

    @Serializable
    data class AfterCompletionVector(
        val name: String,
        val intervalDays: Int,
        val firstDueDate: String,
        val occurrences: List<Compact>,
        val expectDue: String,
    )

    @Serializable
    data class Fixture(
        val contractVersion: Int,
        val baseDefinition: RoutineDefinition,
        val merge: List<MergeVector>,
        val definitionMerge: List<DefinitionMergeVector>,
        val prune: List<PruneVector>,
        val archiveRead: List<ArchiveReadVector>,
        val afterCompletion: List<AfterCompletionVector>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val fixture: Fixture by lazy {
        json.decodeFromString(Fixture.serializer(), CrossAppFixture.readTestFixture("routine-archive-v1.json"))
    }

    const val ROUTINE_ID = "r1"
    const val SLOT_ID = "s1"

    /** A compact occurrence as the full record: routine r1, slot s1, 08:00, UTC unless given. */
    fun expand(compact: Compact): RoutineOccurrenceRecord = RoutineOccurrenceRecord(
        key = "$ROUTINE_ID:${compact.date}:$SLOT_ID",
        routineId = ROUTINE_ID,
        slotId = SLOT_ID,
        scheduledDate = compact.date,
        scheduledMinutes = 480,
        timeZoneId = compact.timeZoneId,
        status = compact.status,
        loggedAt = compact.loggedAt,
        modifiedAt = compact.modifiedAt,
        modifiedBy = compact.modifiedBy,
        note = "",
    )

    fun toMap(list: List<Compact>): Map<String, RoutineOccurrenceRecord> =
        list.map(::expand).associateBy { it.key }

    /** `{ date: status }`, the shape the vectors expect. */
    fun statusByDate(map: Map<String, RoutineOccurrenceRecord>): Map<String, String> =
        map.values.sortedBy { it.scheduledDate }.associate { it.scheduledDate to it.status.name }

    fun payload(
        side: Side,
        definition: RoutineDefinition = fixture.baseDefinition,
    ): RoutinePayload = RoutinePayload(
        definition = definition,
        occurrences = toMap(side.occurrences),
        prunedBefore = side.prunedBefore,
    )

    /** The base definition, or a chore with the vector's after-completion schedule. */
    fun definitionFor(schedule: RoutineSchedule?): RoutineDefinition =
        if (schedule == null) {
            fixture.baseDefinition
        } else {
            fixture.baseDefinition.copy(kind = RoutineKind.CHORE, healthSubtype = null, schedule = schedule)
        }
}
