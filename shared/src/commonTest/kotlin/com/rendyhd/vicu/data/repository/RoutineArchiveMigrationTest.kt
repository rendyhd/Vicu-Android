package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.RoutineRig.Companion.definition
import com.rendyhd.vicu.data.repository.RoutineRig.Companion.payload
import com.rendyhd.vicu.data.repository.RoutineRig.Companion.record
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RoutineArchive
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Android 1.8.x kept pruned routine history in a phone-only table. The first version with
 * server-side archives uploads it into archive parts and then forgets the local copy
 * (docs/cross-app-semantics-v1.md, section 6.6). The upload must be safe to repeat and to resume.
 */
class RoutineArchiveMigrationTest {

    private val a = record("2025-01-10")
    private val b = record("2025-01-11")
    private val c = record("2025-01-12", OccurrenceStatus.SKIPPED)
    private val recent = record("2026-10-05")

    private suspend fun RoutineRig.seedLegacyRoutine() {
        seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        archiveDao.add(a.toLegacyRow(), b.toLegacyRow(), c.toLegacyRow())
    }

    @Test
    fun `the phone only history is uploaded into an archive part and the local copy is dropped`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedLegacyRoutine()

        val result = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Success<*>>(result)
        val part = rig.serverPart(rig.server.archiveRows().single().id)
        assertEquals(setOf(a.key, b.key, c.key), part.occurrences.keys)
        assertEquals(OccurrenceStatus.SKIPPED, part.occurrences.getValue(c.key).status)
        assertEquals("r1", part.routineId)
        assertEquals(1, part.part)
        assertTrue(rig.archiveDao.all.isEmpty(), "dropped only after the part was written and read back")
        assertEquals(5L, rig.server.archiveRows().single().projectId, "in the carrier's project")
    }

    @Test
    fun `it merges with parts that are already on the server`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedLegacyRoutine()
        // Another device already archived day a (older than the phone's copy) and day d.
        val olderA = a.copy(status = OccurrenceStatus.SKIPPED, loggedAt = "", modifiedAt = "2025-01-09T08:00:00.000Z", modifiedBy = "tablet")
        val d = record("2025-01-13", modifiedBy = "tablet")
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(olderA.key to olderA, d.key to d)))

        rig.repository.migrateLocalArchive()

        assertEquals(listOf(200L), rig.server.archiveRows().map { it.id }, "appended to the part that is there")
        val part = rig.serverPart(200)
        assertEquals(setOf(a.key, b.key, c.key, d.key), part.occurrences.keys)
        assertEquals(OccurrenceStatus.COMPLETED, part.occurrences.getValue(a.key).status, "the newer write wins")
        assertTrue(rig.archiveDao.all.isEmpty())
    }

    @Test
    fun `running it again after it finished does nothing`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedLegacyRoutine()
        rig.repository.migrateLocalArchive()
        val requestsAfterFirstRun = rig.server.requests.size

        val second = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Success<*>>(second)
        assertEquals(requestsAfterFirstRun, rig.server.requests.size, "an empty table needs no request")
    }

    @Test
    fun `repeating an upload that was cut short writes nothing twice`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedLegacyRoutine()
        rig.repository.migrateLocalArchive()
        // The process died after the upload and before the local rows were deleted.
        rig.archiveDao.add(a.toLegacyRow(), b.toLegacyRow(), c.toLegacyRow())
        val partsBefore = rig.server.archiveRows().map { it.id to it.description }
        rig.server.requests.clear()

        val result = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(partsBefore, rig.server.archiveRows().map { it.id to it.description })
        assertTrue(rig.server.requests.none { it.method == "POST" || it.method == "PATCH" })
        assertTrue(rig.archiveDao.all.isEmpty())
    }

    @Test
    fun `offline the history stays on the phone and a later run uploads it`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedLegacyRoutine()
        rig.server.failure = { HttpStatusCode.ServiceUnavailable }

        val offline = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Error>(offline)
        assertEquals(3, rig.archiveDao.all.size, "nothing is lost")
        assertTrue(rig.server.archiveRows().isEmpty())

        rig.server.failure = { null }
        val online = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Success<*>>(online)
        assertTrue(rig.archiveDao.all.isEmpty())
        assertEquals(3, rig.serverPart(rig.server.archiveRows().single().id).occurrences.size)
    }

    @Test
    fun `a routine that fails does not hold back the others`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val second = definition().copy(id = "r2", name = "Magnesium")
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        rig.seedCarrier(101, payload(record("2026-10-05", routineId = "r2"), prunedBefore = "2025-09-01", definition = second))
        rig.archiveDao.add(a.toLegacyRow(), record("2025-01-10", routineId = "r2").toLegacyRow())
        var creates = 0
        rig.server.failure = {
            if (it.method == "POST" && it.path.startsWith("/projects/") && creates++ == 0) HttpStatusCode.UnprocessableEntity else null
        }

        val first = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Error>(first)
        assertEquals(1, rig.archiveDao.all.size, "the routine that failed keeps its rows")
        assertEquals(1, rig.server.archiveRows().size, "the other one was uploaded")

        val again = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Success<*>>(again)
        assertTrue(rig.archiveDao.all.isEmpty())
        assertEquals(2, rig.server.archiveRows().size)
    }

    @Test
    fun `a large history is split into parts that each fit the budget`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        // 2400 days of history, far more than one 384 KiB part holds.
        val start = LocalDate(2018, 1, 1)
        val many = (0 until 2_400).map { offset ->
            record(start.plus(offset, DateTimeUnit.DAY).toString())
                .copy(note = "x".repeat(150))
        }
        rig.archiveDao.add(*many.map { it.toLegacyRow() }.toTypedArray())

        val result = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Success<*>>(result)
        val rows = rig.server.archiveRows()
        assertTrue(rows.size >= 2, "needs several parts, got ${rows.size}")
        val parts = rows.map { rig.serverPart(it.id) }
        assertEquals((1..parts.size).toList(), parts.map { it.part }.sorted())
        for (part in parts) assertTrue(RoutineArchive.partBytes(part, rig.json) <= RoutineArchive.BUDGET_BYTES)
        assertEquals(2_400, parts.flatMap { it.occurrences.keys }.toSet().size)
        assertTrue(rig.archiveDao.all.isEmpty())
    }

    @Test
    fun `history of a routine that is not in the carrier cache waits for it`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.archiveDao.add(a.toLegacyRow())

        val result = rig.repository.migrateLocalArchive()

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(1, rig.archiveDao.all.size, "the cache may just be empty; keep it until the routine shows up")
        assertTrue(rig.server.requests.isEmpty())

        // Once the carrier is there it goes up.
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        rig.repository.migrateLocalArchive()
        assertTrue(rig.archiveDao.all.isEmpty())
        assertEquals(1, rig.server.archiveRows().size)
    }

    @Test
    fun `history of a routine that is gone from a fresh carrier cache is dropped`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.archiveDao.add(a.toLegacyRow())

        val result = rig.repository.migrateLocalArchive(carriersAuthoritative = true)

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(rig.archiveDao.all.isEmpty(), "the routine was deleted, its history has nowhere to go")
        assertTrue(rig.server.requests.isEmpty())
    }

    @Test
    fun `a carrier that only exists on the phone is skipped until it is synced`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(-5, payload(recent, prunedBefore = "2025-09-01"))
        rig.archiveDao.add(a.toLegacyRow())

        val result = rig.repository.migrateLocalArchive(carriersAuthoritative = true)

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(1, rig.archiveDao.all.size)
        assertTrue(rig.server.archiveRows().isEmpty())
    }

    @Test
    fun `finalizing routines also runs the upload`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedLegacyRoutine()

        rig.repository.finalizeAndPrune()

        assertTrue(rig.archiveDao.all.isEmpty())
        assertEquals(1, rig.server.archiveRows().size)
    }

    @Test
    fun `finalizing carries on when the history upload itself breaks`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(record("2026-10-05"), definition = definition()))
        rig.archiveDao.failReads = true

        val result = rig.repository.finalizeAndPrune()

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(
            rig.serverPayload(100).occurrences.values.any { it.status == OccurrenceStatus.NOT_LOGGED },
            "the past days were still finalized",
        )
    }

    @Test
    fun `the history screen shows phone only history until it is uploaded`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedLegacyRoutine()
        rig.server.failure = { HttpStatusCode.ServiceUnavailable }

        val history = rig.repository.observeHistory("r1").first { it.size == 4 }

        assertEquals(4, history.size)
    }
}
