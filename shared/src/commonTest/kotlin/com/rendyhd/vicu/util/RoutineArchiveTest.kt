package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pruning and archive-part planning beyond the shared vectors: the size budget, the guards and
 * which part a record is written to (docs/cross-app-semantics-v1.md, sections 6.1, 6.2 and 6.4).
 */
class RoutineArchiveTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val today = LocalDate(2026, 10, 6)
    private val zone = TimeZone.of("Europe/Amsterdam")

    private val routineId = "3f2b8c1e-5d4a-4b7e-9c1d-0a1b2c3d4e5f"
    private val slotIds = listOf(
        "a1b2c3d4-0000-4000-8000-000000000001",
        "a1b2c3d4-0000-4000-8000-000000000002",
        "a1b2c3d4-0000-4000-8000-000000000003",
    )
    private val device = "9d8c7b6a-1111-4222-8333-444455556666"

    private fun definition(
        schedule: RoutineSchedule = RoutineSchedule.Calendar(anchorDate = "2024-01-01"),
        slots: Int = 1,
    ) = RoutineDefinition(
        id = routineId,
        name = "Vitamin D",
        kind = if (schedule is RoutineSchedule.AfterCompletion) RoutineKind.CHORE else RoutineKind.HEALTH,
        schedule = schedule,
        slots = slotIds.take(slots).mapIndexed { index, id ->
            RoutineSlot(id = id, label = "Slot $index", period = RoutinePeriod.ANYTIME, reminderMinutes = 480 + index * 60)
        },
        activeFrom = "2024-01-01",
        createdAt = "2024-01-01T08:00:00.000Z",
        updatedAt = "2024-01-01T08:00:00.000Z",
        updatedBy = device,
    )

    private fun record(
        date: LocalDate,
        slot: String = slotIds[0],
        status: OccurrenceStatus = OccurrenceStatus.COMPLETED,
        modifiedAt: String = "${date}T07:30:12.123Z",
        modifiedBy: String = device,
        routine: String = routineId,
    ): RoutineOccurrenceRecord = RoutineOccurrenceRecord(
        key = "$routine:$date:$slot",
        routineId = routine,
        slotId = slot,
        scheduledDate = date.toString(),
        scheduledMinutes = 480,
        timeZoneId = "Europe/Amsterdam",
        status = status,
        loggedAt = if (status == OccurrenceStatus.COMPLETED) "${date}T07:30:12.123Z" else "",
        modifiedAt = modifiedAt,
        modifiedBy = modifiedBy,
    )

    /** One record per slot per day over the last [days] days, today included. */
    private fun history(days: Int, slots: Int = 1): Map<String, RoutineOccurrenceRecord> {
        val records = LinkedHashMap<String, RoutineOccurrenceRecord>()
        for (offset in 0 until days) {
            val date = today.minus(offset, DateTimeUnit.DAY)
            for (slot in slotIds.take(slots)) record(date, slot).let { records[it.key] = it }
        }
        return records
    }

    private fun payload(
        occurrences: Map<String, RoutineOccurrenceRecord>,
        prunedBefore: String = "",
        definition: RoutineDefinition = definition(),
    ) = RoutinePayload(definition = definition, occurrences = occurrences, prunedBefore = prunedBefore)

    private fun prune(source: RoutinePayload, budget: Int = RoutineArchive.BUDGET_BYTES) =
        RoutineArchive.prune(source, today, json, zone, budget)

    // --- Rolling window and budget --------------------------------------------------------

    @Test
    fun `the contract constants`() {
        assertEquals(393_216, RoutineArchive.BUDGET_BYTES)
        assertEquals(524_288, RoutineArchive.HARD_LIMIT_BYTES)
        assertEquals(400, RoutineArchive.WINDOW_DAYS)
        assertEquals(30, RoutineArchive.PRUNE_STEP_DAYS)
        assertEquals("Vicu routine archive", RoutineArchive.TITLE)
        assertEquals("vicu-routine:archive", RoutineArchive.SEARCH)
    }

    @Test
    fun `the window keeps the cutoff day itself and archives the day before`() {
        val cutoff = today.minus(400, DateTimeUnit.DAY)
        val source = payload(
            mapOf(
                record(cutoff.minus(1, DateTimeUnit.DAY)).let { it.key to it },
                record(cutoff).let { it.key to it },
            ),
        )

        val outcome = prune(source)

        assertEquals(cutoff.toString(), outcome.payload.prunedBefore)
        assertEquals(listOf(cutoff.minus(1, DateTimeUnit.DAY).toString()), outcome.archived.map { it.scheduledDate })
        assertEquals(listOf(cutoff.toString()), outcome.payload.occurrences.values.map { it.scheduledDate })
    }

    @Test
    fun `nothing to archive leaves the payload and its prunedBefore alone`() {
        val source = payload(history(30), prunedBefore = "")

        val outcome = prune(source)

        assertEquals(source, outcome.payload)
        assertTrue(outcome.archived.isEmpty())
    }

    @Test
    fun `an existing prunedBefore is kept when there is nothing older to move`() {
        val source = payload(history(30), prunedBefore = "2025-01-01")

        val outcome = prune(source)

        assertEquals("2025-01-01", outcome.payload.prunedBefore)
        assertTrue(outcome.archived.isEmpty())
    }

    @Test
    fun `a payload over the budget moves the cutoff forward in whole 30 day steps until it fits`() {
        val source = payload(history(400))
        val full = RoutineArchive.payloadBytes(source, json)
        val budget = full - 1_000 // must lose a few days of history, which takes one whole step

        val outcome = prune(source, budget)

        val cutoff = today.minus(400, DateTimeUnit.DAY)
        val prunedBefore = LocalDate.parse(outcome.payload.prunedBefore)
        assertTrue(RoutineArchive.payloadBytes(outcome.payload, json) <= budget)
        assertEquals(0, cutoff.daysUntil(prunedBefore) % 30, "moves in steps of 30 days from the 400 day cutoff")
        assertTrue(prunedBefore > cutoff)
        // And it is the smallest step that fits: one step less would still be over budget.
        val oneLess = prunedBefore.minus(30, DateTimeUnit.DAY)
        val candidate = source.copy(
            occurrences = source.occurrences.filterValues { it.scheduledDate >= oneLess.toString() },
            prunedBefore = oneLess.toString(),
        )
        assertTrue(RoutineArchive.payloadBytes(candidate, json) > budget)
        // What left the payload is exactly what is older than the new prunedBefore.
        assertEquals(
            source.occurrences.values.count { it.scheduledDate < prunedBefore.toString() },
            outcome.archived.size,
        )
        assertEquals(source.occurrences.size, outcome.archived.size + outcome.payload.occurrences.size)
    }

    @Test
    fun `a three slot routine with a year of history is shortened to fit 384 KiB`() {
        val source = payload(history(400, slots = 3), definition = definition(slots = 3))
        assertTrue(RoutineArchive.payloadBytes(source, json) > RoutineArchive.BUDGET_BYTES, "the scenario must be over budget")

        val outcome = prune(source)

        assertTrue(RoutineArchive.payloadBytes(outcome.payload, json) <= RoutineArchive.BUDGET_BYTES)
        assertTrue(LocalDate.parse(outcome.payload.prunedBefore) > today.minus(400, DateTimeUnit.DAY))
        assertTrue(outcome.payload.occurrences.values.any { it.scheduledDate == today.toString() }, "today stays")
        assertTrue(outcome.archived.isNotEmpty())
    }

    @Test
    fun `the cutoff never passes today however small the budget`() {
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val source = payload(
            mapOf(
                record(yesterday).let { it.key to it },
                record(today).let { it.key to it },
            ),
        )

        val outcome = prune(source, budget = 1)

        assertEquals(today.toString(), outcome.payload.prunedBefore)
        assertEquals(listOf(today.toString()), outcome.payload.occurrences.values.map { it.scheduledDate })
        assertEquals(listOf(yesterday.toString()), outcome.archived.map { it.scheduledDate })
    }

    @Test
    fun `a prunedBefore in the future of the computed cutoff is kept, never moved back`() {
        val source = payload(
            mapOf(
                record(today.minus(380, DateTimeUnit.DAY)).let { it.key to it },
                record(today.minus(10, DateTimeUnit.DAY)).let { it.key to it },
            ),
            prunedBefore = today.minus(300, DateTimeUnit.DAY).toString(),
        )

        val outcome = prune(source)

        assertEquals(today.minus(300, DateTimeUnit.DAY).toString(), outcome.payload.prunedBefore)
        assertEquals(1, outcome.archived.size)
    }

    // --- The after_completion guard -----------------------------------------------------------

    @Test
    fun `an after completion routine keeps the record it counts from even when the size forces a shrink`() {
        val schedule = RoutineSchedule.AfterCompletion(intervalDays = 7, firstDueDate = "2024-01-01")
        val lastDone = today.minus(100, DateTimeUnit.DAY)
        // Everything after lastDone is skipped so that lastDone stays the latest completion.
        val adjusted = history(400).mapValues { (_, r) ->
            if (r.scheduledDate > lastDone.toString()) {
                r.copy(status = OccurrenceStatus.SKIPPED, loggedAt = "")
            } else {
                r
            }
        }
        val source = payload(adjusted, definition = definition(schedule))

        val outcome = prune(source, budget = 1)

        assertEquals(lastDone.toString(), outcome.payload.prunedBefore, "stops at the latest completion")
        assertTrue(outcome.payload.occurrences.values.any { it.scheduledDate == lastDone.toString() })
    }

    @Test
    fun `an after completion routine without a completion keeps firstDueDate however old`() {
        val schedule = RoutineSchedule.AfterCompletion(intervalDays = 7, firstDueDate = "2025-03-01")
        val skipped = (1..5).map { record(LocalDate(2025, 2, it), status = OccurrenceStatus.SKIPPED) } +
            record(LocalDate(2025, 3, 1), status = OccurrenceStatus.SKIPPED)
        val source = payload(skipped.associateBy { it.key }, definition = definition(schedule))

        val outcome = prune(source)

        assertEquals("2025-03-01", outcome.payload.prunedBefore)
        assertEquals(listOf("2025-03-01"), outcome.payload.occurrences.values.map { it.scheduledDate })
    }

    @Test
    fun `the completion date, not the scheduled date, decides which completion is the latest`() {
        val schedule = RoutineSchedule.AfterCompletion(intervalDays = 7, firstDueDate = "2024-01-01")
        // Scheduled long ago but completed yesterday: that is the latest completion, although
        // another record has a later scheduled date.
        val latest = record(LocalDate(2024, 6, 1)).copy(loggedAt = "${today.minus(1, DateTimeUnit.DAY)}T07:30:00.000Z")
        val scheduledLater = record(LocalDate(2024, 9, 1)).copy(loggedAt = "2024-09-01T07:30:00.000Z")
        val older = record(LocalDate(2024, 5, 1), status = OccurrenceStatus.SKIPPED)
        val source = payload(
            listOf(latest, scheduledLater, older).associateBy { it.key },
            definition = definition(schedule),
        )

        val outcome = prune(source)

        assertEquals("2024-06-01", outcome.payload.prunedBefore, "the ceiling is the latest completion's scheduled date")
        assertEquals(listOf("2024-05-01"), outcome.archived.map { it.scheduledDate })
        assertEquals(setOf(latest.key, scheduledLater.key), outcome.payload.occurrences.keys)
    }

    // --- Part planning (section 6.4) -------------------------------------------------------------

    private fun ref(taskId: Long, part: Int, vararg records: RoutineOccurrenceRecord, routine: String = routineId) =
        RoutineArchive.PartRef(
            taskId,
            RoutineArchivePart(routineId = routine, part = part, occurrences = records.associateBy { it.key }),
        )

    private fun plan(
        existing: List<RoutineArchive.PartRef>,
        moved: List<RoutineOccurrenceRecord>,
        budget: Int = RoutineArchive.BUDGET_BYTES,
    ) = RoutineArchive.planWrites(routineId, existing, moved, json, budget)

    private fun partBytes(part: RoutineArchivePart) = RoutineArchive.partBytes(part, json)

    @Test
    fun `with no part yet the moved history goes into a new part 1`() {
        val moved = listOf(record(LocalDate(2025, 1, 1)), record(LocalDate(2025, 1, 2)))

        val ops = plan(emptyList(), moved)

        val create = assertIs<RoutineArchive.Op.Create>(ops.single())
        assertEquals(1, create.part.part)
        assertEquals(routineId, create.part.routineId)
        assertEquals(moved.map { it.key }.toSet(), create.part.occurrences.keys)
    }

    @Test
    fun `new records are appended to the part with the highest number while it fits`() {
        val first = record(LocalDate(2025, 1, 1))
        val second = record(LocalDate(2025, 1, 2))
        val existing = listOf(ref(11, 1, first), ref(12, 2, second))

        val ops = plan(existing, listOf(record(LocalDate(2025, 1, 3))))

        val update = assertIs<RoutineArchive.Op.Update>(ops.single())
        assertEquals(12L, update.taskId)
        assertEquals(2, update.part.part)
        assertEquals(2, update.part.occurrences.size)
    }

    @Test
    fun `a record that does not fit starts part number plus one`() {
        val existing = listOf(ref(11, 1, record(LocalDate(2025, 1, 1))), ref(12, 2, record(LocalDate(2025, 1, 2))))
        val oneMore = record(LocalDate(2025, 1, 3))
        // A budget that holds exactly what part 2 holds now and nothing more.
        val budget = partBytes(existing[1].part)

        val ops = plan(existing, listOf(oneMore), budget)

        val create = assertIs<RoutineArchive.Op.Create>(ops.single())
        assertEquals(3, create.part.part)
        assertEquals(setOf(oneMore.key), create.part.occurrences.keys)
    }

    @Test
    fun `parts never pass the budget however much is moved`() {
        val moved = history(400, slots = 3).values.toList()
        val budget = 60_000

        val ops = plan(emptyList(), moved, budget)

        assertTrue(ops.size > 2, "the history needs several parts")
        val parts = ops.map { assertIs<RoutineArchive.Op.Create>(it).part }
        assertEquals((1..parts.size).toList(), parts.map { it.part }, "numbered 1, 2, 3, ...")
        for (part in parts) assertTrue(partBytes(part) <= budget, "part ${part.part} is ${partBytes(part)} bytes")
        assertEquals(moved.map { it.key }.toSet(), parts.flatMap { it.occurrences.keys }.toSet())
        assertEquals(moved.size, parts.sumOf { it.occurrences.size }, "no record is written twice")
    }

    @Test
    fun `at the default budget a long history splits into parts of at most 384 KiB`() {
        val moved = history(400, slots = 3).values.toList() +
            (400 until 800).flatMap { offset ->
                slotIds.map { record(today.minus(offset, DateTimeUnit.DAY), it) }
            }

        val ops = plan(emptyList(), moved)

        assertTrue(ops.size >= 2)
        for (op in ops) {
            val part = assertIs<RoutineArchive.Op.Create>(op).part
            assertTrue(partBytes(part) <= RoutineArchive.BUDGET_BYTES)
            val description = RoutineEnvelope.encodeArchive(part, json) // under the hard limit too
            assertTrue(description.startsWith("<!-- vicu-routine:archive:v1:"))
        }
    }

    @Test
    fun `a newer record for an archived key is written to the part that holds the key`() {
        val old = record(LocalDate(2025, 1, 1), status = OccurrenceStatus.COMPLETED)
        val existing = listOf(ref(11, 1, old), ref(12, 2, record(LocalDate(2025, 6, 1))))
        val edited = old.copy(status = OccurrenceStatus.SKIPPED, loggedAt = "", modifiedAt = "2026-10-01T08:00:00.000Z")

        val ops = plan(existing, listOf(edited))

        val update = assertIs<RoutineArchive.Op.Update>(ops.single())
        assertEquals(11L, update.taskId, "not the highest part")
        assertEquals(OccurrenceStatus.SKIPPED, assertNotNull(update.part.occurrences[old.key]).status)
    }

    @Test
    fun `an older or identical record for an archived key changes nothing`() {
        val current = record(LocalDate(2025, 1, 1)).copy(modifiedAt = "2025-02-01T08:00:00.000Z")
        val existing = listOf(ref(11, 1, current))
        val stale = current.copy(status = OccurrenceStatus.SKIPPED, modifiedAt = "2025-01-15T08:00:00.000Z")

        assertTrue(plan(existing, listOf(stale)).isEmpty())
        assertTrue(plan(existing, listOf(current)).isEmpty(), "uploading the same history twice writes nothing")
    }

    @Test
    fun `among duplicate parts with the highest number the lowest task id is appended to`() {
        val existing = listOf(
            ref(31, 2, record(LocalDate(2025, 1, 2))),
            ref(30, 2, record(LocalDate(2025, 1, 1))),
        )

        val ops = plan(existing, listOf(record(LocalDate(2025, 1, 3))))

        assertEquals(30L, assertIs<RoutineArchive.Op.Update>(ops.single()).taskId)
    }

    @Test
    fun `parts of other routines are never touched`() {
        val other = "other-routine"
        val foreign = ref(40, 5, record(LocalDate(2025, 1, 1), routine = other), routine = other)

        val ops = plan(listOf(foreign), listOf(record(LocalDate(2025, 1, 3))))

        val create = assertIs<RoutineArchive.Op.Create>(ops.single())
        assertEquals(1, create.part.part, "numbering starts at 1 for this routine")
        assertFalse(ops.any { it is RoutineArchive.Op.Update })
    }

    @Test
    fun `a newer record that would overflow its part goes to another part instead`() {
        val old = record(LocalDate(2025, 1, 1))
        val existing = listOf(ref(11, 1, old))
        val longer = old.copy(note = "x".repeat(200), modifiedAt = "2026-10-01T08:00:00.000Z")
        val budget = partBytes(existing[0].part) + 10

        val ops = plan(existing, listOf(longer), budget)

        assertTrue(ops.none { it is RoutineArchive.Op.Update && it.taskId == 11L }, "the full part is left as it was")
        val create = assertIs<RoutineArchive.Op.Create>(ops.single())
        assertEquals(2, create.part.part)
        // Reading merges by key, so the newer record still wins.
        val history = RoutineArchive.readHistory(
            routineId,
            emptyMap(),
            listOf(existing[0].part, create.part),
        )
        assertEquals("x".repeat(200), assertNotNull(history[old.key]).note)
    }

    // --- Reading ------------------------------------------------------------------------------------

    @Test
    fun `history ignores parts of other routines and lets a newer main record win`() {
        val inPart = record(LocalDate(2025, 1, 1), status = OccurrenceStatus.COMPLETED)
        val inMain = inPart.copy(status = OccurrenceStatus.SKIPPED, modifiedAt = "2026-01-01T08:00:00.000Z")
        val foreign = record(LocalDate(2025, 1, 2), routine = "other")

        val history = RoutineArchive.readHistory(
            routineId,
            mapOf(inMain.key to inMain),
            listOf(
                RoutineArchivePart(routineId = routineId, part = 1, occurrences = mapOf(inPart.key to inPart)),
                RoutineArchivePart(routineId = "other", part = 1, occurrences = mapOf(foreign.key to foreign)),
            ),
        )

        assertEquals(setOf(inPart.key), history.keys)
        assertEquals(OccurrenceStatus.SKIPPED, history.getValue(inPart.key).status)
    }
}
