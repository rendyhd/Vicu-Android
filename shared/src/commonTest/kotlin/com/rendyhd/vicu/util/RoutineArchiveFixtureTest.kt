package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.util.CrossAppFixture.zones
import com.rendyhd.vicu.util.RoutineArchiveFixture.definitionFor
import com.rendyhd.vicu.util.RoutineArchiveFixture.fixture
import com.rendyhd.vicu.util.RoutineArchiveFixture.payload
import com.rendyhd.vicu.util.RoutineArchiveFixture.statusByDate
import com.rendyhd.vicu.util.RoutineArchiveFixture.toMap
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `prune` and `archiveRead` vectors of `test-fixtures/routine-archive-v1.json`
 * (docs/cross-app-semantics-v1.md, sections 6.2 and 6.4), in the three contract time zones.
 * Pruning looks at the after-completion record through the device zone, so all three must give
 * the same answers.
 */
class RoutineArchiveFixtureTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `prune vectors`() {
        for (zone in zones) for (vector in fixture.prune) {
            val source = payload(
                RoutineArchiveFixture.Side(vector.prunedBefore, vector.occurrences),
                definitionFor(vector.schedule),
            )

            val outcome = RoutineArchive.prune(source, LocalDate.parse(vector.today), json, zone)

            val label = "${vector.name} in $zone"
            assertEquals(vector.expect.prunedBefore, outcome.payload.prunedBefore, label)
            assertEquals(
                vector.expect.kept,
                outcome.payload.occurrences.values.map { it.scheduledDate }.sorted(),
                "$label: kept",
            )
            assertEquals(
                vector.expect.archived,
                outcome.archived.map { it.scheduledDate }.sorted(),
                "$label: archived",
            )
        }
    }

    @Test
    fun `archive read vectors merge the main carrier with every part in either order`() {
        for (vector in fixture.archiveRead) {
            val parts = vector.parts.map {
                RoutineArchivePart(routineId = RoutineArchiveFixture.ROUTINE_ID, part = it.part, occurrences = toMap(it.occurrences))
            }
            val main = toMap(vector.main)

            val forward = RoutineArchive.readHistory(RoutineArchiveFixture.ROUTINE_ID, main, parts)
            val backward = RoutineArchive.readHistory(RoutineArchiveFixture.ROUTINE_ID, main, parts.reversed())

            assertEquals(vector.expect, statusByDate(forward), vector.name)
            assertEquals(vector.expect, statusByDate(backward), "${vector.name} (reversed)")
        }
    }
}
