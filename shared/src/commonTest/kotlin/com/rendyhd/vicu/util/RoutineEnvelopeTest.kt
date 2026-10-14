package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.HealthSubtype
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineEnvelopeTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun envelopeRoundTripsWithoutTouchingVisibleDescription() {
        val payload = payload(updatedAt = "2026-08-11T08:00:00Z", updatedBy = "phone")
        val description = RoutineEnvelope.upsert("Visible notes", payload, json)

        val parsed = RoutineEnvelope.parse(description, json)

        assertTrue(parsed.isCarrier)
        assertEquals("Visible notes", parsed.body)
        assertEquals(payload, parsed.payload)
        assertEquals("Visible notes", RoutineEnvelope.strip(description))
    }

    @Test
    fun mergeKeepsNewestDefinitionAndNewestValuePerOccurrence() {
        val key = "routine-1:2026-08-11:morning"
        val local = payload("2026-08-11T10:00:00Z", "phone").copy(
            occurrences = mapOf(key to record(key, OccurrenceStatus.COMPLETED, "2026-08-11T09:00:00Z", "phone")),
        )
        val remote = payload("2026-08-11T08:00:00Z", "tablet").copy(
            occurrences = mapOf(key to record(key, OccurrenceStatus.SKIPPED, "2026-08-11T08:30:00Z", "tablet")),
        )

        val merged = RoutineEnvelope.mergePayload(local, remote)

        assertEquals("phone", merged.definition.updatedBy)
        assertEquals(OccurrenceStatus.COMPLETED, assertNotNull(merged.occurrences[key]).status)
    }

    @Test
    fun mergeComparesParsedInstantsNotStrings() {
        val key = "routine-1:2026-08-11:morning"
        // As text "...:00Z" sorts after "...:00.100Z", but it is the earlier instant.
        val local = payload("2026-08-11T08:00:00Z", "z").copy(
            occurrences = mapOf(key to record(key, OccurrenceStatus.COMPLETED, "2026-08-11T09:00:00Z", "z")),
        )
        val remote = payload("2026-08-11T08:00:00.100Z", "a").copy(
            occurrences = mapOf(key to record(key, OccurrenceStatus.SKIPPED, "2026-08-11T09:00:00.100Z", "a")),
        )

        val merged = RoutineEnvelope.mergePayload(local, remote)

        assertEquals("a", merged.definition.updatedBy)
        assertEquals(OccurrenceStatus.SKIPPED, assertNotNull(merged.occurrences[key]).status)
    }

    @Test
    fun mergeDropsOccurrencesBeforeThePrunedBeforeOfEitherSide() {
        val old = "routine-1:2026-01-05:morning"
        val kept = "routine-1:2026-08-11:morning"
        val local = payload("2026-08-11T08:00:00Z", "phone").copy(
            occurrences = mapOf(
                old to record(old, OccurrenceStatus.COMPLETED, "2026-01-05T08:00:00Z", "phone", "2026-01-05"),
                kept to record(kept, OccurrenceStatus.COMPLETED, "2026-08-11T08:00:00Z", "phone"),
            ),
        )
        val remote = payload("2026-08-11T08:00:00Z", "phone").copy(prunedBefore = "2026-02-01")

        val merged = RoutineEnvelope.mergePayload(local, remote)

        assertEquals("2026-02-01", merged.prunedBefore)
        assertEquals(setOf(kept), merged.occurrences.keys)
    }

    @Test
    fun anArchivePartIsNotAMainCarrierButStaysHidden() {
        val part = RoutineArchivePart(routineId = "routine-1", part = 1)
        val description = RoutineEnvelope.encodeArchive(part, json)

        assertTrue(description.startsWith("<!-- vicu-routine:archive:v1:"))
        assertTrue(RoutineEnvelope.hasMarker(description), "hidden like every routine metadata task")
        assertTrue(RoutineEnvelope.hasArchiveMarker(description))
        assertFalse(RoutineEnvelope.hasCarrierMarker(description))
        assertTrue(CustomListEnvelope.isAnyMetadataTask(description))
        val parsed = RoutineEnvelope.parse(description, json)
        assertFalse(parsed.isCarrier, "an archive part is never read as a routine")
        assertNull(parsed.payload)
    }

    @Test
    fun aMainCarrierIsNotAnArchivePart() {
        val description = RoutineEnvelope.upsert("", payload("2026-08-11T08:00:00Z", "phone"), json)

        assertTrue(RoutineEnvelope.hasMarker(description))
        assertTrue(RoutineEnvelope.hasCarrierMarker(description))
        assertFalse(RoutineEnvelope.hasArchiveMarker(description))
        assertFalse(RoutineEnvelope.parseArchive(description, json).isArchive)
    }

    @Test
    fun upsertKeepsAnArchiveMarkerThatIsNotItsOwn() {
        // A carrier edit must only ever replace the main marker.
        val part = RoutineEnvelope.encodeArchive(RoutineArchivePart(routineId = "routine-1", part = 1), json)
        val updated = RoutineEnvelope.upsert(part, payload("2026-08-11T08:00:00Z", "phone"), json)

        assertTrue(RoutineEnvelope.hasArchiveMarker(updated))
        assertTrue(RoutineEnvelope.hasCarrierMarker(updated))
    }

    @Test
    fun stripRemovesBothKindsOfMarker() {
        val description = "Notes\n" +
            RoutineEnvelope.encodeArchive(RoutineArchivePart(routineId = "routine-1", part = 2), json)

        assertEquals("Notes", RoutineEnvelope.strip(description))
    }

    @Test
    fun anArchivePartRoundTripsItsRecords() {
        val key = "routine-1:2025-01-11:morning"
        val part = RoutineArchivePart(
            routineId = "routine-1",
            part = 3,
            occurrences = mapOf(key to record(key, OccurrenceStatus.COMPLETED, "2025-01-11T08:00:00.000Z", "phone", "2025-01-11")),
        )

        val parsed = RoutineEnvelope.parseArchive(RoutineEnvelope.encodeArchive(part, json), json)

        assertTrue(parsed.isArchive)
        assertEquals(part, parsed.part)
        assertNull(parsed.error)
    }

    @Test
    fun aDamagedArchivePartIsReportedNotThrown() {
        val parsed = RoutineEnvelope.parseArchive("<!-- vicu-routine:archive:v1:@@@ -->", json)

        assertTrue(parsed.isArchive)
        assertNull(parsed.part)
        assertNotNull(parsed.error)
    }

    @Test
    fun anArchivePartWithoutARoutineIdOrWithABadPartNumberIsRejected() {
        fun encoded(raw: String): String {
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val body = kotlin.io.encoding.Base64.UrlSafe.encode(raw.encodeToByteArray()).trimEnd('=')
            return "<!-- vicu-routine:archive:v1:$body -->"
        }

        assertNull(RoutineEnvelope.parseArchive(encoded("""{"version":1,"routineId":"","part":1}"""), json).part)
        assertNull(RoutineEnvelope.parseArchive(encoded("""{"version":1,"routineId":"r","part":0}"""), json).part)
        assertNull(RoutineEnvelope.parseArchive(encoded("""{"version":2,"routineId":"r","part":1}"""), json).part)
        assertNotNull(RoutineEnvelope.parseArchive(encoded("""{"version":1,"routineId":"r","part":1}"""), json).part)
    }

    private fun payload(updatedAt: String, updatedBy: String) = RoutinePayload(
        definition = RoutineDefinition(
            id = "routine-1",
            name = "Creatine",
            kind = RoutineKind.HEALTH,
            healthSubtype = HealthSubtype.SUPPLEMENT,
            amount = "5",
            unit = "g",
            schedule = RoutineSchedule.Calendar(anchorDate = "2026-08-01"),
            slots = listOf(RoutineSlot("morning", "Morning", RoutinePeriod.MORNING)),
            activeFrom = "2026-08-01",
            createdAt = "2026-08-01T08:00:00Z",
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        ),
    )

    private fun record(
        key: String,
        status: OccurrenceStatus,
        modifiedAt: String,
        modifiedBy: String,
        scheduledDate: String = "2026-08-11",
    ) =
        RoutineOccurrenceRecord(
            key = key,
            routineId = "routine-1",
            slotId = "morning",
            scheduledDate = scheduledDate,
            scheduledMinutes = 480,
            timeZoneId = "Europe/Amsterdam",
            status = status,
            modifiedAt = modifiedAt,
            modifiedBy = modifiedBy,
        )
}
