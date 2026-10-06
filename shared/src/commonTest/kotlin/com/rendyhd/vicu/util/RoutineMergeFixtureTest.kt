package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.util.CrossAppFixture.zones
import com.rendyhd.vicu.util.RoutineArchiveFixture.fixture
import com.rendyhd.vicu.util.RoutineArchiveFixture.payload
import com.rendyhd.vicu.util.RoutineArchiveFixture.statusByDate
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `merge`, `definitionMerge` and `afterCompletion` vectors of
 * `test-fixtures/routine-archive-v1.json` (docs/cross-app-semantics-v1.md, sections 6.3 and 6.5).
 * The completion date depends on the device zone when an occurrence has none of its own, so the
 * schedule vectors run in all three contract zones and must agree.
 */
class RoutineMergeFixtureTest {

    @Test
    fun `the fixture is the version this code implements`() {
        assertEquals(1, fixture.contractVersion)
    }

    @Test
    fun `merge vectors, in both argument orders`() {
        for (vector in fixture.merge) {
            val forward = RoutineEnvelope.mergePayload(payload(vector.local), payload(vector.remote))
            assertEquals(vector.expect.prunedBefore, forward.prunedBefore, vector.name)
            assertEquals(vector.expect.occurrences, statusByDate(forward.occurrences), vector.name)

            val backward = RoutineEnvelope.mergePayload(payload(vector.remote), payload(vector.local))
            assertEquals(vector.expect.prunedBefore, backward.prunedBefore, "${vector.name} (swapped)")
            assertEquals(vector.expect.occurrences, statusByDate(backward.occurrences), "${vector.name} (swapped)")
        }
    }

    @Test
    fun `definition merge vectors compare parsed instants`() {
        for (vector in fixture.definitionMerge) {
            fun withDefinition(side: RoutineArchiveFixture.DefinitionSide) = payload(
                RoutineArchiveFixture.Side(),
                fixture.baseDefinition.copy(name = side.name, updatedAt = side.updatedAt, updatedBy = side.updatedBy),
            )
            val local = withDefinition(vector.local)
            val remote = withDefinition(vector.remote)
            assertEquals(vector.expectName, RoutineEnvelope.mergePayload(local, remote).definition.name, vector.name)
            assertEquals(vector.expectName, RoutineEnvelope.mergePayload(remote, local).definition.name, "${vector.name} (swapped)")
        }
    }

    @Test
    fun `after completion vectors count from the completion date in every zone`() {
        for (zone in zones) for (vector in fixture.afterCompletion) {
            val definition = RoutineArchiveFixture.definitionFor(
                RoutineSchedule.AfterCompletion(vector.intervalDays, vector.firstDueDate),
            )
            val source = payload(RoutineArchiveFixture.Side(occurrences = vector.occurrences), definition)

            assertEquals(
                LocalDate.parse(vector.expectDue),
                RoutineScheduleEngine.dueDate(source, zone),
                "${vector.name} in $zone",
            )
        }
    }

    @Test
    fun `a calendar routine has no completion based due date`() {
        val source = payload(RoutineArchiveFixture.Side())

        assertEquals(RoutineKind.HEALTH, source.definition.kind)
        assertEquals(null, RoutineScheduleEngine.dueDate(source, zones.first()))
    }
}
